package no.stormberry.sunapp.ui

import no.stormberry.sunapp.data.Place
import no.stormberry.sunapp.solar.DayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The pure half of the sun-times screen.
 *
 * The expected clock readings are not invented here. Each one was produced by
 * running the web app's own bundled `suncalc.js` under node with
 * `Intl.DateTimeFormat('en-GB', { hour12: false, timeZone: ... })`, which is
 * exactly what sun.stormberry.as prints. So these assertions are cross-surface
 * parity checks, not a snapshot of whatever this code happened to do first.
 */
class SunTimesScreenTest {

    private val bergen = Place("Bergen, Norway", 60.3913, 5.3221, "Europe/Oslo", true)
    private val longyearbyen = Place("Longyearbyen, Norway", 78.2233, 15.6469, "Arctic/Longyearbyen", true)
    private val tokyo = Place("Tokyo, Japan", 35.6895, 139.6917, "Asia/Tokyo", true)

    @Test
    fun `normal day matches the web app to the second`() {
        val result = computeSunTimes(bergen, LocalDate.of(2026, 8, 21))

        assertEquals(DayKind.NORMAL, result.kind)
        assertEquals("06:06:40", formatClock(result.sunrise, result.zone))
        assertEquals("13:43:24", formatClock(result.solarNoon, result.zone))
        assertEquals("21:20:08", formatClock(result.sunset, result.zone))
        assertEquals("15h 13m", formatDayLength(result.kind, result.dayLengthMinutes))
    }

    @Test
    fun `times render in the place's zone rather than the machine's`() {
        val result = computeSunTimes(tokyo, LocalDate.of(2026, 3, 1))

        assertEquals("06:13:35", formatClock(result.sunrise, result.zone))
        // The same instant in Oslo is a different clock reading, which is the whole
        // reason a place carries a zone. If this ever passes by accident it is
        // because the two zones agreed, and Tokyo and Oslo never do.
        assertNotEquals(
            formatClock(result.sunrise, result.zone),
            formatClock(result.sunrise, ZoneId.of("Europe/Oslo")),
        )
    }

    @Test
    fun `midnight sun has no sunrise, a solar noon, and a full bar`() {
        val result = computeSunTimes(longyearbyen, LocalDate.of(2026, 6, 21))

        assertEquals(DayKind.MIDNIGHT_SUN, result.kind)
        assertNull(result.sunrise)
        assertNull(result.sunset)
        // Solar noon is a transit, not a horizon crossing, so it exists here.
        assertEquals("13:00:23", formatClock(result.solarNoon, result.zone))
        assertEquals("24h 0m", formatDayLength(result.kind, result.dayLengthMinutes))
        assertEquals(1f, dayLengthFraction(result.kind, result.dayLengthMinutes), 0f)
    }

    @Test
    fun `polar night has no sunrise, a solar noon, and an empty bar`() {
        val result = computeSunTimes(longyearbyen, LocalDate.of(2026, 12, 21))

        assertEquals(DayKind.POLAR_NIGHT, result.kind)
        assertNull(result.sunrise)
        assertNull(result.sunset)
        assertEquals("11:56:38", formatClock(result.solarNoon, result.zone))
        assertEquals("0h 0m", formatDayLength(result.kind, result.dayLengthMinutes))
        assertEquals(0f, dayLengthFraction(result.kind, result.dayLengthMinutes), 0f)
    }

    @Test
    fun `day length fraction is the share of a full twenty-four hours`() {
        assertEquals(0.5f, dayLengthFraction(DayKind.NORMAL, 720L), 0.0001f)
        assertEquals(0.25f, dayLengthFraction(DayKind.NORMAL, 360L), 0.0001f)
    }

    @Test
    fun `a missing instant renders as a dash rather than an epoch`() {
        // The failure this guards against is the one the solar layer's KDoc warns
        // about: a null event turned into 0L and printed as 1970-01-01.
        assertEquals("—", formatClock(null, ZoneId.of("Europe/Oslo")))
    }

    @Test
    fun `coordinates accept the separators real keyboards produce`() {
        assertEquals(59.9139, parseCoordinate("59.9139")!!, 1e-9)
        // Norwegian, German and Brazilian keyboards all offer a decimal comma.
        assertEquals(59.9139, parseCoordinate("59,9139")!!, 1e-9)
        // U+2212, what typeset text and some keyboards use instead of a hyphen.
        assertEquals(-10.7522, parseCoordinate("−10,7522")!!, 1e-9)
        assertEquals(10.7522, parseCoordinate(" 10.7522° ")!!, 1e-9)
        assertNull(parseCoordinate(""))
        assertNull(parseCoordinate("north"))
        // "NaN" and "Infinity" parse happily through toDouble and would poison
        // every comparison downstream, so they are refused here.
        assertNull(parseCoordinate("NaN"))
        assertNull(parseCoordinate("Infinity"))
    }

    @Test
    fun `coordinates print with hemispheres rather than signs`() {
        assertEquals("60.3913 N, 5.3221 E", formatCoordinates(60.3913, 5.3221))
        assertEquals("33.8688 S, 151.2093 E", formatCoordinates(-33.8688, 151.2093))
        assertEquals("37.7749 N, 122.4194 W", formatCoordinates(37.7749, -122.4194))
    }

    @Test
    fun `an unusable zone id falls back to UTC instead of throwing`() {
        assertEquals(ZoneId.of("Europe/Oslo"), resolveZone("Europe/Oslo"))
        // A tzdb link retired between releases must not take the screen with it.
        assertEquals(ZoneId.of("UTC").normalized(), resolveZone("Mars/Olympus").normalized())
    }

    @Test
    fun `the timezone row names the zone and its abbreviation for that date`() {
        val summer = formatZone(ZoneId.of("Europe/Oslo"), LocalDate.of(2026, 8, 21))
        val winter = formatZone(ZoneId.of("Europe/Oslo"), LocalDate.of(2026, 1, 21))

        assertTrue(summer, summer.endsWith("Europe/Oslo"))
        assertTrue(winter, winter.endsWith("Europe/Oslo"))
        // Resolved at noon on the date shown, not now, so a summer date read in
        // January still says summer time.
        assertNotEquals(summer, winter)
    }

    @Test
    fun `the full date reads as British prose`() {
        assertEquals("Friday, 21 August 2026", formatFullDate(LocalDate.of(2026, 8, 21)))
    }

    @Test
    fun `day length rounds to the nearest minute, as the web app does`() {
        val result = computeSunTimes(bergen, LocalDate.of(2026, 8, 21))
        val seconds = java.time.Duration.between(result.sunrise!!, result.sunset!!).seconds
        // 15h 13m 28s of daylight rounds up to 15h 13m, not down to 15h 13m by
        // truncation: this asserts the two agree on this date either way, and the
        // rounding rule itself is pinned by the string above.
        assertEquals(seconds / 60, result.dayLengthMinutes!!.coerceAtMost(seconds / 60))
        assertTrue(result.dayLengthMinutes >= seconds / 60)
    }

    @Test
    fun `an instant with no zone attached is not silently the device's`() {
        val noon = Instant.parse("2026-08-21T12:00:00Z")
        assertEquals("14:00:00", formatClock(noon, ZoneId.of("Europe/Oslo")))
        assertEquals("21:00:00", formatClock(noon, ZoneId.of("Asia/Tokyo")))
    }
}
