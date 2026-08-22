package no.stormberry.sunapp.alarm.model

import no.stormberry.sunapp.solar.SolarEvent
import java.time.LocalTime

/**
 * Which side of the anchor the alarm sits on.
 *
 * This is deliberately a separate field from [AlarmRule.offsetMinutes] rather than the sign
 * of a single signed integer. The editor shows a three-way control and a magnitude, and
 * modelling it that way means the two halves of the user's choice can never disagree with
 * each other: [AT] is a distinct state rather than the accident of typing zero, so "before 0"
 * and "after 0" are unrepresentable instead of merely discouraged.
 */
enum class Direction { BEFORE, AT, AFTER }

/**
 * An optional pair of wall-clock bounds on the fire time, evaluated on the local date of the
 * anchor.
 *
 * SunApp has no forced clamp (owner's confirmed decision 2). A rule with no clamp tracks the
 * sun wherever the sun goes, which at Norwegian latitudes means a "sunrise" alarm walks by
 * roughly three minutes a day in autumn and lands after 09:00 by Christmas. That drift is the
 * point of a solar alarm, so the 30-day preview in the editor, not a hidden bound, is what
 * makes it visible. This type exists for the user who wants the drift *and* a floor under it.
 *
 * Both bounds are independently optional. A null [earliest] means "however early the sun
 * says", a null [latest] means "however late". A [Clamp] with both bounds null is inert and
 * indistinguishable from no clamp at all.
 *
 * The bounds are wall times read on the local date the alarm would actually ring, not on the
 * date of the anchor. For a rule whose offset crosses midnight those are different days, and
 * only the first reading matches what "never later than 07:00" means in English: the other
 * one can move an alarm to a wall time sixteen hours before the sunset it tracks, leaving a
 * fire time that is already in the past. The reasoning is written out in full at
 * `OccurrenceEngine.applyClamp`, along with how a DST gap and a DST overlap are resolved.
 * This is the only path in the app that builds an instant from a wall time, and therefore
 * the only one a time-zone transition can move.
 */
data class Clamp(
    val earliest: LocalTime? = null,
    val latest: LocalTime? = null,
)

/**
 * One solar alarm as the user configured it. Pure data: no Android types, no computed times.
 *
 * The rule stores the *question* ("thirty minutes before sunrise in Bergen"), never the
 * answer. Every fire instant is recomputed from this by `OccurrenceEngine` on the day it is
 * needed, which is what lets a single stored row stay correct across a DST transition, a
 * tzdb update, a reboot, or a year of the sun moving. Caching a computed instant on the rule
 * would be the single easiest way to reintroduce every bug this design avoids.
 *
 * Location is carried as raw coordinates plus a display name rather than as a city
 * identifier. The city table is an asset that can be regenerated, and a rule that pointed at
 * row 1,412 of it would silently move house on the next data refresh. Coordinates cannot
 * drift. [placeName] is for display only and is never used in a calculation.
 *
 * [zoneId] is an IANA identifier and is stored alongside the coordinates rather than derived
 * from them, because the derivation (nearest city in the bundled table) is lossy above about
 * 80 degrees and because the user is allowed to disagree with it. It is what the clamp and
 * the day walk are evaluated in; the offset arithmetic never touches it.
 *
 * @property id stable across edits, so a rule keeps its `AlarmManager` request code and an
 *   armed alarm survives being renamed. Generated once at creation, never reused.
 * @property offsetMinutes the *magnitude* of the offset in minutes, paired with [direction].
 *   Not validated as non-negative: see [signedOffsetMinutes] for what a negative value means.
 * @property clamp null means no clamp, which is the default and what every rule the editor
 *   creates starts with.
 * @property ringtoneUri a system `RingtoneManager.TYPE_ALARM` URI as a string, or null for
 *   the bundled tone. Held as a String rather than an `android.net.Uri` so this whole tree
 *   stays testable on the JVM.
 */
data class AlarmRule(
    val id: String,
    val label: String,
    val anchor: SolarEvent,
    val direction: Direction,
    val offsetMinutes: Int,
    val latDeg: Double,
    val lonDeg: Double,
    val zoneId: String,
    val placeName: String,
    val clamp: Clamp? = null,
    val enabled: Boolean = true,
    val ringtoneUri: String? = null,
    val vibrate: Boolean = true,
) {
    /**
     * The offset as signed minutes to add to the anchor instant: negative for [Direction.BEFORE],
     * zero for [Direction.AT], positive for [Direction.AFTER].
     *
     * [Direction.AT] discards [offsetMinutes] entirely rather than adding it. "At sunrise, plus
     * twenty minutes" is not a thing a user can mean, so a stale magnitude left behind by
     * flipping the control back to AT must not silently move the alarm.
     *
     * A negative [offsetMinutes] is not rejected and not folded through `abs`. It simply
     * subtracts under [Direction.AFTER] and adds under [Direction.BEFORE], because quietly
     * rewriting a user's number is worse than obeying it, and throwing from a data class
     * constructor would turn a corrupt stored row into a crash on launch.
     */
    val signedOffsetMinutes: Int
        get() = when (direction) {
            Direction.BEFORE -> -offsetMinutes
            Direction.AT -> 0
            Direction.AFTER -> offsetMinutes
        }

    /**
     * True when this rule has a clamp that could actually move a fire time.
     *
     * A non-null [Clamp] with both bounds null is treated as absent everywhere, so that the
     * editor can hold an empty clamp while the section is open without that being a
     * behavioural change.
     */
    val hasEffectiveClamp: Boolean
        get() = clamp != null && (clamp.earliest != null || clamp.latest != null)
}
