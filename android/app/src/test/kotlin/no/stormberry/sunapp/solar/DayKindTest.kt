package no.stormberry.sunapp.solar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.PI

/**
 * Pins [DayKindCalculator] to the classifier in `app.js` section 9.4.
 *
 * These are not merely regression tests for the port. They are the cross-surface contract:
 * every case below is a day where the website and the APK are required to print the same
 * label, and the boundary case is a day where a plausible simplification of the classifier
 * would make them disagree.
 */
class DayKindTest {

    /**
     * The grazing-crossing fallback: 2026-04-19 at Longyearbyen.
     *
     * This is the one day in 2026 where the corrected classifier and the old noon-against-zero
     * test could part company, and the reason `app.js` has a third branch at all. The
     * assertions walk the whole mechanism rather than just checking the answer, because the
     * answer alone would still pass if someone deleted the fallback and got MIDNIGHT_SUN by
     * accident from a different branch.
     */
    @Test
    fun `Longyearbyen on the 2026-04-19 grazing day reaches the fallback and reads as midnight sun`() {
        val date = LocalDate.of(2026, 4, 19)
        val times = SunCalc.times(date, LONGYEARBYEN_LAT, LONGYEARBYEN_LON)

        // The solver could not find the crossing, which is what sends the classifier down the
        // altitude-sampling path in the first place.
        assertNull("SunCalc should fail to solve the sunrise crossing on this day", times[SolarEvent.SUNRISE])
        assertNull(times[SolarEvent.SUNSET])

        val maxAltitude = SunCalc.position(times[SolarEvent.SOLAR_NOON]!!, LONGYEARBYEN_LAT, LONGYEARBYEN_LON).altitudeRad
        val minAltitude = SunCalc.position(times[SolarEvent.NADIR]!!, LONGYEARBYEN_LAT, LONGYEARBYEN_LON).altitudeRad

        // Neither of the first two branches fires: the sun dips a few thousandths of a degree
        // below the sunset altitude at nadir, and sits far above it at noon.
        assertTrue("nadir altitude should be just below the horizon threshold", minAltitude < HORIZON_RAD)
        assertTrue("but only just", minAltitude > HORIZON_RAD - 0.001)
        assertTrue("noon altitude should be well above the threshold", maxAltitude > HORIZON_RAD)

        assertEquals(
            DayKind.MIDNIGHT_SUN,
            DayKindCalculator.of(date, LONGYEARBYEN_LAT, LONGYEARBYEN_LON),
        )
    }

    @Test
    fun `Longyearbyen at midwinter is polar night`() {
        assertEquals(
            DayKind.POLAR_NIGHT,
            DayKindCalculator.of(LocalDate.of(2026, 12, 21), LONGYEARBYEN_LAT, LONGYEARBYEN_LON),
        )
    }

    @Test
    fun `Longyearbyen at midsummer is midnight sun`() {
        assertEquals(
            DayKind.MIDNIGHT_SUN,
            DayKindCalculator.of(LocalDate.of(2026, 6, 21), LONGYEARBYEN_LAT, LONGYEARBYEN_LON),
        )
    }

    /**
     * The polar seasons are contiguous runs, not a scatter.
     *
     * A classifier with an inverted comparison or a stray absolute value still gets the two
     * solstices right and produces isolated wrong days in between. Counting the transitions
     * across a whole year is the cheap way to catch that: Longyearbyen should have exactly
     * four boundaries in 2026, polar night to normal, normal to midnight sun, and back.
     */
    @Test
    fun `Longyearbyen moves between the three kinds exactly four times in 2026`() {
        val kinds = daysOf(2026).map { DayKindCalculator.of(it, LONGYEARBYEN_LAT, LONGYEARBYEN_LON) }
        val transitions = kinds.zipWithNext().count { (a, b) -> a != b }
        assertEquals("Polar seasons must be contiguous runs: $kinds", 4, transitions)
        assertEquals(DayKind.POLAR_NIGHT, kinds.first())
        assertEquals(DayKind.POLAR_NIGHT, kinds.last())
        assertTrue(kinds.contains(DayKind.MIDNIGHT_SUN))
        assertTrue(kinds.contains(DayKind.NORMAL))
    }

    /**
     * Bergen never has a polar day, which is the negative control the rest of the suite needs.
     *
     * At 60.4 N the sun is never close to either threshold, so any classifier that has become
     * unconditionally polar, or that reads NaN as polar, fails here on 365 days at once.
     */
    @Test
    fun `Bergen is a normal day on every day of 2026`() {
        val odd = daysOf(2026).filter { DayKindCalculator.of(it, BERGEN_LAT, BERGEN_LON) != DayKind.NORMAL }
        assertTrue("Bergen should never be polar, but these days classified otherwise: $odd", odd.isEmpty())
    }

    /**
     * Tromso on 2026-01-15, from golden case G10.
     *
     * Deep inside the Arctic Circle in January and still NORMAL, because the sun clears the
     * -0.833 threshold for about twenty-four minutes. Latitude and season alone do not decide
     * this, and a classifier that shortcuts on either is wrong here.
     */
    @Test
    fun `Tromso in mid-January is a normal day despite the latitude`() {
        assertEquals(
            DayKind.NORMAL,
            DayKindCalculator.of(LocalDate.of(2026, 1, 15), 69.6492, 18.9553),
        )
    }

    /**
     * Golden case G14: 89.99 N on 2026-03-19, where the classifier and intuition part company.
     *
     * Every angle event is absent and the sun spends the whole day below the true horizon, yet
     * nadir altitude is -0.8211 deg, which is *above* the -0.833 deg sunset altitude. By the
     * definition SunApp uses for sunset, the sun therefore never sets, so this is midnight sun.
     *
     * This is exactly the case the old noon-against-zero test got wrong, calling it polar
     * night. It is the reason `app.js` was changed rather than the reason the port diverges.
     */
    @Test
    fun `the near-pole equinox day reads as midnight sun rather than polar night`() {
        val date = LocalDate.of(2026, 3, 19)
        val times = SunCalc.times(date, 89.99, 0.0)
        val maxAltitude = SunCalc.position(times[SolarEvent.SOLAR_NOON]!!, 89.99, 0.0).altitudeRad
        val minAltitude = SunCalc.position(times[SolarEvent.NADIR]!!, 89.99, 0.0).altitudeRad

        assertTrue("noon is below the true horizon", maxAltitude < 0)
        assertTrue("yet nadir is above the sunset altitude", minAltitude > HORIZON_RAD)
        assertEquals(DayKind.MIDNIGHT_SUN, DayKindCalculator.of(date, 89.99, 0.0))
    }

    private fun daysOf(year: Int): List<LocalDate> {
        val start = LocalDate.of(year, 1, 1)
        return (0 until LocalDate.of(year, 12, 31).dayOfYear).map { start.plusDays(it.toLong()) }
    }

    private companion object {
        const val LONGYEARBYEN_LAT = 78.2233
        const val LONGYEARBYEN_LON = 15.6469
        const val BERGEN_LAT = 60.3913
        const val BERGEN_LON = 5.3221

        /** The same threshold DayKindCalculator uses, restated so a change there fails here. */
        const val HORIZON_RAD = -0.833 * PI / 180
    }
}
