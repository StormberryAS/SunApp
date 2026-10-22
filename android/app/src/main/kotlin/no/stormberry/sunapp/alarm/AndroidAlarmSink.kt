package no.stormberry.sunapp.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import androidx.core.os.UserManagerCompat
import no.stormberry.sunapp.MainActivity
import no.stormberry.sunapp.alarm.model.AlarmRule
import no.stormberry.sunapp.data.AlarmStore
import no.stormberry.sunapp.data.SharedPreferencesRuleFile
import java.time.Instant
import java.time.ZoneId

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
    private val denied = LinkedHashSet<Int>()

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
        if (denied.isNotEmpty()) listOf(AlarmCapabilityGap.EXACT_ALARMS) else emptyList()

    /**
     * Arm [planned], replacing whatever is registered under the same request code.
     *
     * The `SecurityException` catch is not defensive padding. `canScheduleExactAlarms()` can
     * return true and the grant can be gone by the next line: the user can revoke it from
     * Settings at any moment, and a device-policy change can revoke it with no user action at
     * all. This runs inside a boot receiver, where an escaping exception would abandon every
     * *other* alarm in the list, so the failure is contained per alarm and downgraded to an
     * inexact wakeup rather than propagated.
     *
     * 1.1.4 (A-7): any failure of the exact call now falls back to the inexact one, not only a
     * refused grant. Some vendors impose undocumented limits on the number of pending alarms and
     * signal them with whatever exception they feel like; an inexact alarm is better than none.
     */
    override fun arm(planned: PlannedAlarm) {
        armWithResult(planned)
    }

    /** [arm], reporting what became of it, for the snooze path that must know (A-7). */
    fun armWithResult(planned: PlannedAlarm): ArmResult {
        val manager = alarmManager
        val code = planned.requestCode
        val operation = if (manager == null) null else firePendingIntent(planned, PendingIntent.FLAG_UPDATE_CURRENT)
        val result = if (manager == null || operation == null) {
            ArmResult.FAILED
        } else {
            val triggerAtMillis = planned.occurrence.fireAt.toEpochMilli()
            FireRules.armWithFallback(
                exact = {
                    manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent(code)), operation)
                },
                // Needs no grant of any kind, and unlike a plain set() it still fires in Doze,
                // though the system may hold it back. The alarm row says so while this is the
                // state, which is what inexactRequestCodes is for.
                inexact = { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation) },
                onExactFailure = { e ->
                    Log.w(TAG, "exact alarm refused for request code $code, falling back to inexact", e)
                    if (e is SecurityException) {
                        lastExactAlarmDenialMillis = System.currentTimeMillis()
                        denied += code
                    }
                },
            )
        }
        when (result) {
            ArmResult.EXACT -> {
                inexact -= code
                failed -= code
                denied -= code
            }
            ArmResult.INEXACT -> {
                inexact += code
                failed -= code
            }
            ArmResult.FAILED -> {
                Log.e(TAG, "could not arm request code $code at all")
                inexact -= code
                failed += code
            }
        }
        return result
    }

    /**
     * 1.1.4, A-2: the backstop for the nearest alarm, armed only on Android 12 and 12L (the
     * planner passes null elsewhere, which cancels it).
     *
     * Armed twice, each under its own address, because each kind of inexact alarm fails
     * differently there (EclipseTracker fix C, C-3). `setAndAllowWhileIdle` survives Doze but may
     * be delivered up to an hour after its time; `setWindow` with the shortest window Android 12
     * allows (ten minutes) arrives within it while the phone is awake or in light use, but not in
     * Doze. Revoking exact alarms cancels exact alarms only, so both survive it. Whichever
     * arrives first rings the alarm; the receiver then finds the other, and the exact one if it
     * still comes, already rung (FireRules.decide). Never throws.
     */
    override fun guard(next: PlannedAlarm?) {
        val manager = alarmManager ?: return
        for (slot in GuardSlot.entries) {
            try {
                if (next != null) {
                    val operation = PendingIntent.getBroadcast(
                        appContext,
                        slot.requestCode,
                        AlarmFireReceiver.guardIntent(
                            context = appContext,
                            path = slot.path,
                            requestCode = next.requestCode,
                            ruleId = next.occurrence.ruleId,
                            fireAtMillis = next.occurrence.fireAt.toEpochMilli(),
                            usedFallback = next.occurrence.usedFallback,
                        ),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    val at = next.occurrence.fireAt.toEpochMilli()
                    when (slot) {
                        GuardSlot.WHILE_IDLE -> manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
                        GuardSlot.WINDOW -> manager.setWindow(AlarmManager.RTC_WAKEUP, at, GUARD_WINDOW_MS, operation)
                    }
                } else {
                    PendingIntent.getBroadcast(
                        appContext,
                        slot.requestCode,
                        AlarmFireReceiver.guardIntent(appContext, slot.path, 0, null, 0L, false),
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                    )?.let { pending ->
                        manager.cancel(pending)
                        pending.cancel()
                    }
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not set the backstop ${slot.path}", e)
            }
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

    /** The two backstops: their codes, and their own data addresses, which keep them apart from each other and from every alarm. */
    private enum class GuardSlot(val requestCode: Int, val path: String) {
        WHILE_IDLE(0x5A30, "guard/idle"),
        WINDOW(0x5A32, "guard/window"),
    }

    companion object {
        private const val TAG = "SunAlarmSink"

        /** The shortest window Android 12 allows `setWindow` (shorter ones are lengthened to it). */
        private const val GUARD_WINDOW_MS = 10L * 60_000L

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
 * time-zone change, a manual clock change, an exact-alarm permission change, each alarm as it
 * fires, and (1.1.4, A-2) the app coming to the front. They all do the same thing, which is to
 * throw away what was believed and recompute from the stored rules, for the reasons set out in
 * [AlarmPlanner]'s KDoc. Since 1.1.4 they run under one lock, because the app's own re-plan runs
 * on a thread of its own and must not interleave with a fire or a boot.
 *
 * The state that lives here, all of it in device-protected storage, because the pure planner
 * cannot own it and because it must be readable between a reboot and the first unlock:
 *
 * **The armed set.** [AlarmPlanner.apply] needs to know what was armed last time so it can
 * cancel a rule that has since been deleted or disabled.
 *
 * **The rule mirror.** The rule file itself is credential-encrypted and unreadable between a
 * reboot and the first unlock, which is exactly when `LOCKED_BOOT_COMPLETED` needs it. A copy
 * is therefore kept here and refreshed on every re-plan performed while unlocked. The mirror is
 * a cache and never the source of truth: an unlocked device always reads the real file.
 *
 * **The last ring of each rule** (1.1.4). The receiver never rings, and the planner never
 * re-arms, an occurrence at or before it ([FireRules.decide], [FireRules.planAfter]).
 *
 * **The snoozes waiting to ring** (1.1.4, A-6). A re-plan arms them again (a restart cancels
 * every alarm), and cancels the snooze of a rule that was deleted or switched off.
 *
 * **The Missed notices owed** (1.1.4). A notice posted while an alarm rings would be grouped
 * with the ring notification and silence it, so a notice the re-plan owes then waits until
 * nothing rings ([tellOwed]).
 */
object AlarmScheduling {

    private const val TAG = "SunAlarmScheduling"
    private const val PREFS_NAME = "sun_app_alarm_runtime"
    private const val KEY_ARMED = "armed_request_codes"
    private const val KEY_RULES_MIRROR = "rules_mirror_json"
    private const val KEY_LAST_RUNG = "last_rung"
    private const val KEY_SNOOZES = "snoozes"
    private const val KEY_OWED = "owed_missed"
    private const val KEY_OWED_REPLACED = "owed_replaced"

    /** The rule id kept for a replaced alarm whose rule id is unknown (its notice is untagged by rule). */
    private const val UNKNOWN_RULE = "unknown"

    /** The most Missed notices kept waiting to be posted. */
    private const val MAX_OWED = 32

    private val lock = Any()

    /**
     * Recompute and re-arm everything. Safe to call from a broadcast receiver: it never
     * throws, and a rule it cannot use is skipped rather than aborting the rest.
     *
     * @return the alarms that are now pending, earliest first. Empty is a legitimate answer,
     *   both for a user with no rules and for a locked device that has never been unlocked
     *   since the mirror was introduced.
     * @param tell post the Missed notices owed, unless an alarm rings. False from the fire
     *   receiver when it is about to ring: a notice posted then would be taken away by the ring
     *   (EclipseTracker fix E, C-4), so it stays owed until nothing rings.
     * @param afterRestart true only for the re-plan after an event that wiped AlarmManager (a
     *   reboot: [FireRules.wipesAlarms]; an app update keeps the alarms, fix round 3). Only then
     *   is a snooze more than ten minutes overdue dropped with a Missed notice (decision D-5);
     *   any other re-plan keeps it and arms it again, because AlarmManager may still be about to
     *   deliver it (fix round 2).
     */
    fun replan(context: Context, tell: Boolean = true, afterRestart: Boolean = false): List<PlannedAlarm> =
        synchronized(lock) { replanLocked(context, tell, afterRestart) }

    private fun replanLocked(context: Context, tell: Boolean, afterRestart: Boolean = false): List<PlannedAlarm> {
        val unlocked = UserManagerCompat.isUserUnlocked(context)
        val prefs = runtimePrefs(context)
        val rules = rules(context)
        // Refreshed here and nowhere else. A re-plan is the moment the app has just proved it
        // can read the real rule file, and it is rare (a boot, an edit, an alarm firing),
        // where a plain read happens several times during a single ring.
        if (unlocked) mirror(context, rules)
        // Whether [rules] is the whole truth. On a locked device with no mirror it is not, and
        // nothing may be cancelled for a rule that only seems to be gone.
        val rulesKnown = unlocked || prefs.contains(KEY_RULES_MIRROR)
        val now = Instant.now()
        // A last ring far ahead of the clock (the clock was set back a long way since) no longer
        // counts and goes before planning (fix round 1), so the occurrence armed again below is not
        // taken for one that has rung when it arrives. A rule that no longer exists goes too.
        val stored = readRung(prefs)
        val lastRung = FireRules.pruneRung(stored, now.toEpochMilli())
            .let { if (rulesKnown) FireRules.pruneRung(it, rules.mapTo(HashSet()) { rule -> rule.id }) else it }
        if (lastRung.size != stored.size) {
            runCatching { prefs.edit(commit = true) { putString(KEY_LAST_RUNG, FireRules.encodeRung(lastRung)) } }
                .onFailure { Log.w(TAG, "could not prune the last-rung record", it) }
        }
        val plan = AlarmPlanner.plan(rules, now, lastRung)
        val sink = AndroidAlarmSink(context)
        // The snoozes first, so the Android 12 backstop can stand in for a snooze that comes
        // before the nearest alarm (fix round 1): revoking exact alarms cancels an exact snooze too.
        val snoozes = replanSnoozes(context, prefs, sink, rules, rulesKnown, now.toEpochMilli(), afterRestart)
        val armed = AlarmPlanner.apply(
            plan,
            sink,
            previouslyArmed = armedRequestCodes(context),
            backstop = FireRules.backstopWanted(Build.VERSION.SDK_INT),
            snoozes = snoozes.map { snoozePlanned(it) },
        )
        rememberArmed(context, armed)
        lastObservedGaps = sink.observedGaps()
        Log.i(TAG, "re-planned ${plan.size} alarm(s) from ${rules.size} rule(s)")
        if (tell) tellOwedLocked(context)
        return plan
    }

    /**
     * 1.1.4, A-6: arm the stored snoozes again, cancel those whose rule is gone or switched off,
     * and, after a restart only ([afterRestart], fix round 2), drop those that came due too long
     * ago with a Missed notice (decision D-5). Returns the snoozes still waiting, earliest first
     * (empty when they cannot be read). Never throws.
     */
    private fun replanSnoozes(
        context: Context,
        prefs: SharedPreferences,
        sink: AndroidAlarmSink,
        rules: List<AlarmRule>,
        rulesKnown: Boolean,
        now: Long,
        afterRestart: Boolean,
    ): List<PendingSnooze> {
        try {
            if (!prefs.contains(KEY_SNOOZES)) return emptyList()
            val stored = FireRules.decodeSnoozes(prefs.getString(KEY_SNOOZES, null))
            val live = if (rulesKnown) {
                rules.filter { it.enabled }.mapTo(HashSet()) { it.id }
            } else {
                // Locked with no mirror: every rule counts as live, so nothing is cancelled.
                stored.mapTo(HashSet()) { it.ruleId }
            }
            val sort = FireRules.sortSnoozes(stored, live, now, dropLate = afterRestart)
            AlarmPlanner.applySnoozes(sort, sink, ZoneId.systemDefault())
            for (gone in sort.gone) {
                AlarmService.cancelNotice(context, gone.ruleId)
                Log.i(TAG, "cancelled the snooze of a rule that was deleted or switched off")
            }
            for (late in sort.late) {
                AlarmService.cancelNotice(context, late.ruleId)
                oweLocked(prefs, late)
                Log.i(TAG, "a snooze came due ${now - late.at} ms before a restart and could not ring: missed")
            }
            if (sort.keep != stored) {
                prefs.edit(commit = true) { putString(KEY_SNOOZES, FireRules.encodeSnoozes(sort.keep)) }
            }
            return sort.keep
        } catch (e: Exception) {
            Log.e(TAG, "could not re-plan the snoozes", e)
            return emptyList()
        }
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

    // ------------------------------------------------------------------------------ rules

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

    // -------------------------------------------------------------------------- last ring

    /** The instant [ruleId] last rang, or null when it never did or the record cannot be read (it then rings). */
    fun lastRung(context: Context, ruleId: String): Long? = try {
        readRungOrThrow(runtimePrefs(context))[ruleId]
    } catch (e: Exception) {
        Log.w(TAG, "the last-rung record could not be read; ringing", e)
        null
    }

    /**
     * Note that [ruleId] rang its occurrence at [at], before the re-plan that follows, so the
     * re-plan arms the occurrence after it. Written with commit, because the next delivery of
     * the same alarm can arrive straight after this one. Never throws.
     */
    fun markRung(context: Context, ruleId: String, at: Long) = synchronized(lock) {
        try {
            val prefs = runtimePrefs(context)
            val next = FireRules.recordRung(readRung(prefs), ruleId, at)
            prefs.edit(commit = true) { putString(KEY_LAST_RUNG, FireRules.encodeRung(next)) }
        } catch (e: RuntimeException) {
            Log.e(TAG, "could not note the ring; a second delivery of it may ring again", e)
        }
    }

    /**
     * The last ring of every rule (empty when none or unreadable), for the alarm list, which
     * computes each rule's next ring as the planner does ([FireRules.planAfter], fix round 1), so
     * it never promises an occurrence that is not armed.
     */
    fun lastRungAll(context: Context): Map<String, Long> = readRung(runtimePrefs(context))

    private fun readRung(prefs: SharedPreferences): Map<String, Long> = try {
        readRungOrThrow(prefs)
    } catch (e: Exception) {
        Log.w(TAG, "the last-rung record could not be read", e)
        emptyMap()
    }

    private fun readRungOrThrow(prefs: SharedPreferences): Map<String, Long> =
        FireRules.decodeRung(prefs.getString(KEY_LAST_RUNG, null))

    // ---------------------------------------------------------------------------- snoozes

    /** The snoozes waiting now; empty when there are none or they cannot be read. */
    fun snoozes(context: Context): List<PendingSnooze> = synchronized(lock) {
        try {
            FireRules.decodeSnoozes(runtimePrefs(context).getString(KEY_SNOOZES, null))
        } catch (e: Exception) {
            Log.w(TAG, "the stored snoozes could not be read", e)
            emptyList()
        }
    }

    /**
     * Whether the snooze of [ruleId] due at [at] is still stored: true, false, or null when that
     * cannot be known (no snooze was ever stored by this version, or storage cannot be read), in
     * which case it rings.
     */
    fun snoozeStored(context: Context, ruleId: String, at: Long): Boolean? = synchronized(lock) {
        try {
            val prefs = runtimePrefs(context)
            if (!prefs.contains(KEY_SNOOZES)) return@synchronized null
            FireRules.decodeSnoozes(prefs.getString(KEY_SNOOZES, null)).any { it.ruleId == ruleId && it.at == at }
        } catch (e: Exception) {
            Log.w(TAG, "the stored snoozes could not be read; ringing", e)
            null
        }
    }

    /**
     * Store [snooze], then arm it, exactly if allowed, else inexactly (A-6, A-7). Stored first,
     * because a snooze that rang without being stored would be taken for a cancelled one; when
     * it cannot be stored, or cannot be armed at all, nothing is left behind and the result is
     * FAILED, so the caller keeps the alarm ringing. Never throws.
     */
    fun snooze(context: Context, snooze: PendingSnooze): ArmResult = synchronized(lock) {
        val prefs = runtimePrefs(context)
        val stored = try {
            val before = if (prefs.contains(KEY_SNOOZES)) FireRules.decodeSnoozes(prefs.getString(KEY_SNOOZES, null)) else emptyList()
            prefs.edit(commit = true) {
                putString(KEY_SNOOZES, FireRules.encodeSnoozes(FireRules.putSnooze(before, snooze)))
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "the snooze could not be stored", e)
            false
        }
        if (!stored) return@synchronized ArmResult.FAILED
        val result = try {
            AndroidAlarmSink(context).armWithResult(snoozePlanned(snooze))
        } catch (e: RuntimeException) {
            Log.e(TAG, "the snooze could not be armed", e)
            ArmResult.FAILED
        }
        if (result == ArmResult.FAILED) {
            forgetSnoozeLocked(prefs, snooze.ruleId)
        } else if (FireRules.backstopWanted(Build.VERSION.SDK_INT)) {
            // The backstop follows the nearest alarm or snooze (fix round 1), and this snooze is
            // usually the nearest now. A re-plan moves it; the ring is still on, so nothing is told.
            runCatching { replanLocked(context, tell = false) }.onFailure { Log.w(TAG, "could not move the backstop to the snooze", it) }
        }
        result
    }

    /** Forget the stored snooze of [ruleId] (it is ringing now, or it came due too late). Never throws. */
    fun forgetSnooze(context: Context, ruleId: String) = synchronized(lock) {
        forgetSnoozeLocked(runtimePrefs(context), ruleId)
    }

    /**
     * Cancel and forget the snooze of [ruleId] (Dismiss). On Android 12 and 12L the backstop may
     * stand in for that snooze, so a re-plan then moves it to the nearest alarm that is left (fix
     * round 2, verify 1 code review C-3); nothing is told, as in [snooze]. Never throws.
     */
    fun cancelSnooze(context: Context, ruleId: String) = synchronized(lock) {
        runCatching { AndroidAlarmSink(context).cancel(AlarmPlanner.snoozeRequestCodeFor(ruleId)) }
            .onFailure { Log.w(TAG, "could not cancel a snooze", it) }
        forgetSnoozeLocked(runtimePrefs(context), ruleId)
        if (FireRules.backstopWanted(Build.VERSION.SDK_INT)) {
            runCatching { replanLocked(context, tell = false) }.onFailure { Log.w(TAG, "could not move the backstop off the snooze", it) }
        }
    }

    private fun forgetSnoozeLocked(prefs: SharedPreferences, ruleId: String) {
        try {
            if (!prefs.contains(KEY_SNOOZES)) return
            val before = FireRules.decodeSnoozes(prefs.getString(KEY_SNOOZES, null))
            val after = FireRules.removeSnooze(before, ruleId)
            if (after.size != before.size) prefs.edit(commit = true) { putString(KEY_SNOOZES, FireRules.encodeSnoozes(after)) }
        } catch (e: Exception) {
            Log.w(TAG, "could not forget a snooze", e)
        }
    }

    /** A snooze as an alarm to arm, in the phone's zone ([AlarmPlanner.snoozePlanned]). */
    fun snoozePlanned(snooze: PendingSnooze): PlannedAlarm = AlarmPlanner.snoozePlanned(snooze, ZoneId.systemDefault())

    // ------------------------------------------------------------------------ owed notices

    private fun oweLocked(prefs: SharedPreferences, snooze: PendingSnooze, key: String = KEY_OWED) {
        try {
            val owed = FireRules.putSnooze(readOwed(prefs, key), snooze).takeLast(MAX_OWED)
            prefs.edit(commit = true) { putString(key, FireRules.encodeSnoozes(owed)) }
        } catch (e: Exception) {
            Log.e(TAG, "could not keep a Missed notice owed", e)
        }
    }

    /**
     * Owe the "no answer until the next alarm began" notice for [alarm] (A-5), told once nothing
     * rings. Kept in storage rather than in the ringing service (fix round 1), so the notice is
     * not lost with the service or the process, whichever path ends the ring. Never throws.
     */
    fun oweReplaced(context: Context, alarm: RingingAlarm) = synchronized(lock) {
        val entry = PendingSnooze(
            ruleId = alarm.ruleId?.takeIf { it.isNotEmpty() } ?: UNKNOWN_RULE,
            at = alarm.fireAtMillis.coerceAtLeast(1L),
            label = alarm.label.take(PendingSnooze.MAX_LABEL),
        )
        oweLocked(runtimePrefs(context), entry, KEY_OWED_REPLACED)
    }

    /**
     * Post every Missed notice still owed, then forget them, unless an alarm rings: a notice
     * posted then would be grouped with the ring notification and silence it (the 1.1.3 rule),
     * so they wait until nothing rings. Never throws.
     */
    fun tellOwed(context: Context) = synchronized(lock) { tellOwedLocked(context) }

    private fun tellOwedLocked(context: Context) {
        try {
            if (AlarmService.current() != null) return
            val prefs = runtimePrefs(context)
            val owed = readOwed(prefs, KEY_OWED)
            val replaced = readOwed(prefs, KEY_OWED_REPLACED)
            if (owed.isEmpty() && replaced.isEmpty()) return
            owed.forEach { AlarmService.postNotRungNotice(context, it.ruleId, it.label) }
            replaced.forEach { AlarmService.postReplacedNotice(context, it.ruleId.takeIf { id -> id != UNKNOWN_RULE }, it.label, it.at) }
            prefs.edit(commit = true) {
                remove(KEY_OWED)
                remove(KEY_OWED_REPLACED)
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "could not post the owed Missed notices; they stay owed", e)
        }
    }

    private fun readOwed(prefs: SharedPreferences, key: String): List<PendingSnooze> = try {
        FireRules.decodeSnoozes(prefs.getString(key, null))
    } catch (e: Exception) {
        Log.w(TAG, "the owed Missed notices could not be read", e)
        emptyList()
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
