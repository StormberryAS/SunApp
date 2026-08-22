package no.stormberry.sunapp.alarm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import androidx.activity.ComponentActivity
import androidx.core.content.edit
import no.stormberry.sunapp.MainActivity
import java.time.LocalTime

/**
 * SunApp's half of the `AlarmClock` intent API: the four public actions every Android alarm
 * clock is expected to answer.
 *
 * ### Why an alarm clock should implement this
 *
 * `ACTION_SET_ALARM` and friends are how Assistant, Tasker, an accessibility service or
 * another app ask *the device's* alarm clock to do something without knowing which app that
 * is. Answering them is what makes SunApp an alarm clock rather than an app that happens to
 * make a noise, and it is also the evidence a store reviewer looks for when an app claims the
 * exact-alarm and full-screen-intent grants that only alarm clocks are entitled to.
 *
 * ### Why it is a trampoline and nothing else
 *
 * This activity has no layout, no Compose content and no state. It reads an intent, either
 * hands a request to the app's real UI or fires a broadcast at the alarm runtime, and
 * finishes inside `onCreate`. That keeps the entry point that any app on the device can reach
 * as small as it can possibly be: there is no surface here to get a permission check wrong on.
 *
 * ### The one honest limitation
 *
 * `ACTION_SET_ALARM` names an hour and a minute. SunApp has no such thing: every alarm here is
 * anchored to the sun and therefore moves. The request is not refused and it is not quietly
 * turned into something else either. It opens the rule editor carrying the requested wall
 * time, where a single button sets the offset that lands on that time **today** and the
 * thirty-day preview immediately shows what it becomes afterwards. `EXTRA_SKIP_UI` is
 * therefore not honoured, deliberately: the platform documentation asks apps to skip the UI
 * only when they can complete the request without it, and completing this one silently would
 * mean inventing an anchor on the user's behalf.
 */
class AlarmIntentActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        // Before any frame is drawn. With a translucent theme in the manifest this activity
        // is never visible at all; the user sees only whatever it launched.
        finish()
    }

    private fun handle(request: Intent?) {
        when (request?.action) {
            AlarmClock.ACTION_SET_ALARM -> {
                AlarmIntentHandoff.write(this, seedFrom(request))
                openApp()
            }

            AlarmClock.ACTION_SHOW_ALARMS -> {
                AlarmIntentHandoff.write(
                    this,
                    AlarmIntentSeed(openEditor = false, label = null, requestedTime = null, vibrate = null),
                )
                openApp()
            }

            // Dismiss and snooze act on an alarm that is ringing right now, which is the
            // runtime's business and not the UI's. Handing them straight to the same receiver
            // the notification's own buttons use keeps this activity ignorant of the service,
            // the wake lock and the audio, and means an Assistant "snooze" and a tap on the
            // notification take literally the same code path.
            AlarmClock.ACTION_DISMISS_ALARM -> sendToRuntime(AlarmFireReceiver.ACTION_DISMISS)

            AlarmClock.ACTION_SNOOZE_ALARM -> sendToRuntime(AlarmFireReceiver.ACTION_SNOOZE)

            // An unrecognised or absent action is not an error worth reporting to whoever
            // sent it. Opening the app is the least surprising thing that can happen.
            else -> openApp()
        }
    }

    private fun seedFrom(request: Intent): AlarmIntentSeed {
        val hour = request.getIntExtra(AlarmClock.EXTRA_HOUR, -1)
        val minute = request.getIntExtra(AlarmClock.EXTRA_MINUTES, 0)
        val time = if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
        return AlarmIntentSeed(
            openEditor = true,
            label = request.getStringExtra(AlarmClock.EXTRA_MESSAGE),
            requestedTime = time,
            // Absent means "the app decides", which for SunApp means its own default rather
            // than false. Reading it with a false default would silently switch vibration off
            // for every alarm created through this route.
            vibrate = if (request.hasExtra(AlarmClock.EXTRA_VIBRATE)) {
                request.getBooleanExtra(AlarmClock.EXTRA_VIBRATE, true)
            } else {
                null
            },
        )
    }

    /**
     * Forward a dismiss or snooze to the alarm runtime.
     *
     * The rule id is not in the incoming intent and could not be: the sender has no idea what
     * SunApp's rules are called. It comes instead from the service's own record of what is
     * ringing, which is the only correct answer to "the alarm", and is null when nothing is.
     * A dismiss with no id still stops the noise; a snooze with no id has nothing to re-arm,
     * which the receiver logs rather than guesses at.
     *
     * `EXTRA_ALARM_SEARCH_MODE` and `EXTRA_ALARM_SNOOZE_DURATION` are read by neither side.
     * SunApp rings at most one alarm at a time, so there is nothing to search among, and the
     * snooze length is a fixed nine minutes; silently accepting a duration the runtime would
     * ignore would be worse than not offering it.
     */
    private fun sendToRuntime(action: String) {
        val ringing = AlarmService.ringing.value
        val broadcast = Intent(this, AlarmFireReceiver::class.java)
            .setAction(action)
            .putExtra(AlarmFireReceiver.EXTRA_RULE_ID, ringing?.ruleId)
            .putExtra(AlarmFireReceiver.EXTRA_LABEL, ringing?.label ?: AlarmFireReceiver.DEFAULT_LABEL)
        sendBroadcast(broadcast)
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                // CLEAR_TOP so an app already sitting in the background comes forward on the
                // alarms tab rather than wherever the user left it.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }
}

/**
 * What an `AlarmClock` intent asked for, in the app's own vocabulary.
 *
 * @property openEditor true for `ACTION_SET_ALARM`, false for `ACTION_SHOW_ALARMS`, which only
 *   asks for the list.
 * @property requestedTime the wall time the sender wanted, or null when it did not name one.
 *   SunApp cannot ring at a fixed time; see the class KDoc of [AlarmIntentActivity] for what
 *   the editor does with this instead.
 * @property vibrate null when the sender expressed no preference, which is different from
 *   asking for it to be off.
 */
data class AlarmIntentSeed(
    val openEditor: Boolean,
    val label: String?,
    val requestedTime: LocalTime?,
    val vibrate: Boolean?,
)

/**
 * A one-shot letterbox between [AlarmIntentActivity] and the Compose UI.
 *
 * ### Why not simply put the extras on the launch intent
 *
 * Because reading them back reliably would mean owning `MainActivity`'s launch mode and its
 * `onNewIntent`. An app whose task is already running receives a second intent through a
 * callback that a Compose tree never sees, and the stale `Activity.intent` it does see is the
 * one from last time. Two `SharedPreferences` writes sidestep that entirely and work the same
 * whether the app was cold, backgrounded or already on screen.
 *
 * ### Why it expires
 *
 * A seed is a request made by a person a moment ago. If the app is opened without the alarms
 * tab ever being reached, an unconsumed seed would otherwise sit on disk and spring the
 * editor open days later with a time nobody remembers asking for. [MAX_AGE_MILLIS] keeps a
 * handoff to the launch it belongs to.
 */
object AlarmIntentHandoff {

    /** Its own preference file, so clearing it can never disturb settings or rules. */
    const val PREFS_NAME: String = "sun_app_intent"

    /** A minute is long enough for a cold start on a slow device and short enough to be one
     *  launch rather than one session. */
    const val MAX_AGE_MILLIS: Long = 60_000L

    fun write(context: Context, seed: AlarmIntentSeed) {
        prefs(context).edit {
            putBoolean(KEY_OPEN_EDITOR, seed.openEditor)
            putString(KEY_LABEL, seed.label)
            putInt(KEY_HOUR, seed.requestedTime?.hour ?: -1)
            putInt(KEY_MINUTE, seed.requestedTime?.minute ?: -1)
            putInt(KEY_VIBRATE, seed.vibrate?.let { if (it) 1 else 0 } ?: -1)
            putLong(KEY_WRITTEN_AT, System.currentTimeMillis())
        }
    }

    /** True when a live handoff is waiting. Used to decide which tab opens first. */
    fun pending(context: Context): Boolean = read(context) != null

    /** Read and clear. Calling twice returns null the second time, by design. */
    fun consume(context: Context): AlarmIntentSeed? {
        val seed = read(context)
        prefs(context).edit { clear() }
        return seed
    }

    private fun read(context: Context): AlarmIntentSeed? {
        val store = prefs(context)
        val writtenAt = store.getLong(KEY_WRITTEN_AT, 0L)
        if (writtenAt == 0L) return null

        // Absolute difference, not a simple subtraction. The wall clock can move backwards
        // (a manual change, an NTP correction, a timezone-less device catching up at boot)
        // and a negative age must expire the seed rather than make it immortal.
        val age = System.currentTimeMillis() - writtenAt
        if (age > MAX_AGE_MILLIS || age < -MAX_AGE_MILLIS) return null

        val hour = store.getInt(KEY_HOUR, -1)
        val minute = store.getInt(KEY_MINUTE, -1)
        val vibrate = store.getInt(KEY_VIBRATE, -1)
        return AlarmIntentSeed(
            openEditor = store.getBoolean(KEY_OPEN_EDITOR, false),
            label = store.getString(KEY_LABEL, null),
            requestedTime = if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null,
            vibrate = if (vibrate < 0) null else vibrate == 1,
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val KEY_OPEN_EDITOR = "open_editor"
    private const val KEY_LABEL = "label"
    private const val KEY_HOUR = "hour"
    private const val KEY_MINUTE = "minute"
    private const val KEY_VIBRATE = "vibrate"
    private const val KEY_WRITTEN_AT = "written_at"
}
