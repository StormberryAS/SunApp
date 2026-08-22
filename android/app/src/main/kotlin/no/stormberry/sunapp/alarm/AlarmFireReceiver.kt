package no.stormberry.sunapp.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import no.stormberry.sunapp.alarm.model.Occurrence
import java.time.Instant
import java.time.ZoneId

/**
 * Receives the alarm from `AlarmManager` and the Snooze and Dismiss taps from the
 * notification, and is the one component that must never throw.
 *
 * The order of work in [onAlarmFired] is the load-bearing part. The next occurrence is armed
 * **before** anything that could fail, because everything after it (reading the rule, starting
 * a service, playing audio) has a plausible failure mode, and an alarm clock that stops
 * scheduling because one morning went wrong is worse than one that misses a single ring. Any
 * other order means a single bad day silently ends the alarm.
 *
 * Snooze and Dismiss arrive here as broadcasts rather than as service or activity intents
 * because notification trampolines are blocked from Android 12: an action that starts a
 * service which then starts an activity is dropped by the platform. A broadcast to an
 * unexported receiver in our own process has none of that ambiguity.
 */
class AlarmFireReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ALARM_FIRED -> onAlarmFired(context, intent)
            ACTION_SNOOZE -> onSnooze(context, intent)
            ACTION_DISMISS -> onDismiss(context, intent)
            else -> Log.w(TAG, "ignoring unexpected action ${intent.action}")
        }
    }

    private fun onAlarmFired(context: Context, intent: Intent) {
        // The system's own wake lock ends when onReceive returns, which can easily be before
        // the service has started its audio. Ours bridges that gap and is handed to the
        // service, which releases it once it holds one of its own.
        AlarmWakeLock.acquire(context)
        var handedOff = false
        try {
            // First, and inside its own catch: the schedule must survive whatever happens
            // next. Re-planning everything rather than just this rule keeps the one code path
            // that arms alarms, and is idempotent because request codes are stable.
            try {
                AlarmScheduling.replan(context)
            } catch (e: RuntimeException) {
                Log.e(TAG, "re-planning after a fire failed", e)
            }

            val targetMillis = intent.getLongExtra(EXTRA_FIRE_AT, 0L)
            val now = System.currentTimeMillis()
            if (targetMillis > 0L && now < targetMillis - CLOCK_JUMP_TOLERANCE_MS) {
                // The alarm arrived materially early, which means the wall clock moved rather
                // than time passing: a manual change, a network time correction, or a device
                // whose RTC drifted while it was off. Ringing now would wake someone in the
                // middle of the night. The re-plan above has already armed the correct
                // instant against the corrected clock, so the right move is to say nothing.
                Log.w(TAG, "alarm arrived ${targetMillis - now} ms early, treating it as a clock jump")
                return
            }

            val ruleId = intent.getStringExtra(EXTRA_RULE_ID)
            // A rule that cannot be found still rings, with a generic label. It can be absent
            // for two reasons: the device is locked and the mirror is thin, or the rule was
            // deleted between arming and firing. Staying silent would be right for the second
            // and catastrophic for the first, and only one of the two makes a person late.
            val rule = ruleId?.let {
                try {
                    AlarmScheduling.ruleFor(context, it)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "could not read rule $it while firing", e)
                    null
                }
            }
            if (rule == null) Log.w(TAG, "firing request code without a readable rule: $ruleId")

            ContextCompat.startForegroundService(
                context,
                AlarmService.ringIntent(
                    context = context,
                    ruleId = ruleId,
                    rule = rule,
                    fireAtMillis = if (targetMillis > 0L) targetMillis else now,
                    usedFallback = intent.getBooleanExtra(EXTRA_USED_FALLBACK, false),
                    isSnooze = intent.getIntExtra(EXTRA_REQUEST_CODE, 0) and 1 == 1,
                ),
            )
            handedOff = true
        } catch (e: RuntimeException) {
            // Includes ForegroundServiceStartNotAllowedException on Android 12 and later. It
            // should be unreachable, because an alarm-clock broadcast puts the app on the
            // temporary power allowlist, but an unreachable crash in a broadcast receiver is
            // still a crash the user sees at six in the morning.
            Log.e(TAG, "could not start the ringing service", e)
        } finally {
            if (!handedOff) AlarmWakeLock.release()
        }
    }

    private fun onSnooze(context: Context, intent: Intent) {
        stopRinging(context)
        val ruleId = intent.getStringExtra(EXTRA_RULE_ID)
        if (ruleId == null) {
            Log.w(TAG, "snooze without a rule id, nothing to re-arm")
            return
        }
        val label = intent.getStringExtra(EXTRA_LABEL) ?: DEFAULT_LABEL
        val fireAt = Instant.now().plusSeconds(SNOOZE_MINUTES * 60L)
        // A synthetic occurrence: a snooze has no solar anchor, so anchorAt is the fire time
        // itself and the two fallback flags are false. It is built here rather than in the
        // sink so that AndroidAlarmSink keeps its single, dumb arming path, and it uses the
        // odd sibling request code so tomorrow's alarm for the same rule stays armed.
        val occurrence = Occurrence(
            ruleId = ruleId,
            // atZone().toLocalDate() rather than LocalDate.ofInstant, which is a Java 9
            // addition and therefore not something to bet a 06:00 alarm on under library
            // desugaring on API 24.
            anchorDate = fireAt.atZone(ZoneId.systemDefault()).toLocalDate(),
            anchorAt = fireAt,
            fireAt = fireAt,
            usedFallback = false,
            clamped = false,
        )
        AndroidAlarmSink(context).arm(
            PlannedAlarm(occurrence, AlarmPlanner.snoozeRequestCodeFor(ruleId)),
        )
        AlarmService.postSnoozedNotice(context, label, fireAt.toEpochMilli())
    }

    private fun onDismiss(context: Context, intent: Intent) {
        stopRinging(context)
        val ruleId = intent.getStringExtra(EXTRA_RULE_ID) ?: return
        // Dismiss means dismiss. If the user snoozed and then changed their mind during the
        // second ring, the pending snooze must go with it.
        AndroidAlarmSink(context).cancel(AlarmPlanner.snoozeRequestCodeFor(ruleId))
    }

    /**
     * Stop the service outright rather than sending it a command.
     *
     * `stopService` is permitted from the background on every Android version, while starting
     * one is not, and the service's `onDestroy` already releases the audio, the vibrator, the
     * wake lock and the notification. Routing Dismiss through a start command would add a
     * background-start restriction to the one path that absolutely has to work.
     */
    private fun stopRinging(context: Context) {
        try {
            context.stopService(Intent(context, AlarmService::class.java))
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not stop the ringing service", e)
        }
    }

    companion object {
        private const val TAG = "SunAlarmFire"

        /** Sent by `AlarmManager` when a planned alarm comes due. */
        const val ACTION_ALARM_FIRED: String = "no.stormberry.sunapp.action.ALARM_FIRED"

        /** Sent by the Snooze control in the notification and on the ring screen. */
        const val ACTION_SNOOZE: String = "no.stormberry.sunapp.action.SNOOZE"

        /** Sent by the Dismiss control in the notification and on the ring screen. */
        const val ACTION_DISMISS: String = "no.stormberry.sunapp.action.DISMISS"

        const val EXTRA_RULE_ID: String = "no.stormberry.sunapp.extra.RULE_ID"
        const val EXTRA_FIRE_AT: String = "no.stormberry.sunapp.extra.FIRE_AT"
        const val EXTRA_USED_FALLBACK: String = "no.stormberry.sunapp.extra.USED_FALLBACK"
        const val EXTRA_REQUEST_CODE: String = "no.stormberry.sunapp.extra.REQUEST_CODE"
        const val EXTRA_LABEL: String = "no.stormberry.sunapp.extra.LABEL"

        /**
         * How long a snooze lasts. Nine minutes because that is what every alarm clock has
         * done since mechanical snooze bars, and a user's expectation about their alarm is
         * not the place to be original.
         */
        const val SNOOZE_MINUTES: Int = 9

        /** Shown when the rule behind a ringing alarm cannot be read. */
        const val DEFAULT_LABEL: String = "Solar alarm"

        /**
         * How early an alarm may arrive before it is read as a clock jump rather than as the
         * alarm coming due. Two seconds absorbs ordinary scheduling slop without absorbing a
         * real time change, which is never that small.
         */
        private const val CLOCK_JUMP_TOLERANCE_MS = 2_000L

        /**
         * The intent `AlarmManager` will deliver, and the same shape [AndroidAlarmSink.cancel]
         * rebuilds from a request code alone.
         *
         * The per-code `data` URI is what makes that rebuild sound. `PendingIntent` matching
         * compares action, data, type, class and categories and ignores extras entirely, so
         * without it every alarm in the app would share one identity and cancelling any rule
         * would cancel all of them.
         */
        fun fireIntent(
            context: Context,
            requestCode: Int,
            ruleId: String?,
            fireAtMillis: Long,
            usedFallback: Boolean,
        ): Intent = Intent(context, AlarmFireReceiver::class.java)
            .setAction(ACTION_ALARM_FIRED)
            .setData("sunapp://alarm/$requestCode".toUri())
            .putExtra(EXTRA_REQUEST_CODE, requestCode)
            .putExtra(EXTRA_RULE_ID, ruleId)
            .putExtra(EXTRA_FIRE_AT, fireAtMillis)
            .putExtra(EXTRA_USED_FALLBACK, usedFallback)

        /** The Snooze control's intent. Explicit, so it can never leave the app. */
        fun snoozeIntent(context: Context, ruleId: String?, label: String): Intent =
            Intent(context, AlarmFireReceiver::class.java)
                .setAction(ACTION_SNOOZE)
                .setData("sunapp://snooze/${ruleId.orEmpty()}".toUri())
                .putExtra(EXTRA_RULE_ID, ruleId)
                .putExtra(EXTRA_LABEL, label)

        /** The Dismiss control's intent. Explicit, so it can never leave the app. */
        fun dismissIntent(context: Context, ruleId: String?): Intent =
            Intent(context, AlarmFireReceiver::class.java)
                .setAction(ACTION_DISMISS)
                .setData("sunapp://dismiss/${ruleId.orEmpty()}".toUri())
                .putExtra(EXTRA_RULE_ID, ruleId)
    }
}

/**
 * The wake lock that carries an alarm from the broadcast into the ringing service.
 *
 * Process-wide and not reference counted, so the handover is a single object with a single
 * owner at a time: the receiver takes it, the service releases it as soon as it holds its
 * own. The timeout is the safety net for the case the service never arrives at all, because
 * a partial wake lock leaked by an alarm clock is a battery complaint the user will blame on
 * the alarm they did not get.
 */
internal object AlarmWakeLock {

    private const val TAG = "SunAlarmWakeLock"

    /**
     * Deliberately generous next to the ten seconds a receiver gets: on a cold device the
     * service still has to create a channel, resolve a ringtone and prepare a MediaPlayer.
     * Nothing is expected to hold it this long.
     */
    private const val TIMEOUT_MS = 60_000L

    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context) {
        if (wakeLock?.isHeld == true) return
        val power = context.applicationContext.getSystemService(PowerManager::class.java)
        if (power == null) {
            Log.w(TAG, "no PowerManager, ringing without a wake lock")
            return
        }
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SunApp:alarm").apply {
            setReferenceCounted(false)
            acquire(TIMEOUT_MS)
        }
    }

    @Synchronized
    fun release() {
        val held = wakeLock ?: return
        wakeLock = null
        if (held.isHeld) {
            try {
                held.release()
            } catch (e: RuntimeException) {
                Log.w(TAG, "wake lock was already released", e)
            }
        }
    }
}
