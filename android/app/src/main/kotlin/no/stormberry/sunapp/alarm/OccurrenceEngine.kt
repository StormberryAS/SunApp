package no.stormberry.sunapp.alarm

import no.stormberry.sunapp.alarm.model.AlarmRule
import no.stormberry.sunapp.alarm.model.Occurrence
import no.stormberry.sunapp.solar.SolarEvent
import no.stormberry.sunapp.solar.SunCalc
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * Turns an [AlarmRule] into the instants it fires at. Pure: no Android, no clock of its own,
 * no state. Every time in SunApp that anyone needs to know when an alarm will ring, it comes
 * from here.
 *
 * **There is exactly one implementation of this arithmetic and this is it.** The 30-day
 * preview in the rule editor is the whole mitigation for having no forced clamp (owner's
 * confirmed decision 2): it is the only thing telling the user that their "sunrise minus 30"
 * alarm will be ringing at 08:15 by Christmas. If the preview ever computed times by a
 * different route from the scheduler, it would stop being a warning and start being a lie.
 * So [preview] calls [occurrenceFor], [nextOccurrence] calls [occurrenceFor], and a test
 * asserts that the preview row for a date is identical to a direct call for that date. Any
 * future "quick" path that recomputes fire times elsewhere must be rejected in review.
 *
 * ### The four steps, in order
 *
 * 1. **Anchor day selection.** Find the instant of the requested event whose *local* date in
 *    the rule's zone is the date asked for. Not the same thing as "the event SunCalc returns
 *    for that UTC date": see [resolveAnchor].
 * 2. **Fallback.** If the event does not occur at all, substitute solar noon and flag it
 *    (owner's confirmed decision 1). Solar noon is the one thing that always exists.
 * 3. **Offset.** Pure instant arithmetic, immune to time zones by construction.
 * 4. **Clamp.** Optional, off by default, and the only wall-clock step in the whole app.
 *
 * ### Zones
 *
 * [ZoneId.of] is called on the rule's stored identifier and is allowed to throw if the
 * identifier is not one this tzdb knows. That is deliberate: it is a programming error here,
 * because `AlarmStore` refuses to hand back a rule with an unusable zone, and a silent
 * fallback to the device zone would fire a Reykjavik alarm on Oslo time without ever saying
 * so. `AlarmPlanner` is the layer that catches it, per rule, so one bad row cannot stop the
 * other alarms being re-armed after a reboot.
 */
object OccurrenceEngine {

    /**
     * How far [nextOccurrence] will walk forward before giving up.
     *
     * A full year plus a day covers the worst honest case, which is a golden-hour anchor
     * inside the Arctic Circle: those keys can be absent for months, and while the fallback
     * means the rule still fires, the walk has to survive a long run of unusual days. Beyond
     * that the answer is "this rule has no next occurrence", which the UI reports as an error
     * state rather than the engine throwing.
     */
    private const val MAX_FORWARD_DAYS = 366L

    /**
     * The occurrence of [rule] anchored on [localDate], or null if the solar model returned
     * nothing usable at all for that date.
     *
     * Null is close to unreachable in practice: it requires solar noon itself to be missing,
     * which cannot happen because it is computed directly rather than by solving for a
     * horizon crossing. It is modelled as nullable anyway rather than asserted away, because
     * the alternative is an exception on the path that re-arms alarms after a reboot.
     *
     * Note there is no day-of-week filter. A SunApp rule is a daily rule; recurrence is
     * expressed by the user disabling it, not by a mask, so every date in range produces a row.
     */
    fun occurrenceFor(rule: AlarmRule, localDate: LocalDate): Occurrence? {
        val zone = ZoneId.of(rule.zoneId)
        val anchor = resolveAnchor(rule, localDate, zone) ?: return null

        // Step 3, the offset. This is instant arithmetic and never touches the zone, so it is
        // structurally immune to DST: "sunrise plus eight hours" is eight hours of real time
        // on the night the clocks go back, not seven or nine. That property is worth stating
        // out loud because the obvious alternative implementation (render local, add minutes,
        // convert back) silently loses it, and the loss only shows up twice a year.
        val offsetApplied = anchor.instant.plusSeconds(rule.signedOffsetMinutes * 60L)

        val clamped = applyClamp(rule, zone, offsetApplied)

        return Occurrence(
            ruleId = rule.id,
            anchorDate = localDate,
            anchorAt = anchor.instant,
            fireAt = clamped.instant,
            usedFallback = anchor.usedFallback,
            clamped = clamped.moved,
        )
    }

    /**
     * The first occurrence of [rule] that fires strictly after [from], or null if there is
     * none within a year.
     *
     * Strictly after, with no grace window. A scheduler that has just been woken by an alarm
     * needs "the next one after this one" and would re-arm the same instant forever if the
     * comparison were inclusive. The early-fire guard for a jumped clock (do not ring if we
     * were woken more than a couple of seconds early) belongs in the receiver that observes
     * the jump, not in a pure function that cannot see one.
     *
     * ### Why the walk starts in the past
     *
     * The obvious implementation walks forward from today. It is wrong for exactly the case
     * SunApp exists to support: an offset can carry a fire time onto a different local day
     * from its anchor. "Sunset plus eight hours" in Bergen in June anchors on Friday and
     * rings at 05:12 on Saturday, so at 02:00 on Saturday the next alarm is Friday's row.
     * Starting from today would skip it and arm the one 24 hours later. The walk therefore
     * begins far enough back to cover the offset ([lookbackDays]) and considers those days'
     * fire times like any other.
     *
     * ### Why it does not stop at the first candidate
     *
     * Fire times are almost, but not exactly, monotonic in anchor date. Near the antimeridian
     * the day-selection step in [resolveAnchor] can pull two adjacent local dates onto solar
     * events that are less than 24 hours apart, so the first candidate found is not always
     * the earliest one. The walk keeps the best candidate and carries on for a bounded tail
     * of the same width as the lookback before returning, which costs a handful of extra
     * evaluations and removes a whole class of "fired a day late in Fiji" bug.
     */
    fun nextOccurrence(rule: AlarmRule, from: Instant): Occurrence? {
        val zone = ZoneId.of(rule.zoneId)
        val lookback = lookbackDays(rule)
        val start = from.atZone(zone).toLocalDate().minusDays(lookback)
        val limit = start.plusDays(lookback + MAX_FORWARD_DAYS)

        var best: Occurrence? = null
        var tail = 0L
        var date = start
        while (!date.isAfter(limit)) {
            val candidate = occurrenceFor(rule, date)
            if (candidate != null && candidate.fireAt.isAfter(from)) {
                val current = best
                if (current == null || candidate.fireAt.isBefore(current.fireAt)) best = candidate
            }
            if (best != null) {
                tail++
                if (tail > lookback + 1) break
            }
            date = date.plusDays(1)
        }
        return best
    }

    /**
     * One occurrence per calendar day for [days] days starting at [fromDate].
     *
     * This is what the rule editor renders, and per the class KDoc it must never be anything
     * other than repeated [occurrenceFor] calls. The rows are indexed by anchor date, not by
     * fire time, so a rule whose offset crosses midnight produces rows whose fire dates are
     * shifted by a day relative to their anchor dates; the editor shows both, because that
     * shift is exactly the surprise the preview exists to remove.
     *
     * The first row equals [nextOccurrence] whenever [fromDate] is today and today's fire has
     * not happened yet. Once it has, the two legitimately diverge: the preview is a calendar,
     * [nextOccurrence] is a clock. Do not "fix" that by making the preview skip past rows;
     * a preview that hides today is a preview that cannot explain what just rang.
     */
    fun preview(rule: AlarmRule, fromDate: LocalDate, days: Int = 30): List<Occurrence> {
        if (days <= 0) return emptyList()
        return (0 until days).mapNotNull { offset -> occurrenceFor(rule, fromDate.plusDays(offset.toLong())) }
    }

    /** The anchor instant for a date, and whether it had to fall back to solar noon. */
    private class ResolvedAnchor(val instant: Instant, val usedFallback: Boolean)

    /**
     * Steps 1 and 2: pick the solar event that actually belongs to [localDate] in [zone], and
     * substitute solar noon if that event never happens.
     *
     * **Why a search is needed at all.** `SunCalc.times` is anchored on noon UTC of the date
     * it is handed, which is the rule the web app uses and which the port keeps for parity.
     * For a zone far from its longitude that can return an event whose local date is the day
     * before or the day after the one asked for: Pacific/Apia and Pacific/Fiji are the
     * standard offenders, and Reykjavik manages it too because Iceland keeps UTC while
     * sitting 20 degrees west. Handing that instant back unexamined is how the web app
     * currently prints one date and means another.
     *
     * The correction is a single step by construction. The drift is at most one day, so
     * shifting the probe date by the observed difference lands on the right one; the loop
     * verifies once and then accepts, so a pathological input costs two evaluations rather
     * than spinning.
     *
     * **The fallback** (owner's confirmed decision 1). A null from the solar model means the
     * sun genuinely never reaches that altitude on that date: no sunrise during polar night,
     * no sunset during midnight sun, no golden hour near the Arctic Circle at midwinter. The
     * alarm still has to ring, so solar noon stands in and [Occurrence.usedFallback] tells
     * the UI to say why. The alternative, skipping the day, means a Tromsø user's alarm goes
     * quiet for six weeks in winter, which is a worse failure than an alarm at midday.
     *
     * Day selection is applied to whichever instant is actually being used, fallback or not,
     * so a substituted solar noon lands on the right local date too.
     */
    private fun resolveAnchor(rule: AlarmRule, localDate: LocalDate, zone: ZoneId): ResolvedAnchor? {
        var probe = localDate
        var corrected = false
        while (true) {
            val times = SunCalc.times(probe, rule.latDeg, rule.lonDeg)
            val requested = times[rule.anchor]
            val usedFallback = requested == null
            val instant = requested ?: times[SolarEvent.SOLAR_NOON] ?: return null

            val rendered = instant.atZone(zone).toLocalDate()
            if (rendered == localDate || corrected) return ResolvedAnchor(instant, usedFallback)

            probe = probe.plusDays(localDate.toEpochDay() - rendered.toEpochDay())
            corrected = true
        }
    }

    /** The clamped instant, and whether the clamp actually moved anything. */
    private class ClampOutcome(val instant: Instant, val moved: Boolean)

    /**
     * Step 4, the optional clamp. Off unless the user turned it on, which is the whole of
     * owner's confirmed decision 2.
     *
     * Bounds are wall times evaluated on **the fire time's own local date**, which is to say
     * on the morning (or evening) the alarm would actually ring.
     *
     * That is the correction to an earlier version of this function, and it is worth spelling
     * out because the mistake was subtle and its symptom was catastrophic. The bounds used to
     * be evaluated on the *anchor's* local date. For a rule whose offset crosses midnight,
     * "sunset plus eight hours, never later than 07:00" in Bergen on 21 June anchors at 23:12
     * on the 21st and rings at 07:12 on the 22nd. Evaluating the ceiling on the anchor date
     * built `2026-06-21T07:00`, saw that 07:12 on the 22nd was later than it, and moved the
     * alarm there: 07:00 on the 21st, sixteen hours *before* the sunset the rule tracks. The
     * occurrence then had `fireAt` earlier than `anchorAt`, so it was already in the past by
     * the time the planner looked at it, [nextOccurrence] discarded it, and the user's alarm
     * quietly did not ring that day. An alarm clock has exactly one unforgivable failure and
     * that was it.
     *
     * Using the fire time's own date means a bound can only ever move the alarm within the
     * day it was already going to ring on, which is both what "never later than 07:00" means
     * in English and the only reading under which the clamp cannot invent a time on a day the
     * user was not looking at. The local date is re-read between the two bounds rather than
     * computed once, so that a floor which shifts across a transition still hands the ceiling
     * the date the alarm has actually landed on.
     *
     * **This is the only DST-sensitive path in SunApp**, because it is the only one that
     * builds an instant from a wall time. Two rules, both intended:
     *
     *  - *Spring forward.* A bound of 02:30 on 29 March in Oslo names a wall time that does
     *    not exist. `ZonedDateTime.of` moves it forward by the gap, to 03:30. For an alarm
     *    that is the right answer: a floor of "not before 02:30" is satisfied by 03:30, and
     *    the alternative (moving backwards to 01:30) would breach the floor the user set.
     *  - *Autumn back.* A bound of 02:30 on 25 October names a wall time that happens twice.
     *    [ZonedDateTime.withEarlierOffsetAtOverlap] is called explicitly, so the alarm takes
     *    the first pass. It matches `ZonedDateTime.of`'s own default, and it is written out
     *    rather than relied on because "the alarm rang an hour late once a year" is a bug
     *    nobody reports and everybody notices.
     *
     * When both bounds are set and contradict each other (a [Clamp.earliest] later in the
     * day than [Clamp.latest]) the ceiling wins, because it is evaluated second. The editor
     * should not offer that combination; the engine resolves it deterministically rather than
     * throwing, so a hand-edited or migrated row cannot crash the scheduler.
     */
    private fun applyClamp(
        rule: AlarmRule,
        zone: ZoneId,
        fireAt: Instant,
    ): ClampOutcome {
        val clamp = rule.clamp
        if (clamp == null || !rule.hasEffectiveClamp) return ClampOutcome(fireAt, false)

        var result = fireAt
        var moved = false

        clamp.earliest?.let { earliest ->
            val local = result.atZone(zone).toLocalDateTime()
            val floor = local.toLocalDate().atTime(earliest)
            if (local.isBefore(floor)) {
                result = atWallTime(floor, zone)
                moved = true
            }
        }
        clamp.latest?.let { latest ->
            val local = result.atZone(zone).toLocalDateTime()
            val ceiling = local.toLocalDate().atTime(latest)
            if (local.isAfter(ceiling)) {
                result = atWallTime(ceiling, zone)
                moved = true
            }
        }

        // A clamp that named exactly the instant already computed has not "moved" anything,
        // and reporting it as clamped would put a warning in the UI for a no-op.
        return ClampOutcome(result, moved && result != fireAt)
    }

    /** Resolve a wall time in a zone, with the gap and overlap policy documented in [applyClamp]. */
    private fun atWallTime(local: LocalDateTime, zone: ZoneId): Instant =
        ZonedDateTime.of(local, zone).withEarlierOffsetAtOverlap().toInstant()

    /**
     * How many days before [Instant] "now" the day walk has to start so that no fire time can
     * be missed.
     *
     * A fire time is its anchor plus the offset, and the anchor is pinned to its own local
     * date by [resolveAnchor], so the furthest a fire time can travel from its anchor date is
     * the offset itself, rounded up to whole days. The extra two days are for the antimeridian
     * wobble described in [nextOccurrence] and cost two solar evaluations, which is nothing
     * next to being wrong once a year in Fiji.
     */
    private fun lookbackDays(rule: AlarmRule): Long =
        abs(rule.signedOffsetMinutes).toLong() / (24L * 60L) + 2L
}
