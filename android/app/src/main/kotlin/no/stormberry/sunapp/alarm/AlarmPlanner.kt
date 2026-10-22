package no.stormberry.sunapp.alarm

import no.stormberry.sunapp.alarm.model.AlarmRule
import no.stormberry.sunapp.alarm.model.Occurrence
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * One rule's next firing together with the identifier the platform will remember it by.
 *
 * The request code is carried alongside the occurrence rather than recomputed by the sink,
 * so that arming and cancelling cannot disagree about which integer belongs to which rule.
 * That disagreement is the classic way an alarm app ends up with an orphaned pending intent
 * that nothing can cancel and that rings after the rule was deleted.
 */
data class PlannedAlarm(val occurrence: Occurrence, val requestCode: Int)

/**
 * Decides the complete set of alarms that should be pending right now, and drives an
 * [AlarmSink] to make reality match.
 *
 * Pure with respect to time: [Clock] or an explicit [Instant] comes in as a parameter and is
 * never read from the environment, which is what lets the tests replay a DST night, a reboot
 * and a backwards clock jump in milliseconds instead of waiting for them.
 *
 * The planner deliberately re-derives everything from the rules on every call rather than
 * tracking incremental state. Re-planning happens after boot, after `MY_PACKAGE_REPLACED`,
 * after a time-zone change, after a manual time change, after an exact-alarm permission
 * change, and after every alarm fires, and each of those events can arrive with the previous
 * state arbitrarily stale or absent. A full recompute is cheap (a solar evaluation per rule
 * per candidate day) and cannot drift; incremental bookkeeping across seven entry points
 * would be a source of exactly the bugs an alarm clock must not have.
 */
object AlarmPlanner {

    /**
     * The alarms that should be pending as of [clock]'s instant.
     *
     * Only one occurrence per rule is planned. `AlarmManager` is a scarce, system-visible
     * resource and only the next firing can be exact anyway; the alarm after it is armed by
     * the receiver when the first one fires, which is also what keeps a year-out alarm from
     * being computed against a tzdb that will have been updated twice before it rings.
     */
    fun plan(rules: List<AlarmRule>, clock: Clock): List<PlannedAlarm> = plan(rules, clock.instant())

    /**
     * The alarms that should be pending as of [now]. See the [Clock] overload.
     *
     * Disabled rules are dropped, and so is any rule that has no next occurrence within a
     * year or whose stored zone this tzdb no longer recognises. Both are swallowed per rule
     * rather than propagated, because this runs inside a boot receiver: one unusable row must
     * not stop the other alarms being re-armed. A rule that vanishes here is visible in the
     * UI as "not scheduled" rather than being silently deleted.
     */
    fun plan(rules: List<AlarmRule>, now: Instant): List<PlannedAlarm> = plan(rules, now, emptyMap())

    /**
     * The alarms that should be pending as of [now], given the instant each rule last rang
     * ([lastRung], epoch milliseconds by rule id; 1.1.4).
     *
     * A rule's next occurrence is looked for strictly after the later of [now] and its last ring,
     * so an occurrence that has rung is never armed again. Without that, a clock set back after
     * a ring, or an alarm that arrived up to two seconds early, re-armed the occurrence that had
     * just rung and it rang twice; and the Android 12 backstop would ring every alarm twice.
     */
    fun plan(rules: List<AlarmRule>, now: Instant, lastRung: Map<String, Long>): List<PlannedAlarm> =
        rules.asSequence()
            .filter { it.enabled }
            .mapNotNull { rule ->
                val next = try {
                    OccurrenceEngine.nextOccurrence(rule, FireRules.planAfter(now, lastRung[rule.id]))
                } catch (_: RuntimeException) {
                    // Practically: ZoneId.of on an identifier retired from tzdb. See the
                    // zone note in OccurrenceEngine's KDoc for why the engine throws here
                    // instead of guessing, and AlarmStore for how such a row is disabled on
                    // the way in so this branch stays close to unreachable.
                    null
                }
                next?.let { PlannedAlarm(it, requestCodeFor(rule.id)) }
            }
            .sortedBy { it.occurrence.fireAt }
            .toList()

    /**
     * Make the sink match [plan], cancelling anything in [previouslyArmed] that is no longer
     * wanted, and return the set of request codes now armed.
     *
     * Cancel before arm, so that a rule which stays in the plan is never momentarily
     * unarmed: its code is not in the cancel set, so the re-arm simply replaces it in place.
     *
     * The previously-armed set is passed in rather than remembered because the planner has no
     * business owning persistent state, and because after a reboot the honest answer is "we
     * do not know what was armed", which an empty set expresses exactly. Cancelling a code
     * that was never armed is a no-op by [AlarmSink]'s contract, so passing a stale or
     * over-broad set is safe.
     */
    fun apply(
        plan: List<PlannedAlarm>,
        sink: AlarmSink,
        previouslyArmed: Set<Int> = emptySet(),
        backstop: Boolean = false,
        snoozes: List<PlannedAlarm> = emptyList(),
    ): Set<Int> {
        val wanted = plan.map { it.requestCode }.toSet()
        for (stale in previouslyArmed - wanted) sink.cancel(stale)
        for (planned in plan) sink.arm(planned)
        // 1.1.4, A-2: the backstop follows the nearest alarm on Android 12 and 12L
        // ([backstop], from FireRules.backstopWanted) and is cancelled everywhere else. Last, so
        // it never stands in for an alarm the loop above has not armed yet. Fix round 1: a
        // waiting snooze ([snoozes], armed by the caller) that comes first takes it instead, since
        // revoking exact alarms cancels an exact snooze as well.
        sink.guard(if (backstop) backstopTarget(plan, snoozes) else null)
        return wanted
    }

    /** The alarm the backstop stands in for: the earliest of the nearest alarm and the waiting snoozes (an alarm on a tie). */
    fun backstopTarget(plan: List<PlannedAlarm>, snoozes: List<PlannedAlarm>): PlannedAlarm? {
        val alarm = plan.minByOrNull { it.occurrence.fireAt }
        val snooze = snoozes.minByOrNull { it.occurrence.fireAt }
        return when {
            snooze == null -> alarm
            alarm == null -> snooze
            snooze.occurrence.fireAt.isBefore(alarm.occurrence.fireAt) -> snooze
            else -> alarm
        }
    }

    /**
     * Carries out a re-plan's sort of the stored snoozes ([FireRules.sortSnoozes], A-6) on [sink]:
     * cancels the snooze alarm of every rule in [SnoozeSort.gone] and [SnoozeSort.late], then arms
     * every snooze in [SnoozeSort.keep] again, a past instant included (it then rings at once).
     * The notices that go with them are the caller's. Returns the snoozes still waiting.
     */
    fun applySnoozes(sort: SnoozeSort, sink: AlarmSink, zone: ZoneId): List<PendingSnooze> {
        for (s in sort.gone + sort.late) sink.cancel(snoozeRequestCodeFor(s.ruleId))
        for (s in sort.keep) sink.arm(snoozePlanned(s, zone))
        return sort.keep
    }

    /**
     * A snooze as an alarm to arm: a synthetic occurrence on the rule's odd sibling request code,
     * so tomorrow's alarm for the same rule stays armed. It has no solar anchor, so anchorAt is
     * the fire time itself and the two fallback flags are false.
     */
    fun snoozePlanned(snooze: PendingSnooze, zone: ZoneId): PlannedAlarm {
        val at = Instant.ofEpochMilli(snooze.at)
        return PlannedAlarm(
            Occurrence(
                ruleId = snooze.ruleId,
                // atZone().toLocalDate() rather than LocalDate.ofInstant, which is a Java 9
                // addition and therefore not something to bet a 06:00 alarm on under library
                // desugaring on API 24.
                anchorDate = at.atZone(zone).toLocalDate(),
                anchorAt = at,
                fireAt = at,
                usedFallback = false,
                clamped = false,
            ),
            snoozeRequestCodeFor(snooze.ruleId),
        )
    }

    /** Convenience for the common caller: plan and apply in one step. */
    fun sync(
        rules: List<AlarmRule>,
        clock: Clock,
        sink: AlarmSink,
        previouslyArmed: Set<Int> = emptySet(),
    ): Set<Int> = apply(plan(rules, clock), sink, previouslyArmed)

    /**
     * The `PendingIntent` request code for a rule's own alarm. Stable for the life of the
     * rule id and derived from nothing else.
     *
     * Stability is the requirement. `AlarmManager` identifies a pending alarm by request code
     * plus intent, so a code that changed when the user renamed a rule, or that depended on
     * the rule's position in a list, would leave the old alarm armed and unreachable while a
     * second one was added beside it. Deriving it from the id alone means an edit replaces
     * the alarm and a delete can always cancel it.
     *
     * FNV-1a over the id's UTF-8 bytes: a few lines, no dependency, and deterministic across
     * processes and Android versions, which `String.hashCode` also is but which
     * `Object.hashCode` is emphatically not. The low bit is cleared so that base codes are
     * always even and snooze codes always odd, which makes a collision between the two
     * arithmetically impossible rather than merely unlikely.
     *
     * Two different rules can still collide, at roughly one chance in a billion per pair. The
     * consequence is one alarm replacing another, which is bad, so ids are generated as
     * UUIDs and never recycled; a table of a handful of rules makes this a non-risk in
     * practice and the alternative (a persisted counter) is a migration and a corruption mode.
     */
    fun requestCodeFor(ruleId: String): Int {
        var hash = -0x7EE3623B // FNV-1a 32-bit offset basis, 2166136261 as a signed Int
        for (byte in ruleId.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toInt() and 0xFF)
            hash *= 0x01000193 // FNV prime
        }
        return hash and 0x7FFFFFFE
    }

    /**
     * The request code for a rule's snooze re-arm.
     *
     * Always the odd sibling of [requestCodeFor], so snoozing can never cancel or overwrite
     * the pending alarm for the following day. Both survive: the snooze rings in nine
     * minutes, tomorrow's alarm is still armed for tomorrow.
     */
    fun snoozeRequestCodeFor(ruleId: String): Int = requestCodeFor(ruleId) or 1
}
