package no.stormberry.sunapp.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * How the user told SunApp where they are.
 *
 * There is deliberately no third value for device location. The web app has one
 * and this app cannot: reading the device position needs ACCESS_COARSE_LOCATION,
 * and no build of this app has ever asked for a location permission. See the notice composable in
 * `ui/components/Chrome.kt`, which says the same thing to the user rather than
 * leaving them to wonder why a button they remember is missing.
 */
enum class InputMode { CITY, COORDINATES }

/**
 * A resolved place: everything the sun-times screen needs, and nothing that
 * would make it go looking for more.
 *
 * The timezone is carried as a plain IANA id rather than a `ZoneId` because this
 * type is written to SharedPreferences, and because an id that no longer resolves
 * (a tzdb link retired between releases) must not throw on the way out of the
 * store. Resolution happens once, at the point of use, where it can fall back.
 *
 * @property label what the user sees: "Bergen, Norway" for a catalogue city, or
 *   the formatted coordinates for a typed pair.
 * @property fromCatalogue true when [zoneId] came with the city, false when it
 *   was inferred from the nearest catalogue city. Only used to decide whether the
 *   coordinate fields should be prefilled on the next launch.
 */
data class Place(
    val label: String,
    val latDeg: Double,
    val lonDeg: Double,
    val zoneId: String,
    val fromCatalogue: Boolean,
)

/**
 * The one thing SunApp remembers between launches: where you last looked.
 *
 * SharedPreferences lives in the app's private data directory, so this needs no
 * permission and never leaves the device. The date is deliberately NOT persisted;
 * opening the app tomorrow should show tomorrow, not the day you last happened to
 * be curious about.
 *
 * Coordinates are stored as strings rather than through `putFloat`, because a
 * float carries about seven significant digits and the catalogue's coordinates
 * have up to eight (a longitude such as -122.4194 plus its sign). Rounding a
 * stored place on every launch would move it by tens of metres for no reason.
 * `Double.toString` and `String.toDoubleOrNull` are both locale-independent, so
 * a Norwegian device does not write a value a British one cannot read back.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var mode: InputMode
        get() {
            val stored = prefs.getString(KEY_MODE, null) ?: return InputMode.CITY
            // Read by name with a fallback rather than by ordinal: renaming or
            // reordering a constant in a later version must not crash, or worse
            // silently mean something else, on someone's existing install.
            return InputMode.entries.firstOrNull { it.name == stored } ?: InputMode.CITY
        }
        set(value) = prefs.edit { putString(KEY_MODE, value.name) }

    /**
     * The last place the user looked at, or null on a fresh install.
     *
     * A partially written entry (label present, latitude unparseable) reads back
     * as null rather than as a place at 0,0 in the Gulf of Guinea, which is what
     * a defaulting `getFloat` would have produced.
     */
    var place: Place?
        get() {
            val label = prefs.getString(KEY_LABEL, null) ?: return null
            val lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
            val lon = prefs.getString(KEY_LON, null)?.toDoubleOrNull() ?: return null
            val zone = prefs.getString(KEY_ZONE, null) ?: return null
            return Place(
                label = label,
                latDeg = lat,
                lonDeg = lon,
                zoneId = zone,
                fromCatalogue = prefs.getBoolean(KEY_FROM_CATALOGUE, true),
            )
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_LABEL)
                remove(KEY_LAT)
                remove(KEY_LON)
                remove(KEY_ZONE)
                remove(KEY_FROM_CATALOGUE)
            } else {
                putString(KEY_LABEL, value.label)
                putString(KEY_LAT, value.latDeg.toString())
                putString(KEY_LON, value.lonDeg.toString())
                putString(KEY_ZONE, value.zoneId)
                putBoolean(KEY_FROM_CATALOGUE, value.fromCatalogue)
            }
        }

    /**
     * Which revision of the first-run notice this install has already seen, 0 on a
     * fresh install.
     *
     * An integer rather than a boolean so the notice can be shown again if its text
     * ever changes materially; the comparison itself lives in
     * `ui/FirstRunNotice.kt`, next to the copy it is about, and the constant that
     * gets written here is that file's version rather than a literal.
     *
     * Stored in the same file as the place, and therefore covered by the same backup
     * rules. Carrying it across a restore is deliberate: a user restoring their phone
     * has read this notice already, and showing it again on the new device would be
     * the app forgetting rather than the user needing telling twice.
     */
    var firstRunNoticeSeenVersion: Int
        get() = prefs.getInt(KEY_NOTICE_VERSION, 0)
        set(value) = prefs.edit { putInt(KEY_NOTICE_VERSION, value) }

    companion object {
        /**
         * The preference file name, which is also the filename inside the app's
         * `shared_prefs` directory. `res/xml/backup_rules.xml` and
         * `res/xml/data_extraction_rules.xml` name the same file, so changing it
         * here without changing them there silently stops the backup working.
         */
        const val PREFS_NAME = "sun_app"

        private const val KEY_MODE = "input_mode"
        private const val KEY_LABEL = "place_label"
        private const val KEY_LAT = "place_lat"
        private const val KEY_LON = "place_lon"
        private const val KEY_ZONE = "place_zone"
        private const val KEY_FROM_CATALOGUE = "place_from_catalogue"
        private const val KEY_NOTICE_VERSION = "first_run_notice_seen_version"
    }
}
