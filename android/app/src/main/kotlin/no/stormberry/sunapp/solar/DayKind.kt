package no.stormberry.sunapp.solar

import java.time.LocalDate
import kotlin.math.PI

/**
 * What sort of day it is at a place: an ordinary one with a sunrise and a sunset, or one of
 * the two polar cases where there is neither.
 *
 * There is deliberately no fourth value for "the sun rises but civil twilight never ends" or
 * similar. Absence is per-event and is already carried by the nulls in [SunCalc.times]; this
 * type answers only the question the user interface asks, which is what to show where the
 * sunrise and sunset rows would otherwise go.
 */
enum class DayKind { NORMAL, MIDNIGHT_SUN, POLAR_NIGHT }

/**
 * Classifies a day as [DayKind.NORMAL], [DayKind.MIDNIGHT_SUN] or [DayKind.POLAR_NIGHT].
 *
 * **This is a transcription of `app.js` section 9.4, and it must stay one.** The web app at
 * sun.stormberry.as and this APK are the same product on two surfaces, so a day the website
 * labels "Midnight Sun" cannot be a day the APK labels "Polar Night". A change to the logic
 * below is a change to `app.js` in the same release, and vice versa. That requirement is
 * written into the comment block above `polarCondition` in `app.js` as well, so neither side
 * can be edited without the other side's author being told.
 *
 * The two decisions worth understanding, because both look wrong until they are explained:
 *
 * **1. The comparison is against -0.833 deg, not against zero.** The naive test is "is the sun
 * above the horizon at solar noon", and it is wrong at the margin. Sunrise is *defined* at
 * -0.833 deg, that being the sun's apparent radius plus standard refraction, so a sun that
 * climbs to -0.5 deg at noon has still never risen by the definition SunApp uses everywhere
 * else, and a sun that only sinks to -0.5 deg at nadir has never set. Testing against zero
 * makes the classifier disagree with the very event times printed beside it. Across the whole
 * of 2026 this changes the answer on exactly one day at Longyearbyen and on no day at all at
 * Tromso or Bergen, so it is here for correctness and parity rather than because users were
 * being misled weekly.
 *
 * **2. The third branch is a real fallback, not dead code.** It is reached when SunCalc found
 * no sunrise yet the sun's own sampled altitudes straddle the -0.833 threshold, which sounds
 * contradictory and is not: SunCalc solves the crossing with a single-pass approximation, and
 * on a day where the sun grazes the threshold by a few thousandths of a degree the solver's
 * `acos` argument slips outside [-1, 1] while a direct altitude sample stays inside. Observed
 * on 2026-04-19 at Longyearbyen, where the sun dips 0.007 deg below the threshold at nadir.
 * For such a day the honest answer is the old noon-against-zero test, because a sun that is up
 * for essentially the whole twenty-four hours should read as midnight sun. Deleting this
 * branch would leave that day classified by whichever comparison happened to fall through.
 *
 * A worked consequence, so the behaviour is not mistaken for a bug later: at 89.99 N on
 * 2026-03-19 every one of the twelve angle events is absent, noon altitude is -0.6034 deg and
 * nadir altitude is -0.8211 deg. Nadir sits *above* the -0.833 threshold, so this is midnight
 * sun, even though the sun spends the entire day below the true horizon. That is the correct
 * reading of "the sun never sets" at an event angle that includes refraction.
 */
object DayKindCalculator {

    /**
     * The sunrise/sunset altitude in radians, written exactly as `app.js` writes it so the two
     * surfaces cannot drift by a floating-point hair. [SolarEvent.SUNRISE] carries the same
     * -0.833 in degrees; the duplication is intentional, since this constant has to match the
     * JavaScript expression character for character rather than match the enum.
     */
    private const val HORIZON_RAD = -0.833 * PI / 180

    fun of(date: LocalDate, latDeg: Double, lonDeg: Double): DayKind {
        val times = SunCalc.times(date, latDeg, lonDeg)
        val sunrise = times[SolarEvent.SUNRISE]
        val sunset = times[SolarEvent.SUNSET]

        // app.js: `if (!sunriseValid || !sunsetValid)`. The two are always absent together,
        // since the rise is the set mirrored about transit, but the disjunction is transcribed
        // rather than simplified so a future reader diffing the two files sees the same shape.
        if (sunrise != null && sunset != null) return DayKind.NORMAL

        // Sampling the sun's actual altitude at transit and anti-transit, which is a different
        // question from the one the crossing solver failed to answer and never returns NaN for
        // a finite input. The elvis branches exist only for a NaN latitude or longitude, where
        // JavaScript would hand getPosition an Invalid Date and get NaN back; propagating NaN
        // here reproduces that, since every comparison below is then false and the fallback's
        // else branch answers POLAR_NIGHT exactly as the web app does.
        val maxAltitude = times[SolarEvent.SOLAR_NOON]
            ?.let { SunCalc.position(it, latDeg, lonDeg).altitudeRad } ?: Double.NaN
        val minAltitude = times[SolarEvent.NADIR]
            ?.let { SunCalc.position(it, latDeg, lonDeg).altitudeRad } ?: Double.NaN

        return when {
            // Never drops to the sunset altitude.
            minAltitude > HORIZON_RAD -> DayKind.MIDNIGHT_SUN
            // Never climbs to the sunrise altitude.
            maxAltitude < HORIZON_RAD -> DayKind.POLAR_NIGHT
            // The grazing-crossing fallback described in the class KDoc, point 2.
            else -> if (maxAltitude > 0) DayKind.MIDNIGHT_SUN else DayKind.POLAR_NIGHT
        }
    }
}
