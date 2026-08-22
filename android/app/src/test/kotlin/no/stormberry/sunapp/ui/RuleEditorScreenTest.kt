package no.stormberry.sunapp.ui

import no.stormberry.sunapp.alarm.BERGEN_LAT
import no.stormberry.sunapp.alarm.BERGEN_LON
import no.stormberry.sunapp.alarm.LONGYEARBYEN_LAT
import no.stormberry.sunapp.alarm.LONGYEARBYEN_LON
import no.stormberry.sunapp.alarm.OccurrenceEngine
import no.stormberry.sunapp.alarm.model.Clamp
import no.stormberry.sunapp.alarm.model.Direction
import no.stormberry.sunapp.alarm.testRule
import no.stormberry.sunapp.data.Place
import no.stormberry.sunapp.solar.SolarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The pure half of the rule editor: the draft-to-rule mapping, the offset parsing, the
 * sentences, and the reduction that turns thirty occurrences into the one number the screen
 * is built around.
 *
 * The solar figures below are not snapshots of this code. Each was produced by running the
 * web app's own bundled `suncalc.js` under node with
 * `Intl.DateTimeFormat('en-GB', { hour12: false, timeZone: 'Europe/Oslo' })`, which is what
 * sun.stormberry.as prints, so a disagreement here is a real disagreement between the two
 * surfaces rather than a stale expectation.
 */
class RuleEditorScreenTest {

    private val oslo: ZoneId = ZoneId.of("Europe/Oslo")
    private val bergen = Place("Bergen, Norway", BERGEN_LAT, BERGEN_LON, "Europe/Oslo", true)

    /* -------------------------------------------------------------- *
     * Owner's confirmed decision 5
     * -------------------------------------------------------------- */

    @Test
    fun `the picker offers exactly three anchors, in solar order`() {
        assertEquals(
            listOf(SolarEvent.SUNRISE, SolarEvent.SOLAR_NOON, SolarEvent.SUNSET),
            EDITOR_ANCHORS,
        )
    }

    @Test
    fun `the preview window is thirty days`() {
        assertEquals(30, PREVIEW_DAYS)
    }

    /* -------------------------------------------------------------- *
     * Offsets
     * -------------------------------------------------------------- */

    @Test
    fun `offset boxes combine into minutes`() {
        assertEquals(90, parseOffsetMinutes("1", "30"))
        assertEquals(0, parseOffsetMinutes("0", "0"))
        assertEquals(45, parseOffsetMinutes("", "45"))
        assertEquals(120, parseOffsetMinutes("2", ""))
    }

    @Test
    fun `an offset that is not a plain number is rejected rather than guessed at`() {
        assertNull(parseOffsetMinutes("x", "0"))
        // The sign belongs to Direction. A minus typed here would mean the opposite of the
        // button the user pressed, so it is refused rather than folded through abs.
        assertNull(parseOffsetMinutes("-1", "0"))
        assertNull(parseOffsetMinutes("0", "60"))
        assertNull(parseOffsetMinutes("37", "0"))
    }

    @Test
    fun `offsets read as English`() {
        assertEquals("0 min", formatOffset(0))
        assertEquals("45 min", formatOffset(45))
        assertEquals("1 h", formatOffset(60))
        assertEquals("1 h 30 min", formatOffset(90))
    }

    @Test
    fun `timing sentences match the three-way control`() {
        assertEquals("At sunrise", describeTiming(SolarEvent.SUNRISE, Direction.AT, 0))
        assertEquals("30 min before sunrise", describeTiming(SolarEvent.SUNRISE, Direction.BEFORE, 30))
        assertEquals("1 h 15 min after sunset", describeTiming(SolarEvent.SUNSET, Direction.AFTER, 75))
        // A magnitude of zero is "at", whichever side is selected: "0 min after sunset" is
        // not a thing anybody means.
        assertEquals("At sunset", describeTiming(SolarEvent.SUNSET, Direction.AFTER, 0))
        assertEquals("At solar noon", describeTiming(SolarEvent.SOLAR_NOON, Direction.AT, 0))
    }

    @Test
    fun `an unnamed rule is labelled from what it does and where`() {
        assertEquals(
            "30 min before sunrise, Bergen",
            defaultLabel(SolarEvent.SUNRISE, Direction.BEFORE, 30, "Bergen, Norway"),
        )
        assertEquals("At sunset", defaultLabel(SolarEvent.SUNSET, Direction.AT, 0, ""))
    }

    /* -------------------------------------------------------------- *
     * Draft to rule and back
     * -------------------------------------------------------------- */

    @Test
    fun `a draft without a place cannot be saved`() {
        assertNull(RuleDraft(id = "a").toRule())
    }

    @Test
    fun `a draft becomes the rule the engine will schedule`() {
        val rule = RuleDraft(
            id = "a",
            label = "  Wake  ",
            anchor = SolarEvent.SUNSET,
            direction = Direction.BEFORE,
            offsetHours = "1",
            offsetMinutes = "5",
            place = bergen,
        ).toRule()

        assertNotNull(rule)
        assertEquals("Wake", rule!!.label)
        assertEquals(SolarEvent.SUNSET, rule.anchor)
        assertEquals(Direction.BEFORE, rule.direction)
        assertEquals(65, rule.offsetMinutes)
        assertEquals(-65, rule.signedOffsetMinutes)
        assertEquals("Europe/Oslo", rule.zoneId)
        assertEquals("Bergen, Norway", rule.placeName)
        // Owner's confirmed decision 2: no clamp unless one was asked for.
        assertNull(rule.clamp)
    }

    @Test
    fun `an open but empty clamp section is not a clamp`() {
        val rule = RuleDraft(id = "a", place = bergen, clampOpen = true).toRule()
        assertNull(rule!!.clamp)
    }

    @Test
    fun `crossed clamp bounds block the save rather than being silently reordered`() {
        // The engine resolves this deterministically (the ceiling is applied last and wins),
        // but a rule that means the opposite of what its two boxes say must not be creatable.
        val draft = RuleDraft(
            id = "a",
            place = bergen,
            earliest = LocalTime.of(9, 0),
            latest = LocalTime.of(7, 0),
        )
        assertNull(draft.toRule())
    }

    @Test
    fun `opening a stored rule and saving it again changes nothing`() {
        val original = testRule(
            id = "keep-me",
            label = "Sunset walk",
            anchor = SolarEvent.SUNSET,
            direction = Direction.AFTER,
            offsetMinutes = 95,
            placeName = "Bergen, Norway",
            clamp = Clamp(earliest = LocalTime.of(6, 30), latest = LocalTime.of(8, 0)),
            vibrate = false,
        )
        assertEquals(original, original.toDraft().toRule())
    }

    @Test
    fun `an anchor the picker does not offer survives being opened`() {
        // Eleven of the fourteen events are not on the picker. Opening a rule that uses one
        // must not quietly rewrite it to sunrise: that would be data loss disguised as a
        // simplification.
        val original = testRule(anchor = SolarEvent.NAUTICAL_DAWN, placeName = "Bergen, Norway")
        assertEquals(SolarEvent.NAUTICAL_DAWN, original.toDraft().anchor)
        assertEquals(original, original.toDraft().toRule())
    }

    /* -------------------------------------------------------------- *
     * The preview summary, which is the whole mitigation for having no clamp
     * -------------------------------------------------------------- */

    @Test
    fun `a Bergen sunrise rule drifts more than an hour in a month`() {
        val rule = testRule(anchor = SolarEvent.SUNRISE, direction = Direction.AT)
        val rows = OccurrenceEngine.preview(rule, LocalDate.of(2026, 8, 21), PREVIEW_DAYS)
        val summary = summarisePreview(rows, oslo)

        // suncalc.js under node: 2026-08-21 sunrise 06:06 Europe/Oslo, 2026-09-19 sunrise
        // 07:16. Seventy minutes of drift inside one preview window is exactly the surprise
        // the owner's decision 2 says the preview exists to remove.
        assertEquals(30, summary.rows.size)
        assertEquals(LocalDate.of(2026, 8, 21), summary.earliest!!.anchorDate)
        assertEquals(LocalDate.of(2026, 9, 19), summary.latest!!.anchorDate)
        assertEquals(70, summary.spreadMinutes)
        assertEquals(0, summary.fallbackDays)
        assertEquals(0, summary.clampedDays)
    }

    @Test
    fun `a window that crosses midnight measures as minutes, not as most of a day`() {
        // Sunset plus three hours in Bergen rings at 00:20 on 21 August and at 22:51 on 19
        // September, so the fire time crosses back over midnight inside the window. Compared
        // as absolute minutes past midnight that is a spread of 22 hours 31 minutes, which
        // would make the headline number of the whole screen nonsense.
        val rule = testRule(
            anchor = SolarEvent.SUNSET,
            direction = Direction.AFTER,
            offsetMinutes = 180,
        )
        val rows = OccurrenceEngine.preview(rule, LocalDate.of(2026, 8, 21), PREVIEW_DAYS)
        val summary = summarisePreview(rows, oslo)

        assertEquals(89, summary.spreadMinutes)
        assertEquals("00:20", formatFireClock(rows.first().fireAt, oslo))
        assertEquals("22:51", formatFireClock(rows.last().fireAt, oslo))
    }

    @Test
    fun `polar night is counted, not hidden`() {
        // Longyearbyen has no sunrise on any day in December, so every row in the window
        // falls back to solar noon and the editor has thirty warnings to show rather than
        // thirty missing alarms.
        val rule = testRule(
            anchor = SolarEvent.SUNRISE,
            latDeg = LONGYEARBYEN_LAT,
            lonDeg = LONGYEARBYEN_LON,
            placeName = "Longyearbyen, Norway",
        )
        val summary = summarisePreview(
            OccurrenceEngine.preview(rule, LocalDate.of(2026, 12, 1), PREVIEW_DAYS),
            oslo,
        )
        assertEquals(30, summary.fallbackDays)
        assertTrue(summary.rows.all { it.usedFallback })
    }

    @Test
    fun `an empty preview reduces to nothing rather than throwing`() {
        val summary = summarisePreview(emptyList(), oslo)
        assertNull(summary.earliest)
        assertNull(summary.latest)
        assertEquals(0, summary.spreadMinutes)
    }

    @Test
    fun `the summary carries the engine's own rows through untouched`() {
        // The preview must never be a second implementation of the occurrence arithmetic.
        // Identity, not equality: these are the objects OccurrenceEngine produced.
        val rows = OccurrenceEngine.preview(testRule(), LocalDate.of(2026, 8, 21), PREVIEW_DAYS)
        assertSame(rows, summarisePreview(rows, oslo).rows)
    }

    @Test
    fun `differences fold into plus or minus twelve hours`() {
        assertEquals(0, wrapHalfDay(0))
        assertEquals(10, wrapHalfDay(10))
        assertEquals(-10, wrapHalfDay(1430))
        assertEquals(-10, wrapHalfDay(-10))
        assertEquals(719, wrapHalfDay(719))
        assertEquals(-720, wrapHalfDay(720))
    }

    /* -------------------------------------------------------------- *
     * The AlarmClock SET_ALARM bridge
     * -------------------------------------------------------------- */

    @Test
    fun `matching a requested wall time lands on it today and then lets go`() {
        val draft = RuleDraft(id = "a", anchor = SolarEvent.SUNRISE, place = bergen)
        val matched = matchRequestedTime(draft, LocalTime.of(7, 0))
        val rule = matched.toRule()!!

        val today = LocalDate.now(oslo)
        val fired = OccurrenceEngine.occurrenceFor(rule, today)!!.fireAt.atZone(oslo).toLocalTime()
        // Offsets are whole minutes and sunrise has seconds, so a minute of slack is the
        // exact resolution of the thing being asserted.
        assertTrue(
            "fired at $fired",
            Duration.between(LocalTime.of(7, 0), fired).abs().toMinutes() <= 1,
        )
        // Consumed: the offer must not still be on screen after it has been taken.
        assertNull(matched.requestedTime)
    }

    @Test
    fun `there is nothing to match against without a place`() {
        val draft = RuleDraft(id = "a", requestedTime = LocalTime.of(7, 0))
        assertEquals(draft, matchRequestedTime(draft, LocalTime.of(7, 0)))
    }
}
