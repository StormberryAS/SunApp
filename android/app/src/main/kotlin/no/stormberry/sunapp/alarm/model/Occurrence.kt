package no.stormberry.sunapp.alarm.model

import java.time.Instant
import java.time.LocalDate

/**
 * One computed firing of one rule: the 21st row of a preview, or the thing the scheduler is
 * about to arm. Pure data, produced only by `OccurrenceEngine.occurrenceFor`.
 *
 * Both instants are non-null, and that is a load-bearing guarantee rather than an accident.
 * SunCalc returns null for an event that does not occur, and the temptation at every layer
 * above it is to model that as `0L` or to let a NaN through; on 21 December in Longyearbyen
 * that renders an alarm at 1970-01-01. The engine resolves the absence *before* constructing
 * an [Occurrence], by substituting solar noon, so nothing downstream has to carry a nullable
 * time or invent a sentinel.
 *
 * @property anchorDate the local date the rule is "about", in the rule's own zone. This is
 *   the date the user picked a row for, not necessarily the date [fireAt] renders on: a
 *   sunset anchor with an eight-hour offset in Bergen fires the following morning, and the
 *   preview labels that explicitly. Keeping the anchor date separate from the fire date is
 *   what lets the UI say "sunset on Friday, ringing 02:11 Saturday" instead of lying about
 *   one of the two.
 * @property anchorAt the instant of the solar event itself, before the offset and before any
 *   clamp. Exposed so the editor can show what the alarm is tracking, and so a test can
 *   assert the offset arithmetic independently of the solar maths.
 * @property fireAt the instant the alarm rings. This is what gets armed.
 * @property usedFallback true when the requested anchor did not occur on this date and solar
 *   noon was substituted (owner's confirmed decision 1). The UI must surface this rather than
 *   silently ringing at an unexpected hour: "No sunrise on 21 December, using solar noon".
 * @property clamped true when [Clamp] moved [fireAt] away from anchor-plus-offset. Also worth
 *   surfacing, because a clamped alarm has stopped tracking the sun, which is the thing the
 *   user asked for in the first place.
 */
data class Occurrence(
    val ruleId: String,
    val anchorDate: LocalDate,
    val anchorAt: Instant,
    val fireAt: Instant,
    val usedFallback: Boolean,
    val clamped: Boolean,
)
