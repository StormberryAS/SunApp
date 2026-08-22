package no.stormberry.sunapp.alarm

import no.stormberry.sunapp.alarm.model.Clamp
import no.stormberry.sunapp.alarm.model.Direction
import no.stormberry.sunapp.solar.SolarEvent
import no.stormberry.sunapp.solar.SunCalc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * The behaviour of [OccurrenceEngine], which is the component where a bug means the owner
 * oversleeps.
 *
 * ### Where the expected instants come from
 *
 * Every literal instant below was produced by running the bundled `suncalc.js` under Node
 * with app.js's noon-UTC anchoring rule, not by running this Kotlin and writing down what it
 * said. Where a value also appears in `suncalc-golden.csv` the two agree to the millisecond,
 * and the ones that do not appear there (the two Oslo transition days, 24 October) were
 * generated the same way from the same file. The arithmetic on top of them, the offsets and
 * the clamps and the local-date rendering, is worked out by hand in the comment on each test.
 *
 * That distinction is the whole value of this file. A test that asserts what the code
 * currently returns cannot fail, and a suite of those is what let the clamp bug documented in
 * `a ceiling on a rule that fires after midnight bounds the morning it rings` ship unnoticed.
 *
 * ### Time
 *
 * No test reads the wall clock. [nextOccurrence] takes its "now" as a parameter and every
 * such parameter here comes from a fixed [Clock], so the suite gives the same answer in 2026
 * and in 2031, on a machine set to Oslo and on one set to Auckland.
 */
class OccurrenceEngineTest {

    // -------------------------------------------------------------------------------------
    // Bergen, the ordinary case: does the offset arithmetic land where the sun says it should
    // -------------------------------------------------------------------------------------

    /**
     * Bergen, 21 June 2026, six hours before solar noon.
     *
     * Solar noon is 11:41:42.271Z, which is 13:41:42 CEST, so the rule's anchor date and the
     * date it renders on are the same and no day correction is involved. Six hours earlier is
     * 05:41:42.271Z, or 07:41:42 local: a plausible midsummer alarm and one that drifts with
     * the sun rather than sitting on a fixed wall time.
     */
    @Test
    fun `Bergen six hours before solar noon fires six hours before the computed noon`() {
        val rule = testRule(
            anchor = SolarEvent.SOLAR_NOON,
            direction = Direction.BEFORE,
            offsetMinutes = 6 * 60,
        )

        val occurrence = OccurrenceEngine.occurrenceFor(rule, LocalDate.of(2026, 6, 21))!!

        assertEquals(Instant.parse("2026-06-21T11:41:42.271Z"), occurrence.anchorAt)
        assertEquals(Instant.parse("2026-06-21T05:41:42.271Z"), occurrence.fireAt)
        assertEquals(LocalDate.of(2026, 6, 21), occurrence.anchorDate)
        assertFalse("solar noon always exists so nothing was substituted", occurrence.usedFallback)
        assertFalse("no clamp on this rule", occurrence.clamped)
        assertEquals("07:41:42.271", occurrence.fireAt.atZone(OSLO).toLocalTime().toString())
    }

    /**
     * Bergen, 21 June 2026, eight hours after sunrise.
     *
     * Sunrise is 02:11:05.149Z (04:11:05 CEST), so the alarm is at 10:11:05.149Z, 12:11:05
     * local. The point of asserting a second Bergen rule with the opposite direction and a
     * different anchor is that a sign error in [AlarmRule.signedOffsetMinutes] would pass the
     * BEFORE test above and fail here, or the reverse.
     */
    @Test
    fun `Bergen eight hours after sunrise fires eight hours after the computed sunrise`() {
        val rule = testRule(
            anchor = SolarEvent.SUNRISE,
            direction = Direction.AFTER,
            offsetMinutes = 8 * 60,
        )

        val occurrence = OccurrenceEngine.occurrenceFor(rule, LocalDate.of(2026, 6, 21))!!

        assertEquals(Instant.parse("2026-06-21T02:11:05.149Z"), occurrence.anchorAt)
        assertEquals(Instant.parse("2026-06-21T10:11:05.149Z"), occurrence.fireAt)
        assertFalse(occurrence.usedFallback)
        assertEquals("12:11:05.149", occurrence.fireAt.atZone(OSLO).toLocalTime().toString())
    }

    /**
     * The anchor the engine uses is the one the solar model returns for that date, not a
     * value this suite made up.
     *
     * Asserted independently of the literals above so that a regeneration of `suncalc.js`
     * which legitimately moved every instant would fail the two tests above (parity broken,
     * which is the point of the golden corpus) but still pass this one (the engine is still
     * reading the model correctly). The two kinds of failure mean completely different things
     * and it is worth being able to tell them apart from the report alone.
     */
    @Test
    fun `the anchor instant is whatever SunCalc returns for that date`() {
        val date = LocalDate.of(2026, 6, 21)
        val rule = testRule(anchor = SolarEvent.SUNRISE, direction = Direction.AFTER, offsetMinutes = 8 * 60)

        val expectedAnchor = SunCalc.times(date, BERGEN_LAT, BERGEN_LON)[SolarEvent.SUNRISE]!!
        val occurrence = OccurrenceEngine.occurrenceFor(rule, date)!!

        assertEquals(expectedAnchor, occurrence.anchorAt)
        assertEquals(expectedAnchor.plusSeconds(8 * 3600L), occurrence.fireAt)
    }

    /**
     * [Direction.AT] discards the magnitude rather than adding it.
     *
     * The editor can leave a stale number behind when the user flips the three-way control
     * back to AT, and "at sunrise, plus two hours" is not a thing anyone means.
     */
    @Test
    fun `AT ignores a stale offset magnitude`() {
        val rule = testRule(anchor = SolarEvent.SUNRISE, direction = Direction.AT, offsetMinutes = 120)

        val occurrence = OccurrenceEngine.occurrenceFor(rule, LocalDate.of(2026, 6, 21))!!

        assertEquals(occurrence.anchorAt, occurrence.fireAt)
        assertEquals(Instant.parse("2026-06-21T02:11:05.149Z"), occurrence.fireAt)
    }

    // -------------------------------------------------------------------------------------
    // The polar fallback: owner's confirmed decision 1
    // -------------------------------------------------------------------------------------

    /**
     * Longyearbyen, 21 December 2026: polar night, so there is no sunrise to anchor to.
     *
     * `suncalc.js` returns NONE for SUNRISE on this date (golden case G12) and solar noon at
     * 10:56:38.367Z, which is 11:56:38 CET, so the substituted anchor still renders on the
     * date the rule asked for. The alarm rings and [Occurrence.usedFallback] is what tells
     * the UI to say "no sunrise on 21 December, using solar noon" rather than ringing at an
     * unexplained hour.
     */
    @Test
    fun `Longyearbyen in December falls back to solar noon and flags it`() {
        val rule = testRule(
            anchor = SolarEvent.SUNRISE,
            direction = Direction.AT,
            latDeg = LONGYEARBYEN_LAT,
            lonDeg = LONGYEARBYEN_LON,
            placeName = "Longyearbyen",
        )
        val date = LocalDate.of(2026, 12, 21)

        assertNull(
            "the premise of this test is that there is no sunrise",
            SunCalc.times(date, LONGYEARBYEN_LAT, LONGYEARBYEN_LON)[SolarEvent.SUNRISE],
        )

        val occurrence = OccurrenceEngine.occurrenceFor(rule, date)!!

        assertTrue("the missing anchor must be reported", occurrence.usedFallback)
        assertEquals(Instant.parse("2026-12-21T10:56:38.367Z"), occurrence.anchorAt)
        assertEquals(Instant.parse("2026-12-21T10:56:38.367Z"), occurrence.fireAt)
        assertEquals(
            "the substituted anchor still belongs to the date that was asked for",
            date,
            occurrence.fireAt.atZone(OSLO).toLocalDate(),
        )
    }

    /**
     * The fallback is an anchor like any other, so the user's offset still applies to it.
     *
     * Thirty minutes before the substituted solar noon is 10:26:38.367Z. A fallback that
     * ignored the offset would ring at a different time from the one the preview showed.
     */
    @Test
    fun `the December fallback still carries the rule's offset`() {
        val rule = testRule(
            anchor = SolarEvent.SUNRISE,
            direction = Direction.BEFORE,
            offsetMinutes = 30,
            latDeg = LONGYEARBYEN_LAT,
            lonDeg = LONGYEARBYEN_LON,
            placeName = "Longyearbyen",
        )

        val occurrence = OccurrenceEngine.occurrenceFor(rule, LocalDate.of(2026, 12, 21))!!

        assertTrue(occurrence.usedFallback)
        assertEquals(Instant.parse("2026-12-21T10:26:38.367Z"), occurrence.fireAt)
    }

    /**
     * Longyearbyen, 21 June 2026: midnight sun, the opposite polar case, so there is no
     * sunset.
     *
     * Golden case G13 has all twelve angle events absent on this date and solar noon at
     * 11:00:23.969Z. Worth testing separately from December rather than assuming symmetry:
     * the two are different branches of the solver, and a port that collapsed "any NaN" into
     * one polar case would pass one and fail the other.
     */
    @Test
    fun `Longyearbyen in June falls back to solar noon for a sunset anchor`() {
        val rule = testRule(
            anchor = SolarEvent.SUNSET,
            direction = Direction.AT,
            latDeg = LONGYEARBYEN_LAT,
            lonDeg = LONGYEARBYEN_LON,
            placeName = "Longyearbyen",
        )
        val date = LocalDate.of(2026, 6, 21)

        assertNull(
            "the premise of this test is that there is no sunset",
            SunCalc.times(date, LONGYEARBYEN_LAT, LONGYEARBYEN_LON)[SolarEvent.SUNSET],
        )

        val occurrence = OccurrenceEngine.occurrenceFor(rule, date)!!

        assertTrue(occurrence.usedFallback)
        assertEquals(Instant.parse("2026-06-21T11:00:23.969Z"), occurrence.fireAt)
    }

    /**
     * A Bergen rule on the same dates does not claim a fallback.
     *
     * The flag drives a warning in the UI, so a version that set it defensively on every
     * high-latitude rule would train the user to ignore it.
     */
    @Test
    fun `a rule with a real anchor never reports a fallback`() {
        val rule = testRule(anchor = SolarEvent.SUNRISE, direction = Direction.AT)

        for (date in listOf(LocalDate.of(2026, 6, 21), LocalDate.of(2026, 12, 21))) {
            assertFalse(
                "Bergen has a sunrise on $date",
                OccurrenceEngine.occurrenceFor(rule, date)!!.usedFallback,
            )
        }
    }

    // -------------------------------------------------------------------------------------
    // Offsets that cross midnight
    // -------------------------------------------------------------------------------------

    /**
     * An offset can carry the fire time onto the local day *before* its anchor date.
     *
     * Bergen sunrise on 21 June is 04:11:05 CEST; six hours earlier is 22:11:05 CEST on the
     * 20th. The occurrence still belongs to the 21st, which is what [Occurrence.anchorDate]
     * records, and the preview is expected to render both dates rather than pretend they are
     * the same one.
     */
    @Test
    fun `an offset can push the fire time onto the previous local day`() {
        val rule = testRule(anchor = SolarEvent.SUNRISE, direction = Direction.BEFORE, offsetMinutes = 6 * 60)

        val occurrence = OccurrenceEngine.occurrenceFor(rule, LocalDate.of(2026, 6, 21))!!

        assertEquals(Instant.parse("2026-06-20T20:11:05.149Z"), occurrence.fireAt)
        assertEquals(LocalDate.of(2026, 6, 21), occurrence.anchorDate)
        assertEquals(LocalDate.of(2026, 6, 20), occurrence.fireAt.atZone(OSLO).toLocalDate())
        assertEquals("22:11:05.149", occurrence.fireAt.atZone(OSLO).toLocalTime().toString())
    }

    /**
     * And onto the local day *after*.
     *
     * Bergen sunset on 21 June is 23:12:19 CEST; eight hours later is 07:12:19 CEST on the
     * 22nd. This is the shape of rule that the anchor-date clamp bug destroyed, and it is
     * asserted here without a clamp so that the two failures stay separable.
     */
    @Test
    fun `an offset can push the fire time onto the next local day`() {
        val rule = testRule(anchor = SolarEvent.SUNSET, direction = Direction.AFTER, offsetMinutes = 8 * 60)

        val occurrence = OccurrenceEngine.occurrenceFor(rule, LocalDate.of(2026, 6, 21))!!

        assertEquals(Instant.parse("2026-06-21T21:12:19.394Z"), occurrence.anchorAt)
        assertEquals(Instant.parse("2026-06-22T05:12:19.394Z"), occurrence.fireAt)
        assertEquals(LocalDate.of(2026, 6, 21), occurrence.anchorDate)
        assertEquals(LocalDate.of(2026, 6, 22), occurrence.fireAt.atZone(OSLO).toLocalDate())
        assertEquals("07:12:19.394", occurrence.fireAt.atZone(OSLO).toLocalTime().toString())
    }

    // -------------------------------------------------------------------------------------
    // The clamp: owner's confirmed decision 2, off by default and optional per rule
    // -------------------------------------------------------------------------------------

    /**
     * A ceiling that bites. Bergen sunrise on 21 December is 09:45:45 CET, which is exactly
     * the midwinter lie-in a "wake me at sunrise" rule produces and exactly what a ceiling is
     * for. Capped at 07:00 the alarm moves to 07:00 CET, 06:00:00Z, and says so.
     */
    @Test
    fun `a ceiling that bites moves the fire time and flags it`() {
        val rule = testRule(
            anchor = SolarEvent.SUNRISE,
            direction = Direction.AT,
            clamp = Clamp(latest = LocalTime.of(7, 0)),
        )

        val occurrence = OccurrenceEngine.occurrenceFor(rule, LocalDate.of(2026, 12, 21))!!

        assertEquals(Instant.parse("2026-12-21T08:45:45.303Z"), occurrence.anchorAt)
        assertEquals(Instant.parse("2026-12-21T06:00:00Z"), occurrence.fireAt)
        assertTrue("a clamped alarm has stopped tracking the sun and the UI must say so", occurrence.clamped)
    }

    /**
     * A ceiling that does not bite leaves everything alone, including the flag. Sunrise at
     * 09:45:45 CET is comfortably inside a 10:00 ceiling.
     */
    @Test
    fun `a ceiling that does not bite leaves the fire time and the flag alone`() {
        val rule = testRule(
            anchor = SolarEvent.SUNRISE,
            direction = Direction.AT,
            clamp = Clamp(latest = LocalTime.of(10, 0)),
        )

        val occurrence = OccurrenceEngine.occurrenceFor(rule, LocalDate.of(2026, 12, 21))!!

        assertEquals(Instant.parse("2026-12-21T08:45:45.303Z"), occurrence.fireAt)
        assertFalse(occurrence.clamped)
    }

    /**
     * A floor that bites, the midsummer mirror of the ceiling test. Bergen sunrise on 21 June
     * is 04:11:05 CEST; a floor of 06:00 moves it to 06:00 CEST, 04:00:00Z.
     */
    @Test
    fun `a floor that bites moves the fire time and flags it`() {
        val rule = testRule(
            anchor = SolarEvent.SUNRISE,
            direction = Direction.AT,
            clamp = Clamp(earliest = LocalTime.of(6, 0)),
        )

        val occurrence = OccurrenceEngine.occurrenceFor(rule, LocalDate.of(2026, 6, 21))!!

        assertEquals(Instant.parse("2026-06-21T04:00:00Z"), occurrence.fireAt)
        assertTrue(occurrence.clamped)
    }

    /** A floor below the sunrise does nothing. */
    @Test
    fun `a floor that does not bite leaves the fire time and the flag alone`() {
        val rule = testRule(
            anchor = SolarEvent.SUNRISE,
            direction = Direction.AT,
            clamp = Clamp(earliest = LocalTime.of(3, 0)),
        )

        val occurrence = OccurrenceEngine.occurrenceFor(rule, LocalDate.of(2026, 6, 21))!!

        assertEquals(Instant.parse("2026-06-21T02:11:05.149Z"), occurrence.fireAt)
        assertFalse(occurrence.clamped)
    }

    /**
     * A [Clamp] with both bounds null is inert.
     *
     * The editor holds one of these while the clamp section is open and the user has not yet
     * picked a bound, and that must not count as a behavioural change or light the warning.
     */
    @Test
    fun `a clamp with both bounds null is not a clamp`() {
        val rule = testRule(anchor = SolarEvent.SUNRISE, direction = Direction.AT, clamp = Clamp())

        val occurrence = OccurrenceEngine.occurrenceFor(rule, LocalDate.of(2026, 6, 21))!!

        assertEquals(Instant.parse("2026-06-21T02:11:05.149Z"), occurrence.fireAt)
        assertFalse(occurrence.clamped)
    }

    /**
     * **Regression, and the reason this file exists.**
     *
     * "Sunset plus eight hours, never later than 07:00" in Bergen on 21 June anchors at
     * 23:12:19 CEST and would ring at 07:12:19 CEST on the 22nd. The bound belongs to that
     * morning, so the alarm moves to 07:00 CEST on the 22nd: 2026-06-22T05:00:00Z.
     *
     * The shipped engine evaluated the bound on the *anchor's* local date instead and
     * returned 2026-06-21T05:00:00Z, 07:00 on the 21st, sixteen hours before the sunset the
     * rule tracks. The occurrence came back with `fireAt` earlier than `anchorAt`, already in
     * the past, so `nextOccurrence` discarded it and the alarm silently did not ring.
     *
     * The two assertions at the end are the invariants that make the class of bug impossible
     * to reintroduce quietly: a clamp may move an alarm within the day it was going to ring
     * on, and it may never move it to before the event it is anchored to.
     */
    @Test
    fun `a ceiling on a rule that fires after midnight bounds the morning it rings`() {
        val rule = testRule(
            anchor = SolarEvent.SUNSET,
            direction = Direction.AFTER,
            offsetMinutes = 8 * 60,
            clamp = Clamp(latest = LocalTime.of(7, 0)),
        )

        val occurrence = OccurrenceEngine.occurrenceFor(rule, LocalDate.of(2026, 6, 21))!!

        assertEquals(Instant.parse("2026-06-22T05:00:00Z"), occurrence.fireAt)
        assertTrue(occurrence.clamped)
        assertEquals(
            "the clamp must not move the alarm off the day it was going to ring on",
            LocalDate.of(2026, 6, 22),
            occurrence.fireAt.atZone(OSLO).toLocalDate(),
        )
        assertTrue(
            "an alarm can never ring before the solar event it is anchored to",
            occurrence.fireAt.isAfter(occurrence.anchorAt),
        )
    }

    /**
     * The same invariant from the other side: a floor on a rule that fires the evening
     * *before* its anchor date must not drag the alarm forward into the next day.
     *
     * Sunrise minus six hours in Bergen on 21 June rings at 22:11:05 CEST on the 20th. A
     * floor of 06:00 is satisfied by 22:11, so nothing moves. Under the anchor-date rule the
     * bound was built on the 21st, 22:11 on the 20th read as "before" it, and the alarm was
     * dragged nearly eight hours forward to 06:00 on the 21st.
     */
    @Test
    fun `a floor on a rule that fires the previous evening does not drag it forward a day`() {
        val rule = testRule(
            anchor = SolarEvent.SUNRISE,
            direction = Direction.BEFORE,
            offsetMinutes = 6 * 60,
            clamp = Clamp(earliest = LocalTime.of(6, 0)),
        )

        val occurrence = OccurrenceEngine.occurrenceFor(rule, LocalDate.of(2026, 6, 21))!!

        assertEquals(Instant.parse("2026-06-20T20:11:05.149Z"), occurrence.fireAt)
        assertFalse("22:11 is not earlier than 06:00 on the day it rings", occurrence.clamped)
    }

    /** With both bounds set and contradicting each other the ceiling wins, deterministically,
     *  because it is evaluated second. The editor should never offer this, but a hand-edited
     *  or migrated row must resolve rather than crash the scheduler. */
    @Test
    fun `contradictory bounds resolve to the ceiling rather than throwing`() {
        val rule = testRule(
            anchor = SolarEvent.SUNRISE,
            direction = Direction.AT,
            clamp = Clamp(earliest = LocalTime.of(22, 0), latest = LocalTime.of(7, 0)),
        )

        val occurrence = OccurrenceEngine.occurrenceFor(rule, LocalDate.of(2026, 12, 21))!!

        assertEquals(Instant.parse("2026-12-21T06:00:00Z"), occurrence.fireAt)
        assertTrue(occurrence.clamped)
    }

    // -------------------------------------------------------------------------------------
    // The two Europe/Oslo transition days
    // -------------------------------------------------------------------------------------

    /**
     * Spring forward, 29 March 2026, when Oslo skips 02:00 to 03:00.
     *
     * Sunrise is 05:14:33.007Z, 07:14:33 CEST. Five hours earlier is 00:14:33.007Z, which
     * renders as 01:14:33 CET because it falls before the transition. Five hours of real
     * time, six hours of wall clock: that gap is the assertion, and the obvious alternative
     * implementation (render local, subtract minutes, convert back) would give 02:14:33,
     * a wall time that does not exist, and land an hour out.
     */
    @Test
    fun `spring forward - the offset spans the gap in real time not wall time`() {
        val rule = testRule(anchor = SolarEvent.SUNRISE, direction = Direction.BEFORE, offsetMinutes = 5 * 60)

        val occurrence = OccurrenceEngine.occurrenceFor(rule, LocalDate.of(2026, 3, 29))!!

        assertEquals(Instant.parse("2026-03-29T05:14:33.007Z"), occurrence.anchorAt)
        assertEquals(Instant.parse("2026-03-29T00:14:33.007Z"), occurrence.fireAt)
        assertEquals(
            "five hours of real time",
            Duration.ofHours(5),
            Duration.between(occurrence.fireAt, occurrence.anchorAt),
        )
        assertEquals(
            "but six hours of wall clock, because one of them was skipped",
            Duration.ofHours(6),
            Duration.between(
                occurrence.fireAt.atZone(OSLO).toLocalDateTime(),
                occurrence.anchorAt.atZone(OSLO).toLocalDateTime(),
            ),
        )
        assertEquals("01:14:33.007", occurrence.fireAt.atZone(OSLO).toLocalTime().toString())
    }

    /**
     * Spring forward, the clamp side: a bound naming a wall time that does not exist.
     *
     * The rule above rings at 01:14:33 CET. A floor of 02:30 on 29 March names an hour Oslo
     * skips entirely; `ZonedDateTime.of` resolves it forward by the gap to 03:30 CEST,
     * 01:30:00Z. Forward is the right direction for an alarm: a floor of "not before 02:30"
     * is satisfied by 03:30, whereas resolving backwards to 01:30 CET would breach the floor
     * the user set and ring an hour before they asked.
     */
    @Test
    fun `spring forward - a bound naming a skipped wall time resolves forward past the gap`() {
        val rule = testRule(
            anchor = SolarEvent.SUNRISE,
            direction = Direction.BEFORE,
            offsetMinutes = 5 * 60,
            clamp = Clamp(earliest = LocalTime.of(2, 30)),
        )

        assertNull(
            "the premise: 02:30 does not exist in Oslo on this date",
            OSLO.rules.getValidOffsets(LocalDateTime.of(2026, 3, 29, 2, 30)).firstOrNull(),
        )

        val occurrence = OccurrenceEngine.occurrenceFor(rule, LocalDate.of(2026, 3, 29))!!

        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), occurrence.fireAt)
        assertEquals("03:30", occurrence.fireAt.atZone(OSLO).toLocalTime().toString())
        assertTrue(occurrence.clamped)
    }

    /**
     * Autumn back, 25 October 2026, when Oslo repeats 02:00 to 03:00.
     *
     * Solar noon on the 24th is 11:24:06.192Z, 13:24:06 CEST. Fifteen hours later is
     * 02:24:06.192Z on the 25th, which renders as 03:24:06 CET because it falls after the
     * transition. Fifteen hours of real time, fourteen of wall clock. An alarm set fifteen
     * hours after an event has to wait fifteen hours; the user does not get an hour of sleep
     * confiscated because a government moved a clock.
     */
    @Test
    fun `autumn back - the offset spans the overlap in real time not wall time`() {
        val rule = testRule(anchor = SolarEvent.SOLAR_NOON, direction = Direction.AFTER, offsetMinutes = 15 * 60)

        val occurrence = OccurrenceEngine.occurrenceFor(rule, LocalDate.of(2026, 10, 24))!!

        assertEquals(Instant.parse("2026-10-24T11:24:06.192Z"), occurrence.anchorAt)
        assertEquals(Instant.parse("2026-10-25T02:24:06.192Z"), occurrence.fireAt)
        assertEquals(
            "fifteen hours of real time",
            Duration.ofHours(15),
            Duration.between(occurrence.anchorAt, occurrence.fireAt),
        )
        assertEquals(
            "but fourteen hours of wall clock, because one of them happened twice",
            Duration.ofHours(14),
            Duration.between(
                occurrence.anchorAt.atZone(OSLO).toLocalDateTime(),
                occurrence.fireAt.atZone(OSLO).toLocalDateTime(),
            ),
        )
        assertEquals("03:24:06.192", occurrence.fireAt.atZone(OSLO).toLocalTime().toString())
    }

    /**
     * Autumn back, the clamp side: a bound naming a wall time that happens twice.
     *
     * Bergen sunrise on 25 October is 06:44:44.275Z, 07:44:44 CET. A ceiling of 02:30 names
     * an hour Oslo lives through twice, at 00:30:00Z (CEST) and again at 01:30:00Z (CET). The
     * engine takes the first pass explicitly, so the alarm rings on the earlier of the two.
     * The second pass is the one nobody reports and everybody notices.
     */
    @Test
    fun `autumn back - a bound naming a repeated wall time takes the first pass`() {
        val rule = testRule(
            anchor = SolarEvent.SUNRISE,
            direction = Direction.AT,
            clamp = Clamp(latest = LocalTime.of(2, 30)),
        )

        assertEquals(
            "the premise: 02:30 happens twice in Oslo on this date",
            2,
            OSLO.rules.getValidOffsets(LocalDateTime.of(2026, 10, 25, 2, 30)).size,
        )

        val occurrence = OccurrenceEngine.occurrenceFor(rule, LocalDate.of(2026, 10, 25))!!

        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), occurrence.fireAt)
        assertTrue(occurrence.clamped)
    }

    /**
     * Neither transition day loses or duplicates an alarm.
     *
     * A daily rule produces exactly one occurrence per anchor date across both weekends, with
     * strictly increasing fire times. The failure this catches is a re-plan that arms the
     * same instant twice, or skips a day, on the two nights of the year when the arithmetic
     * is hardest and nobody is awake to notice.
     */
    @Test
    fun `both transition weekends produce one strictly increasing alarm per day`() {
        val rule = testRule(anchor = SolarEvent.SUNRISE, direction = Direction.AT)

        for (start in listOf(LocalDate.of(2026, 3, 27), LocalDate.of(2026, 10, 23))) {
            val fires = OccurrenceEngine.preview(rule, start, days = 5).map { it.fireAt }
            assertEquals("one row per day from $start", 5, fires.size)
            assertEquals("no duplicate instants from $start", 5, fires.toSet().size)
            assertEquals("strictly increasing from $start", fires.sorted(), fires)
        }
    }

    // -------------------------------------------------------------------------------------
    // preview, and its contract with nextOccurrence
    // -------------------------------------------------------------------------------------

    /**
     * The preview is thirty rows, because thirty rows is the entire mitigation for having no
     * forced clamp (owner's confirmed decision 2). If it silently returned fewer, the drift
     * it exists to expose would be the part that got cut.
     */
    @Test
    fun `preview returns thirty rows by default`() {
        val rule = testRule(anchor = SolarEvent.SUNRISE, direction = Direction.BEFORE, offsetMinutes = 30)

        val rows = OccurrenceEngine.preview(rule, LocalDate.of(2026, 11, 25))

        assertEquals(30, rows.size)
        assertEquals(LocalDate.of(2026, 11, 25), rows.first().anchorDate)
        assertEquals(LocalDate.of(2026, 12, 24), rows.last().anchorDate)
    }

    /**
     * Thirty rows even through polar night, where every one of them is a fallback.
     *
     * A preview that dropped the days with no sunrise would show a Longyearbyen user a
     * shorter list in December and tell them nothing about what their alarm was going to do.
     */
    @Test
    fun `preview returns thirty rows through polar night with every row flagged`() {
        val rule = testRule(
            anchor = SolarEvent.SUNRISE,
            direction = Direction.AT,
            latDeg = LONGYEARBYEN_LAT,
            lonDeg = LONGYEARBYEN_LON,
            placeName = "Longyearbyen",
        )

        val rows = OccurrenceEngine.preview(rule, LocalDate.of(2026, 12, 1))

        assertEquals(30, rows.size)
        assertTrue("every December row is a substituted solar noon", rows.all { it.usedFallback })
    }

    /**
     * The preview is repeated [OccurrenceEngine.occurrenceFor] and nothing else.
     *
     * This is the assertion the class KDoc demands: one implementation of the arithmetic. A
     * preview computed by a second, faster route would stop being a warning and start being a
     * lie, and this test is what makes that fail in review rather than in December.
     */
    @Test
    fun `every preview row is identical to a direct occurrence for the same date`() {
        val rule = testRule(
            anchor = SolarEvent.SUNSET,
            direction = Direction.AFTER,
            offsetMinutes = 8 * 60,
            clamp = Clamp(earliest = LocalTime.of(5, 30), latest = LocalTime.of(7, 0)),
        )
        val from = LocalDate.of(2026, 10, 10)

        val rows = OccurrenceEngine.preview(rule, from)

        for ((index, row) in rows.withIndex()) {
            assertEquals(
                "row $index must equal a direct call for its own date",
                OccurrenceEngine.occurrenceFor(rule, from.plusDays(index.toLong())),
                row,
            )
        }
    }

    /**
     * The preview's first future row is the alarm that is actually going to ring.
     *
     * Bergen, six hours before solar noon, so the fire time sits on its own anchor date and
     * the calendar and the clock agree. The two legitimately diverge for a rule whose offset
     * crosses midnight once today's row is in the past, and the KDoc is explicit that the
     * preview must not hide today to paper over it, so the agreement is asserted on a rule
     * where it is genuinely required rather than on one where it is not.
     */
    @Test
    fun `preview's first future row is the next occurrence`() {
        val rule = testRule(anchor = SolarEvent.SOLAR_NOON, direction = Direction.BEFORE, offsetMinutes = 6 * 60)
        val clock = Clock.fixed(Instant.parse("2026-06-20T22:00:00Z"), ZoneOffset.UTC)
        val now = clock.instant()
        val today = now.atZone(OSLO).toLocalDate()

        assertEquals("the fixed clock is just after midnight in Oslo", LocalDate.of(2026, 6, 21), today)

        val firstFuture = OccurrenceEngine.preview(rule, today).first { it.fireAt.isAfter(now) }
        val next = OccurrenceEngine.nextOccurrence(rule, now)

        assertEquals(next, firstFuture)
        assertEquals(Instant.parse("2026-06-21T05:41:42.271Z"), firstFuture.fireAt)
    }

    /** Zero or fewer days is an empty preview, not an exception and not one row. */
    @Test
    fun `preview of zero or fewer days is empty`() {
        val rule = testRule()

        assertTrue(OccurrenceEngine.preview(rule, LocalDate.of(2026, 6, 21), days = 0).isEmpty())
        assertTrue(OccurrenceEngine.preview(rule, LocalDate.of(2026, 6, 21), days = -5).isEmpty())
    }

    // -------------------------------------------------------------------------------------
    // nextOccurrence
    // -------------------------------------------------------------------------------------

    /**
     * Strictly after, with no grace window.
     *
     * The caller is usually a receiver that has just been woken by this very alarm and is
     * asking what to arm next. An inclusive comparison would hand back the instant that just
     * fired and re-arm it forever.
     */
    @Test
    fun `nextOccurrence is strictly after the instant it is given`() {
        val rule = testRule(anchor = SolarEvent.SOLAR_NOON, direction = Direction.BEFORE, offsetMinutes = 6 * 60)
        val justFired = Instant.parse("2026-06-21T05:41:42.271Z")

        val next = OccurrenceEngine.nextOccurrence(rule, justFired)!!

        assertEquals(Instant.parse("2026-06-22T05:41:54.457Z"), next.fireAt)
        assertEquals(LocalDate.of(2026, 6, 22), next.anchorDate)
    }

    /**
     * The walk starts in the past, so a fire time inherited from yesterday's anchor is found.
     *
     * Bergen, sunset plus eight hours: the alarm at 07:12:19 CEST on 22 June belongs to the
     * 21st. At 00:30 on the 22nd the honest answer is that row, and a walk that started at
     * today would skip it and arm the one twenty-four hours later, which is the "my alarm
     * just did not go off" report this lookback exists to prevent.
     */
    @Test
    fun `nextOccurrence finds a fire time anchored on a previous local day`() {
        val rule = testRule(anchor = SolarEvent.SUNSET, direction = Direction.AFTER, offsetMinutes = 8 * 60)
        val clock = Clock.fixed(Instant.parse("2026-06-21T22:30:00Z"), ZoneOffset.UTC)

        val next = OccurrenceEngine.nextOccurrence(rule, clock.instant())!!

        assertEquals("it is already the 22nd in Oslo", LocalDate.of(2026, 6, 22), clock.instant().atZone(OSLO).toLocalDate())
        assertEquals("but the alarm belongs to the 21st", LocalDate.of(2026, 6, 21), next.anchorDate)
        assertEquals(Instant.parse("2026-06-22T05:12:19.394Z"), next.fireAt)
    }

    /**
     * A polar-night rule still has a next occurrence, every day, via the fallback.
     *
     * Longyearbyen, sunrise anchor, asked in the middle of December. Skipping the days with
     * no sunrise would leave a Tromsø or Svalbard user with no alarm for six weeks, which is
     * a far worse failure than an alarm at midday and is exactly what owner's confirmed
     * decision 1 rules out.
     */
    @Test
    fun `nextOccurrence keeps firing through polar night via the fallback`() {
        val rule = testRule(
            anchor = SolarEvent.SUNRISE,
            direction = Direction.AT,
            latDeg = LONGYEARBYEN_LAT,
            lonDeg = LONGYEARBYEN_LON,
            placeName = "Longyearbyen",
        )
        val clock = Clock.fixed(Instant.parse("2026-12-15T12:00:00Z"), ZoneOffset.UTC)

        val next = OccurrenceEngine.nextOccurrence(rule, clock.instant())!!

        assertTrue(next.usedFallback)
        assertEquals(LocalDate.of(2026, 12, 16), next.anchorDate)
        assertEquals(Instant.parse("2026-12-16T10:54:15.471Z"), next.fireAt)
    }

    /**
     * Every occurrence in a year of a polar rule is present and ordered.
     *
     * The walk in [OccurrenceEngine.nextOccurrence] keeps a best candidate and carries on for
     * a bounded tail rather than returning the first hit, so this chains a year of calls to
     * confirm the chain never stalls, never goes backwards and never repeats an instant.
     */
    @Test
    fun `chaining nextOccurrence for a year never stalls or repeats`() {
        val rule = testRule(
            anchor = SolarEvent.SUNRISE,
            direction = Direction.BEFORE,
            offsetMinutes = 45,
            latDeg = LONGYEARBYEN_LAT,
            lonDeg = LONGYEARBYEN_LON,
            placeName = "Longyearbyen",
        )
        var cursor = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC).instant()
        val seen = mutableSetOf<Instant>()

        repeat(365) { day ->
            val next = OccurrenceEngine.nextOccurrence(rule, cursor)
            assertNotNull("the chain stalled on day $day", next)
            assertTrue("day $day went backwards", next!!.fireAt.isAfter(cursor))
            assertTrue("day $day repeated an instant", seen.add(next.fireAt))
            cursor = next.fireAt
        }

        assertEquals(365, seen.size)
    }

    /**
     * The scheduling-level consequence of the clamp fix, and the reason it is not merely
     * cosmetic.
     *
     * At 21:00 on 20 June a Bergen user with "six hours before sunrise, never before 06:00"
     * is due to be woken at 22:11 that same evening, because the floor does not bite: 22:11
     * is not earlier than 06:00 on the day it rings. The shipped engine built the floor on the
     * anchor's date instead, decided 22:11 on the 20th was "before" 06:00 on the 21st, and
     * moved the alarm seven hours and forty-nine minutes later, to 06:00 on the 21st. The
     * user slept through the evening they had asked to be woken in.
     */
    @Test
    fun `nextOccurrence returns tonight's alarm rather than a floor-shifted one tomorrow`() {
        val rule = testRule(
            anchor = SolarEvent.SUNRISE,
            direction = Direction.BEFORE,
            offsetMinutes = 6 * 60,
            clamp = Clamp(earliest = LocalTime.of(6, 0)),
        )
        val clock = Clock.fixed(Instant.parse("2026-06-20T19:00:00Z"), ZoneOffset.UTC)

        val next = OccurrenceEngine.nextOccurrence(rule, clock.instant())!!

        assertEquals("21:00 local on the 20th", "21:00", clock.instant().atZone(OSLO).toLocalTime().toString())
        assertEquals(Instant.parse("2026-06-20T20:11:05.149Z"), next.fireAt)
        assertEquals("this evening, not tomorrow morning", LocalDate.of(2026, 6, 20), next.fireAt.atZone(OSLO).toLocalDate())
        assertFalse("the floor never bit, so nothing should be flagged as clamped", next.clamped)
    }

    // -------------------------------------------------------------------------------------
    // Zones
    // -------------------------------------------------------------------------------------

    /**
     * An unusable zone identifier throws rather than quietly falling back to the device zone.
     *
     * Guessing would fire a Reykjavik alarm on Oslo time without ever saying so. `AlarmStore`
     * refuses to hand back a rule with an unusable zone and [AlarmPlanner] catches this per
     * rule, so the throw is contained; it is asserted here because it is the contract those
     * two layers are written against.
     */
    @Test
    fun `an unusable zone identifier throws rather than guessing`() {
        val rule = testRule(zoneId = "Mars/Olympus_Mons")

        assertThrows(DateTimeException::class.java) {
            OccurrenceEngine.occurrenceFor(rule, LocalDate.of(2026, 6, 21))
        }
    }

    /**
     * The rule's own zone decides everything, and the device's zone decides nothing.
     *
     * A Bergen rule computes the same instants whether the phone is in Oslo, in UTC or in
     * Auckland. The failure this catches is a stray `ZoneId.systemDefault()`, which would
     * work perfectly on the developer's machine and move a traveller's alarm the moment they
     * landed.
     */
    @Test
    fun `the device zone has no influence on any computed instant`() {
        val rule = testRule(
            anchor = SolarEvent.SUNSET,
            direction = Direction.AFTER,
            offsetMinutes = 8 * 60,
            clamp = Clamp(latest = LocalTime.of(7, 0)),
        )
        val date = LocalDate.of(2026, 6, 21)
        val original = java.util.TimeZone.getDefault()

        try {
            val results = listOf("Europe/Oslo", "UTC", "Pacific/Auckland", "America/Los_Angeles").map { zone ->
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone))
                OccurrenceEngine.occurrenceFor(rule, date)
            }
            assertEquals("every device zone must give the same answer", 1, results.toSet().size)
            assertEquals(Instant.parse("2026-06-22T05:00:00Z"), results.first()!!.fireAt)
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }
}
