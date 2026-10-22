package no.stormberry.sunapp.alarm

import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * The decisions the alarm runtime takes when an alarm arrives, a snooze is pressed or a re-plan
 * runs, kept pure so the JVM tests can replay each case (1.1.4, ported from EclipseTracker's fix
 * B as it shipped at 5a38858, including fix rounds C to E). The receiver, the scheduler and the
 * service only carry them out.
 *
 * SunApp differs from EclipseTracker in one structural way: it keeps no per-alarm record. Each
 * rule has one armed occurrence under a stable even request code and its snooze on the odd
 * sibling. So where EclipseTracker deletes a record once it has rung, SunApp remembers the last
 * instant each rule rang ([FireRules.planAfter]) and never rings, or re-arms, an occurrence at or
 * before it. That is what keeps the Android 12 backstop from ringing an alarm twice, and it also
 * closes the double ring after a small backwards clock correction and after an arrival under two
 * seconds early.
 *
 * Fix round 1 (verify 0) bounds that record, because a ring at the wrong real time must not use up
 * the real occurrence: a ring is recorded only when it arrived on time ([shouldRecordRing]), and a
 * record more than [FireRules.LAST_RUNG_HORIZON_MS] ahead of the clock (the clock was set back a long
 * way since) no longer counts and is pruned ([rungCounts]). When in doubt, ring.
 */

/** What the fire receiver does with an alarm that has just arrived. */
sealed interface FireDecision {
    /** It rings. */
    data object Ring : FireDecision

    /** It arrived more than [FireRules.CLOCK_JUMP_TOLERANCE_MS] early: the clock moved, not time. */
    data object Early : FireDecision

    /**
     * Its occurrence has rung already: the same alarm arriving a second time, through the Android
     * 12 backstop or a re-arm after the clock was set back. Nothing rings.
     */
    data object AlreadyRung : FireDecision

    /** A snooze that is no longer stored: cancelled, or rung already. Nothing rings. */
    data object Gone : FireDecision
}

/** What became of an attempt to arm an alarm: exactly, inexactly after the exact call failed, or not at all. */
enum class ArmResult { EXACT, INEXACT, FAILED }

/**
 * A snooze waiting to ring (1.1.4, A-6): the rule it belongs to, the instant it rings and the name
 * it rings with. Kept in device-protected storage so a restart re-arms it, and so deleting or
 * switching off its rule cancels it.
 */
data class PendingSnooze(val ruleId: String, val at: Long, val label: String) {

    fun toJson(): JSONObject = JSONObject().put("v", 1).put("rule", ruleId).put("at", at).put("label", label)

    companion object {
        /** The longest name kept; a rule's label is far shorter in practice. */
        const val MAX_LABEL = 200

        /** The snooze in [raw], or null unless it is exactly one this version writes. */
        fun parse(raw: Any?): PendingSnooze? {
            if (raw !is JSONObject || raw.length() != 4) return null
            if (raw.opt("v") != 1) return null
            val rule = raw.opt("rule") as? String ?: return null
            if (rule.isEmpty()) return null
            val at = (raw.opt("at") as? Number)?.toLong() ?: return null
            if (at <= 0L) return null
            val label = raw.opt("label") as? String ?: return null
            if (label.length > MAX_LABEL) return null
            return PendingSnooze(rule, at, label)
        }
    }
}

/** How a re-plan sorts the stored snoozes (A-6). */
data class SnoozeSort(
    /** Still to ring: armed again, newest per rule. */
    val keep: List<PendingSnooze>,
    /** Came due more than the late limit ago, while the phone could not ring them: a Missed notice each. */
    val late: List<PendingSnooze>,
    /** Their rule was deleted or switched off: cancelled, with no notice. */
    val gone: List<PendingSnooze>,
)

object FireRules {

    /** An alarm arriving more than this early means the clock moved (as in 1.1.3). */
    const val CLOCK_JUMP_TOLERANCE_MS: Long = 2_000L

    /**
     * How overdue a stored snooze may be when the re-plan after a restart finds it and still be
     * armed again (decision D-5). A snooze that came due while the phone was off or restarting
     * rings when the phone comes back if it is at most this late, and is otherwise dropped with a
     * Missed notice ([sortSnoozes] with dropLate). Only that re-plan applies the limit (fix round
     * 2): a restart is what wipes AlarmManager, so only then is an overdue snooze known to be lost.
     * Any other re-plan keeps an overdue snooze and arms it again, and a snooze that AlarmManager
     * delivers itself rings however late it arrives ([decideSnooze], fix round 1), as an alarm's
     * own occurrence does: on Android 12 with exact alarms off every snooze is inexact and can
     * legitimately be late.
     */
    const val SNOOZE_LATE_LIMIT_MS: Long = 10L * 60_000L

    /**
     * How far ahead of the clock a rule's last ring may lie and still count (fix round 1). Further
     * ahead means the clock was set back a long way after the ring, and then nobody can tell
     * whether the ring or the clock now is wrong: the record is dropped and the occurrence rings
     * again, as in 1.1.3, rather than being skipped. An hour still covers what the record is for:
     * an arrival up to two seconds early, a small clock correction and the backstop's second
     * delivery (which comes after the ring, so the record then lies behind the clock).
     */
    const val LAST_RUNG_HORIZON_MS: Long = 60L * 60_000L

    /**
     * How late an exact alarm may arrive and still be recorded as rung (fix round 1).
     * `setAlarmClock` delivers on time, so an exact alarm later than this means the clock jumped
     * forward past it, and the ring happened at the wrong real time.
     */
    const val RECORD_LATE_LIMIT_EXACT_MS: Long = 2L * 60_000L

    /**
     * The same for an inexact alarm or a backstop, which Android may hold back for up to an hour
     * (`setAndAllowWhileIdle` in Doze), with five minutes of margin.
     */
    const val RECORD_LATE_LIMIT_INEXACT_MS: Long = 65L * 60_000L

    /** The most snoozes kept at once: one per rule, and more than one waiting is already rare. */
    const val MAX_SNOOZES: Int = 16

    /**
     * What to do with an alarm armed for [armedAt] (0 when unknown) arriving at [now], when its
     * rule last rang at [lastRung] (null when it never rang, or the record cannot be read). In
     * this order: early, already rung, ring. When in doubt, ring: an unreadable record rings.
     */
    fun decide(armedAt: Long, now: Long, lastRung: Long?): FireDecision = when {
        armedAt > 0L && now < armedAt - CLOCK_JUMP_TOLERANCE_MS -> FireDecision.Early
        armedAt > 0L && lastRung != null && rungCounts(lastRung, now) && armedAt <= lastRung -> FireDecision.AlreadyRung
        else -> FireDecision.Ring
    }

    /**
     * What to do with a snooze due at [at] arriving at [now]. [stored] is whether the snooze is
     * still in storage, or null when storage could not be read (it then rings). In this order:
     * gone, early, ring. A stored snooze rings however late it arrives (fix round 1): only the
     * re-plan after a restart drops an overdue snooze ([sortSnoozes] with dropLate, decision D-5).
     */
    fun decideSnooze(at: Long, now: Long, stored: Boolean?): FireDecision = when {
        stored == false -> FireDecision.Gone
        at > 0L && now < at - CLOCK_JUMP_TOLERANCE_MS -> FireDecision.Early
        else -> FireDecision.Ring
    }

    /**
     * Whether a ring of the occurrence armed for [armedAt] arriving at [now] is recorded as its
     * last ring (fix round 1, verify 0). Only an on-time arrival is: a late one rang at the wrong
     * real time (the clock jumped forward past it), and recording it would make a later
     * correction of the clock skip the real occurrence. [inexact] is true for a backstop, and for
     * any alarm while exact alarms are not allowed, which Android may legitimately hold back.
     * An arrival up to two seconds early counts as on time.
     */
    fun shouldRecordRing(armedAt: Long, now: Long, inexact: Boolean): Boolean =
        armedAt > 0L && now - armedAt <= if (inexact) RECORD_LATE_LIMIT_INEXACT_MS else RECORD_LATE_LIMIT_EXACT_MS

    /**
     * Whether a last ring recorded for [lastRung] still counts at [now]: it is at most
     * [LAST_RUNG_HORIZON_MS] ahead of the clock (fix round 1).
     */
    fun rungCounts(lastRung: Long, now: Long): Boolean = lastRung - now <= LAST_RUNG_HORIZON_MS

    /**
     * Whether the backstop for the nearest alarm is armed on this version (A-2). Only Android 12
     * and 12L let the person switch exact alarms off (SCHEDULE_EXACT_ALARM is capped at 32 and
     * USE_EXACT_ALARM cannot be revoked from 33), and switching them off cancels every exact alarm
     * the app holds with no broadcast to say so.
     */
    fun backstopWanted(sdkInt: Int): Boolean = sdkInt == 31 || sdkInt == 32

    /**
     * Whether the system event [action] means AlarmManager has forgotten every alarm this app
     * armed: a reboot, through either boot broadcast, and nothing else (fix round 3, code review
     * C-2). An app update is not one: Android keeps a package's alarms when it is replaced, so a
     * snooze waiting through an update is still armed and must not be dropped as late. Only after
     * such an event may the re-plan drop a snooze more than [SNOOZE_LATE_LIMIT_MS] overdue
     * (decision D-5).
     */
    fun wipesAlarms(action: String?): Boolean =
        action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED

    /**
     * Whether the system event [action] means Android has taken away every notification this
     * app posted: a reboot, and an app update too, which keeps the alarms but removes the
     * notifications (verify 3, Android check). After such an event the snoozed notices, with
     * their Dismiss, are posted again, and any owed Missed notice is told (Android check C-2).
     */
    fun clearsNotices(action: String?): Boolean =
        wipesAlarms(action) || action == Intent.ACTION_MY_PACKAGE_REPLACED

    /**
     * Arms with [exact]; if that throws anything, reports it to [onExactFailure] and arms with
     * [inexact]; if that throws too, reports FAILED. Never throws itself (A-7).
     */
    fun armWithFallback(
        exact: () -> Unit,
        inexact: () -> Unit,
        onExactFailure: (RuntimeException) -> Unit = {},
    ): ArmResult =
        try {
            exact()
            ArmResult.EXACT
        } catch (e: RuntimeException) {
            runCatching { onExactFailure(e) }
            try {
                inexact()
                ArmResult.INEXACT
            } catch (_: RuntimeException) {
                ArmResult.FAILED
            }
        }

    /**
     * The instant a rule's next occurrence is planned after: [now], or the instant it last rang
     * when that is later and still counts ([rungCounts]). The planner looks strictly after it, so
     * an occurrence that has rung is not armed again after a small clock correction; after a large
     * one it is, and rings (fix round 1). The alarm list computes its next ring the same way, so
     * it shows what is armed.
     */
    fun planAfter(now: Instant, lastRung: Long?): Instant {
        val nowMillis = now.toEpochMilli()
        return if (lastRung != null && lastRung > nowMillis && rungCounts(lastRung, nowMillis)) Instant.ofEpochMilli(lastRung) else now
    }

    /** [lastRung] with [ruleId] marked as having rung at [at] (never moved backwards). */
    fun recordRung(lastRung: Map<String, Long>, ruleId: String, at: Long): Map<String, Long> {
        val before = lastRung[ruleId]
        return if (before != null && before >= at) lastRung else lastRung + (ruleId to at)
    }

    /** [lastRung] without the rules that no longer exist. */
    fun pruneRung(lastRung: Map<String, Long>, ruleIds: Set<String>): Map<String, Long> =
        lastRung.filterKeys { it in ruleIds }

    /**
     * [lastRung] without the records that no longer count at [now] ([rungCounts], fix round 1).
     * The re-plan drops them before it plans, so that the occurrence it then arms is not taken
     * for one that has rung when it arrives.
     */
    fun pruneRung(lastRung: Map<String, Long>, now: Long): Map<String, Long> =
        lastRung.filterValues { rungCounts(it, now) }

    /**
     * The stored [snoozes] sorted for a re-plan at [now]. [liveRuleIds] are the rules that exist
     * and are switched on: a snooze of any other rule is cancelled (A-6, the delete dialog's "will
     * not ring again"). When [dropLate] is true (the re-plan after a reboot, which wiped
     * AlarmManager: [wipesAlarms]), a live snooze that came due more than [SNOOZE_LATE_LIMIT_MS]
     * ago is late.
     * When it is false (any other re-plan: the app opening, an alarm firing, a rule edit, a clock
     * or zone change, an exact-alarm grant, a snooze, an app update), nothing is late (fix round 2): AlarmManager
     * may still deliver an inexact snooze that Doze holds back, so an overdue one is kept and
     * armed again, and an exact re-arm for a past instant rings at once. The kept snoozes are the
     * newest per rule, at most [MAX_SNOOZES].
     */
    fun sortSnoozes(snoozes: List<PendingSnooze>, liveRuleIds: Set<String>, now: Long, dropLate: Boolean): SnoozeSort {
        val gone = snoozes.filter { it.ruleId !in liveRuleIds }
        val live = snoozes.filter { it.ruleId in liveRuleIds }
        val isLate = { s: PendingSnooze -> dropLate && now > s.at + SNOOZE_LATE_LIMIT_MS }
        val late = live.filter(isLate)
        val keep = live.filterNot(isLate)
            .groupBy { it.ruleId }
            .map { (_, perRule) -> perRule.maxBy { it.at } }
            .sortedBy { it.at }
            .take(MAX_SNOOZES)
        return SnoozeSort(keep = keep, late = late.distinctBy { it.ruleId }, gone = gone.distinctBy { it.ruleId })
    }

    /** [snoozes] with [s] in place of any earlier snooze of the same rule. */
    fun putSnooze(snoozes: List<PendingSnooze>, s: PendingSnooze): List<PendingSnooze> =
        snoozes.filter { it.ruleId != s.ruleId } + s

    /** [snoozes] without the snooze of [ruleId]. */
    fun removeSnooze(snoozes: List<PendingSnooze>, ruleId: String): List<PendingSnooze> =
        snoozes.filter { it.ruleId != ruleId }

    /**
     * When [next] starts while [current] still rings: the alarm to report as missed, or null when
     * it is the same ring (A-5: before, the first one ended with no notice at all).
     */
    fun replaced(current: RingingAlarm?, next: RingingAlarm): RingingAlarm? = current?.takeIf { it.key != next.key }

    // ------------------------------------------------------------------------- storage forms

    fun encodeSnoozes(snoozes: List<PendingSnooze>): String =
        JSONArray().apply { snoozes.forEach { put(it.toJson()) } }.toString()

    /** The snoozes in [text]; entries this version did not write are skipped. Throws on unreadable JSON. */
    fun decodeSnoozes(text: String?): List<PendingSnooze> {
        if (text.isNullOrEmpty()) return emptyList()
        val array = JSONArray(text)
        return List(array.length()) { array.opt(it) }.mapNotNull { PendingSnooze.parse(it) }
    }

    fun encodeRung(lastRung: Map<String, Long>): String =
        JSONObject().apply { lastRung.forEach { (id, at) -> put(id, at) } }.toString()

    /** The last-rung instants in [text]; entries that are not positive whole numbers are skipped. Throws on unreadable JSON. */
    fun decodeRung(text: String?): Map<String, Long> {
        if (text.isNullOrEmpty()) return emptyMap()
        val o = JSONObject(text)
        return buildMap {
            for (key in o.keys()) {
                val at = (o.opt(key) as? Number)?.toLong() ?: continue
                if (at > 0L) put(key, at)
            }
        }
    }
}
