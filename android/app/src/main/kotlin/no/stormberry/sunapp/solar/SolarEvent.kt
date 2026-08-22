package no.stormberry.sunapp.solar

/**
 * The fourteen solar events SunApp reports, and the sun altitude that defines each one.
 *
 * The names and the angles are transcribed from the `SunCalc.times` table in the bundled
 * `suncalc.js` (SunCalc 1.8.0) rather than chosen here, because the whole point of the port
 * is that the APK and sun.stormberry.as return the same instants for the same inputs. If an
 * angle is edited, both surfaces have moved apart and the golden corpus will say so.
 *
 * The angles are worth understanding rather than trusting:
 *
 *  - **-0.833 deg** (sunrise/sunset) is not zero because "the sun is at the horizon" is not
 *    the same as "the centre of the sun is at 0 deg". About 0.267 deg of that is the sun's
 *    own apparent radius, so the upper limb touches the horizon while the centre is still
 *    below it, and about 0.566 deg is standard atmospheric refraction bending the image up.
 *    This is the reason the polar classifier compares against -0.833 rather than against 0.
 *  - **-0.3 deg** (sunrise end / sunset start) is the moment the whole disc has cleared the
 *    horizon, so the interval from SUNRISE to SUNRISE_END is the disc's own crossing time.
 *    It follows that SUNRISE can occur on a day where SUNRISE_END never does, and any code
 *    treating one as implying the other computes a negative interval at high latitudes.
 *  - **-6, -12 and -18 deg** are civil, nautical and astronomical twilight, in that order.
 *  - **+6 deg** is the conventional golden-hour boundary, and it is the only positive angle
 *    in the table. Near the Arctic Circle at midwinter the golden-hour keys can be the only
 *    absent ones, because the sun rises but never climbs that high.
 *
 * [altitudeDeg] is null for [SOLAR_NOON] and [NADIR] because those two are not defined by
 * an altitude at all. They are the transit and anti-transit of the sun, computed directly
 * rather than by solving for a horizon crossing, which is exactly why they are the only two
 * events that can never be absent.
 */
enum class SolarEvent(val altitudeDeg: Double?) {
    SUNRISE(-0.833),
    SUNSET(-0.833),
    SUNRISE_END(-0.3),
    SUNSET_START(-0.3),
    DAWN(-6.0),
    DUSK(-6.0),
    NAUTICAL_DAWN(-12.0),
    NAUTICAL_DUSK(-12.0),
    NIGHT_END(-18.0),
    NIGHT(-18.0),
    GOLDEN_HOUR_END(6.0),
    GOLDEN_HOUR(6.0),
    SOLAR_NOON(null),
    NADIR(null),
}
