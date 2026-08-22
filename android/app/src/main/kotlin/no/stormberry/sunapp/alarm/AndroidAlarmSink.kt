package no.stormberry.sunapp.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.core.os.UserManagerCompat
import no.stormberry.sunapp.MainActivity
import no.stormberry.sunapp.alarm.model.AlarmRule
import no.stormberry.sunapp.data.AlarmStore
import no.stormberry.sunapp.data.SharedPreferencesRuleFile
import java.time.Instant

/**
 * The only [AlarmSink] that talks to Android, and the only place in the app that calls
 * `AlarmManager`.
 *
 * `setAlarmClock` is used for every user alarm and nothing else is. It is the sole alarm type
 * exempt from Doze deferral and from standby bucket throttling, it puts the app on the
 * temporary power allowlist when it fires (which is what makes starting the ringing
 * foreground service legal from the background on Android 12 and later), and it is the only
 * call that populates the system's next-alarm surface, so the clock on the lock screen agrees
 * with what SunApp thinks it is going to do. `setExactAndAllowWhileIdle` has none of those
 * three properties and is not a substitute.
 *
 * Everything about *when* to ring was decided above this class by `OccurrenceEngine` and
 * [AlarmPlanner] against a fixed clock. This class contains no judgement about time on
 * purpose: it converts a [PlannedAlarm] into a pending broadcast and back, and records what
 * the platform refused.
 *
 * A sink instance is cheap and stateless apart from that record. Build one per operation
 * rather than holding it: the record is about the operation you just performed.
 */
class AndroidAlarmSink(context: Context) : AlarmSink {

    private val appContext = context.applicationContext
    private val alarmManager: AlarmManager? = appContext.getSystemService(AlarmManager::class.java)

    private val inexact = LinkedHashSet<Int>()
    private val failed = LinkedHashSet<Int>()

    /**
     * Request codes that were armed, but only approximately, because the exact-alarm grant
     * was refused at the moment of the call.
     *
     * These alarms will ring. They may be minutes late, and the alarm row is expected to say
     * so rather than presenting them as normal.
     */
    val inexactRequestCodes: Set<Int> get() = inexact

    /** Request codes that could not be armed at all. These will not ring. */
    val failedRequestCodes: Set<Int> get() = failed

    /**
     * The capability gaps this sink actually ran into, as opposed to the ones a pre-flight
     * check predicts.
     *
     * `AlarmCapability.gaps()` asks the platform what it would allow; this reports what it
     * did allow, which is the stronger statement and the one worth showing after a boot
     * receiver has re-armed everything unattended.
     */
    fun observedGaps(): List<AlarmCapabilityGap> =
        if (inexact.isNotEmpty()) listOf(AlarmCapabilityGap.EXACT_ALARMS) else emptyList()

    /**
     * Arm [planned], replacing whatever is registered under the same request code.
     *
     * The `SecurityException` catch is not defensive padding. `canScheduleExactAlarms()` can
     * return true and the grant can be gone by the next line: the user can revoke it from
     * Settings at any moment, and a device-policy change can revoke it with no user action at
     * all. This runs inside a boot receiver, where an escaping exception would abandon every
     * *other* alarm in the list, so the failure is contained per alarm and downgraded to an
     * inexact wakeup rather than propagated.
     */
    override fun arm(planned: PlannedAlarm) {
        val manager = alarmManager
        val code = planned.requestCode
        if (manager == null) {
            failed += code
            return
        }
        val triggerAtMillis = planned.occurrence.fireAt.toEpochMilli()
        val operation = firePendingIntent(planned, PendingIntent.FLAG_UPDATE_CURRENT)
        if (operation == null) {
            failed += code
            return
        }
        try {
            manager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent(code)),
                operation,
            )
            inexact -= code
            failed -= code
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarm refused for request code $code, falling back to inexact", e)
            lastExactAlarmDenialMillis = System.currentTimeMillis()
            armInexact(manager, triggerAtMillis, operation, code)
        } catch (e: RuntimeException) {
            // Deliberately broad, and only here. Some vendors impose undocumented limits on
            // the number of pending alarms and signal them with whatever exception they feel
            // like. Losing one alarm is bad; losing the boot re-arm for all of them because
            // the receiver crashed is worse.
            Log.e(TAG, "could not arm request code $code", e)
            failed += code
        }
    }

    /**
     * Cancel whatever is registered under [requestCode].
     *
     * Rebuilding the pending intent from the request code alone works because the fire intent
     * carries a per-code `data` URI, and `PendingIntent` matching uses action, data, type,
     * class and categories while ignoring extras. Without that URI two different rules could
     * match each other's pending intent whenever their request codes were reused, which is
     * exactly the orphaned-alarm failure the request-code scheme exists to prevent.
     *
     * `FLAG_NO_CREATE` means a code that was never armed resolves to null and this is a
     * no-op, as [AlarmSink] requires.
     */
    override fun cancel(requestCode: Int) {
        val existing = firePendingIntent(
            requestCode = requestCode,
            ruleId = null,
            fireAtMillis = 0L,
            usedFallback = false,
            flags = PendingIntent.FLAG_NO_CREATE,
        ) ?: return
        try {
            alarmManager?.cancel(existing)
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not cancel request code $requestCode", e)
        }
        // Release the pending intent itself as well, so a cancelled rule leaves nothing
        // behind in the system's registry for a later code collision to inherit.
        existing.cancel()
    }

    private fun armInexact(
        manager: AlarmManager,
        triggerAtMillis: Long,
        operation: PendingIntent,
        code: Int,
    ) {
        try {
            // Needs no grant of any kind, and unlike a plain set() it still fires in Doze,
            // though the system may hold it back by several minutes. The alarm row must say
            // "approximate" while this is the state, which is what inexactRequestCodes is for.
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
            inexact += code
            failed -= code
        } catch (e: RuntimeException) {
            Log.e(TAG, "inexact fallback also refused for request code $code", e)
            failed += code
        }
    }

    private fun firePendingIntent(planned: PlannedAlarm, flags: Int): PendingIntent? =
        firePendingIntent(
            requestCode = planned.requestCode,
            ruleId = planned.occurrence.ruleId,
            fireAtMillis = planned.occurrence.fireAt.toEpochMilli(),
            usedFallback = planned.occurrence.usedFallback,
            flags = flags,
        )

    private fun firePendingIntent(
        requestCode: Int,
        ruleId: String?,
        fireAtMillis: Long,
        usedFallback: Boolean,
        flags: Int,
    ): PendingIntent? = PendingIntent.getBroadcast(
        appContext,
        requestCode,
        AlarmFireReceiver.fireIntent(appContext, requestCode, ruleId, fireAtMillis, usedFallback),
        flags or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Where the system sends the user when they tap the next-alarm chip in the status bar or
     * on the lock screen. Immutable, because nothing outside this app has any business
     * altering it.
     */
    private fun showIntent(requestCode: Int): PendingIntent = PendingIntent.getActivity(
        appContext,
        requestCode,
        Intent(appContext, MainActivity::class.java)
            .setAction(ACTION_SHOW_ALARMS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "SunAlarmSink"

        /**
         * Action on the show-intent, so the UI can open the alarm list rather than the sun
         * times when the user arrives from the system next-alarm chip.
         */
        const val ACTION_SHOW_ALARMS: String = "no.stormberry.sunapp.action.SHOW_ALARMS"

        /**
         * When an exact alarm was last refused, or 0 if never in this process.
         *
         * Process-wide because the discovery and the display happen in different components:
         * a boot receiver finds out, an activity minutes later has to show it. Not persisted,
         * because a stale warning about a permission the user has since restored is worse
         * than no warning; the value rebuilds itself the moment anything arms again.
         */
        @Volatile
        var lastExactAlarmDenialMillis: Long = 0L
            private set
    }
}

/**
 * Load the rules, work out what should be pending, and make `AlarmManager` agree.
 *
 * Every entry point into the alarm runtime funnels through here: boot, package replacement, a
 * time-zone change, a manual clock change, an exact-alarm permission change, and each alarm
 * as it fires. They all do the same thing, which is to throw away what was believed and
 * recompute from the stored rules, for the reasons set out in [AlarmPlanner]'s KDoc.
 *
 * Two pieces of state live here that the pure planner cannot own.
 *
 * **The armed set.** [AlarmPlanner.apply] needs to know what was armed last time so it can
 * cancel a rule that has since been deleted or disabled. That is genuinely persistent state
 * and it is kept in device-protected storage, in a preferences file owned by the alarm
 * runtime rather than by the rule store, because it describes the platform rather than the
 * user's data.
 *
 * **The rule mirror.** The rule file itself is credential-encrypted and unreadable between a
 * reboot and the first unlock, which is exactly when `LOCKED_BOOT_COMPLETED` needs it. A copy
 * is therefore kept in device-protected storage and refreshed on every re-plan performed
 * while unlocked. The mirror is a cache and never the source of truth: an unlocked device
 * always reads the real file.
 */
object AlarmScheduling {

    private const val TAG = "SunAlarmScheduling"
    private const val PREFS_NAME = "sun_app_alarm_runtime"
    private const val KEY_ARMED = "armed_request_codes"
    private const val KEY_RULES_MIRROR = "rules_mirror_json"

    /**
     * Recompute and re-arm everything. Safe to call from a broadcast receiver: it never
     * throws, and a rule it cannot use is skipped rather than aborting the rest.
     *
     * @return the alarms that are now pending, earliest first. Empty is a legitimate answer,
     *   both for a user with no rules and for a locked device that has never been unlocked
     *   since the mirror was introduced.
     */
    fun replan(context: Context): List<PlannedAlarm> {
        val rules = rules(context)
        // Refreshed here and nowhere else. A re-plan is the moment the app has just proved it
        // can read the real rule file, and it is rare (a boot, an edit, an alarm firing),
        // where a plain read happens several times during a single ring.
        if (UserManagerCompat.isUserUnlocked(context)) mirror(context, rules)
        val plan = AlarmPlanner.plan(rules, Instant.now())
        val sink = AndroidAlarmSink(context)
        val armed = AlarmPlanner.apply(plan, sink, previouslyArmed = armedRequestCodes(context))
        rememberArmed(context, armed)
        lastObservedGaps = sink.observedGaps()
        Log.i(TAG, "re-planned ${plan.size} alarm(s) from ${rules.size} rule(s)")
        return plan
    }

    /**
     * What the platform actually refused during the most recent re-plan, or empty if it
     * refused nothing.
     *
     * Kept because a pre-flight check and the arming call can disagree. `canScheduleExactAlarms()`
     * can answer true and `setAlarmClock` can still throw, either because the grant was
     * revoked in the microseconds between the two or because a vendor build has its own
     * opinion. In that state `AlarmCapability.gaps()` reports nothing wrong while the alarms
     * are quietly inexact, and this is the only evidence of it. A UI showing capability gaps
     * should show the union of the two.
     *
     * Process-wide and not persisted, for the same reason as
     * [AndroidAlarmSink.lastExactAlarmDenialMillis]: a stale warning about a permission the
     * user has since restored is worse than no warning, and every re-plan rewrites this.
     */
    @Volatile
    var lastObservedGaps: List<AlarmCapabilityGap> = emptyList()
        private set

    /**
     * The user's rules, from the real store when the device is unlocked and from the
     * device-protected mirror when it is not.
     *
     * Reading the credential-encrypted file before first unlock does not fail loudly, it
     * returns nothing, which would silently disarm every alarm on a device that rebooted
     * overnight. Hence the explicit unlocked check rather than a try/catch.
     */
    fun rules(context: Context): List<AlarmRule> {
        if (!UserManagerCompat.isUserUnlocked(context)) {
            val mirrored = runtimePrefs(context).getString(KEY_RULES_MIRROR, null)
            if (mirrored == null) Log.w(TAG, "locked device and no rule mirror yet")
            return AlarmStore.decode(mirrored)
        }
        return AlarmStore.load(SharedPreferencesRuleFile(context))
    }

    /** One rule by id, or null when it has been deleted or cannot be read yet. */
    fun ruleFor(context: Context, ruleId: String): AlarmRule? =
        rules(context).firstOrNull { it.id == ruleId }

    /** Request codes believed to be armed, from the previous re-plan. */
    fun armedRequestCodes(context: Context): Set<Int> =
        runtimePrefs(context).getStringSet(KEY_ARMED, emptySet())
            .orEmpty()
            .mapNotNullTo(LinkedHashSet()) { it.toIntOrNull() }

    private fun rememberArmed(context: Context, codes: Set<Int>) {
        runtimePrefs(context).edit {
            putStringSet(KEY_ARMED, codes.mapTo(LinkedHashSet()) { it.toString() })
        }
    }

    private fun mirror(context: Context, rules: List<AlarmRule>) {
        try {
            runtimePrefs(context).edit { putString(KEY_RULES_MIRROR, AlarmStore.encode(rules)) }
        } catch (e: RuntimeException) {
            // A failed mirror costs the next locked boot its alarms, which is bad, but not as
            // bad as failing the re-plan that is currently in progress.
            Log.w(TAG, "could not refresh the device-protected rule mirror", e)
        }
    }

    /**
     * Preferences in device-protected storage, which is the only storage readable between a
     * reboot and the first unlock. Available unconditionally because minSdk is 24, the
     * version Direct Boot arrived at, so there is no compatibility branch to get wrong.
     */
    private fun runtimePrefs(context: Context): SharedPreferences = context.applicationContext
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
