package no.stormberry.sunapp.data

import android.content.Context
import androidx.core.content.edit
import no.stormberry.sunapp.alarm.model.AlarmRule
import no.stormberry.sunapp.alarm.model.Clamp
import no.stormberry.sunapp.alarm.model.Direction
import no.stormberry.sunapp.solar.SolarEvent
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZoneId

/**
 * The single byte of platform that persisting alarm rules requires: somewhere to put one
 * string and get it back.
 *
 * [AlarmStore] does all of the work (shape, defaults, forward compatibility, the rules for
 * what to do with a row it cannot fully understand) against this interface, which means the
 * whole of that work is exercised on the JVM with an in-memory implementation instead of
 * needing a device or Robolectric. The Android implementation below is four lines and
 * contains no decisions.
 */
interface AlarmRuleFile {
    /** The stored document, or null if nothing has ever been written. */
    fun read(): String?

    /** Replace the stored document. */
    fun write(text: String)
}

/**
 * [AlarmRuleFile] over `SharedPreferences`, which is the right storage for this: a few
 * kilobytes, written when the user edits a rule, read once at process start and again in a
 * boot receiver. A database would be a schema, a migration and a dependency for a list that
 * will realistically never exceed a dozen rows.
 *
 * Credential-encrypted storage, deliberately. The rule set is not readable before first
 * unlock, and it does not need to be: the alarms that must survive an unattended reboot are
 * mirrored separately into device-protected storage with just enough per-alarm detail to
 * ring, precisely so that the full rule set can stay behind the lock screen.
 */
class SharedPreferencesRuleFile(context: Context) : AlarmRuleFile {
    private val prefs = context.applicationContext
        .getSharedPreferences(AlarmStore.PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(AlarmStore.KEY_RULES, null)

    override fun write(text: String) = prefs.edit { putString(AlarmStore.KEY_RULES, text) }
}

/**
 * Reads and writes the user's alarm rules as JSON, through platform `org.json`.
 *
 * `org.json` rather than a serialisation library because it is already in the Android
 * framework: no dependency, no code generation, no reflection configuration to remember when
 * R8 next changes its defaults, and nothing added to an APK whose whole pitch is that it is
 * small and does nothing behind your back. The cost is that the mapping is written by hand,
 * which for thirteen fields is a fair trade and has the side benefit that the tolerance rules
 * below are explicit rather than a library's defaults.
 *
 * ### Forward compatibility, and why it is a hard requirement here
 *
 * A user can install a newer SunApp, create a rule using a field this version has never heard
 * of, and then roll back. If reading that row threw, every alarm on the device would be gone.
 * So:
 *
 *  - **Unknown keys are ignored**, at the document level and inside each rule. `org.json`
 *    gives this for free and no code should ever start rejecting them.
 *  - **Missing optional keys take documented defaults**, which is not a degradation: a rule
 *    written before clamps existed simply has no clamp.
 *  - **A key that is present but unreadable degrades the rule**: the value is replaced by a
 *    safe default *and the rule is switched off*. This is the interesting rule and it is
 *    uniform. An unrecognised anchor name, an offset that is not a number, a clamp bound that
 *    is not a time, a time zone this tzdb no longer knows: every one of them changes *when*
 *    the alarm would ring. Guessing means ringing at the wrong time, which for an alarm clock
 *    is the worst available outcome; dropping the row means the alarm silently disappears,
 *    which is the second worst. Keeping it, visible and switched off, is the only option that
 *    neither lies nor forgets, and the editor can repair it in two taps.
 *  - **A row with no usable identity or no coordinates is dropped**, because there is nothing
 *    to repair. There is no honest default for "where on Earth": 0,0 is the Gulf of Guinea.
 *  - **Nothing here throws.** A truncated or corrupt document reads as an empty list.
 *
 * The tzdb case is not hypothetical. `Arctic/Longyearbyen` was moved to the backward-compat
 * set upstream, and the desugared `java.time` on API 24 and 25 carries its own copy of the
 * database; a rule stored against a retired identifier is exactly the row this policy keeps
 * alive.
 */
object AlarmStore {

    /** SharedPreferences file name. Separate from the app's general settings so that clearing
     *  one cannot take the other with it. */
    const val PREFS_NAME: String = "sun_app_alarms"

    /** The single key inside [PREFS_NAME] holding the whole document. */
    const val KEY_RULES: String = "rules_json"

    /**
     * Written into every document and never read.
     *
     * It exists so that a future version which genuinely cannot express itself in this shape
     * has somewhere to say so. Reading deliberately does not branch on it: a reader that
     * refuses unknown versions is a reader that breaks on rollback, which is the failure this
     * whole class is written to avoid.
     */
    const val FORMAT_VERSION: Int = 1

    /** Serialise [rules]. Null-valued optional fields are omitted rather than written as
     *  JSON null, so the document stays small and a reader cannot tell the two apart. */
    fun encode(rules: List<AlarmRule>): String {
        val array = JSONArray()
        for (rule in rules) {
            val json = JSONObject()
                .put(KEY_ID, rule.id)
                .put(KEY_LABEL, rule.label)
                .put(KEY_ANCHOR, rule.anchor.name)
                .put(KEY_DIRECTION, rule.direction.name)
                .put(KEY_OFFSET, rule.offsetMinutes)
                .put(KEY_LAT, rule.latDeg)
                .put(KEY_LON, rule.lonDeg)
                .put(KEY_ZONE, rule.zoneId)
                .put(KEY_PLACE, rule.placeName)
                .put(KEY_ENABLED, rule.enabled)
                .put(KEY_VIBRATE, rule.vibrate)
            rule.ringtoneUri?.let { json.put(KEY_RINGTONE, it) }
            rule.clamp?.let { clamp ->
                val nested = JSONObject()
                clamp.earliest?.let { nested.put(KEY_EARLIEST, it.toString()) }
                clamp.latest?.let { nested.put(KEY_LATEST, it.toString()) }
                json.put(KEY_CLAMP, nested)
            }
            array.put(json)
        }
        return JSONObject()
            .put(KEY_VERSION, FORMAT_VERSION)
            .put(KEY_RULES_ARRAY, array)
            .toString()
    }

    /**
     * Parse [text] back into rules, applying the tolerance policy in the class KDoc.
     *
     * A bare JSON array is accepted as well as the current wrapper object. That is not
     * speculative generality: it is one `startsWith` and it means a hand-edited file, an
     * export pasted from somewhere, or a shape from before the wrapper existed all still read.
     */
    fun decode(text: String?): List<AlarmRule> {
        if (text.isNullOrBlank()) return emptyList()
        val trimmed = text.trim()
        val array = try {
            if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                JSONObject(trimmed).optJSONArray(KEY_RULES_ARRAY) ?: return emptyList()
            }
        } catch (_: Exception) {
            // Exception, not RuntimeException, and this is not laziness. Android's framework
            // org.json declares JSONException as a checked exception extending Exception,
            // while the Maven org.json artifact the unit tests run against extends
            // RuntimeException. Catching the narrower type would pass every test on the JVM
            // and still crash the app on a device the first time storage was corrupt, which
            // is the exact class of bug a unit test is supposed to prevent.
            return emptyList()
        }

        val rules = ArrayList<AlarmRule>(array.length())
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val rule = try {
                decodeRule(json)
            } catch (_: Exception) {
                // Belt and braces. Every field below is read through an opt* accessor that
                // cannot throw, but a corrupt document must never be able to take the alarm
                // list with it, so an unforeseen shape costs one row rather than all of them.
                null
            }
            if (rule != null) rules.add(rule)
        }
        return rules
    }

    /** Read the stored rules, or an empty list if nothing has been stored yet. */
    fun load(file: AlarmRuleFile): List<AlarmRule> = decode(file.read())

    /** Replace the stored rules wholesale. There is no partial update: the list is small and
     *  a single atomic document removes any possibility of a half-written rule set. */
    fun save(file: AlarmRuleFile, rules: List<AlarmRule>) = file.write(encode(rules))

    private fun decodeRule(json: JSONObject): AlarmRule? {
        val id = json.optString(KEY_ID).takeIf { it.isNotBlank() } ?: return null
        val lat = json.optDouble(KEY_LAT, Double.NaN)
        val lon = json.optDouble(KEY_LON, Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null

        // Set by any field that was present but could not be read faithfully. See the class
        // KDoc: it switches the rule off rather than letting a guess decide a ringing time.
        var degraded = false

        val anchor = SolarEvent.entries.firstOrNull { it.name == json.optString(KEY_ANCHOR) }
            ?: run { degraded = true; SolarEvent.SUNRISE }

        val direction = Direction.entries.firstOrNull { it.name == json.optString(KEY_DIRECTION) }
            ?: run {
                degraded = true
                // AT rather than BEFORE or AFTER: it is the only value that ignores the
                // magnitude, so the stored number survives untouched for the user to reuse
                // once they re-pick the side.
                Direction.AT
            }

        val offsetMinutes = if (json.has(KEY_OFFSET) && !json.isNull(KEY_OFFSET)) {
            val probe = json.optInt(KEY_OFFSET, Int.MIN_VALUE)
            if (probe == Int.MIN_VALUE) {
                degraded = true
                0
            } else {
                probe
            }
        } else {
            0
        }

        val storedZone = json.optString(KEY_ZONE)
        val zoneId = if (storedZone.isNotBlank() && isKnownZone(storedZone)) {
            storedZone
        } else {
            degraded = true
            // The device zone is a placeholder that keeps the row constructible and visible.
            // The rule is disabled, so it cannot fire against a zone the user never chose.
            ZoneId.systemDefault().id
        }

        val clamp = if (json.has(KEY_CLAMP) && !json.isNull(KEY_CLAMP)) {
            val nested = json.optJSONObject(KEY_CLAMP)
            if (nested == null) {
                degraded = true
                null
            } else {
                val earliest = readTime(nested, KEY_EARLIEST) { degraded = true }
                val latest = readTime(nested, KEY_LATEST) { degraded = true }
                if (earliest == null && latest == null) null else Clamp(earliest, latest)
            }
        } else {
            null
        }

        val ringtoneUri = if (json.has(KEY_RINGTONE) && !json.isNull(KEY_RINGTONE)) {
            json.optString(KEY_RINGTONE).takeIf { it.isNotBlank() }
        } else {
            null
        }

        return AlarmRule(
            id = id,
            label = json.optString(KEY_LABEL),
            anchor = anchor,
            direction = direction,
            offsetMinutes = offsetMinutes,
            latDeg = lat,
            lonDeg = lon,
            zoneId = zoneId,
            placeName = json.optString(KEY_PLACE),
            clamp = clamp,
            enabled = json.optBoolean(KEY_ENABLED, true) && !degraded,
            ringtoneUri = ringtoneUri,
            vibrate = json.optBoolean(KEY_VIBRATE, true),
        )
    }

    /** ISO local time, which is what [LocalTime.toString] emits, so the round trip is exact
     *  including the seconds a clamp will never have. */
    private inline fun readTime(json: JSONObject, key: String, onUnreadable: () -> Unit): LocalTime? {
        if (!json.has(key) || json.isNull(key)) return null
        return try {
            LocalTime.parse(json.optString(key))
        } catch (_: Exception) {
            onUnreadable()
            null
        }
    }

    private fun isKnownZone(id: String): Boolean = try {
        ZoneId.of(id)
        true
    } catch (_: Exception) {
        false
    }

    private const val KEY_VERSION = "version"
    private const val KEY_RULES_ARRAY = "rules"
    private const val KEY_ID = "id"
    private const val KEY_LABEL = "label"
    private const val KEY_ANCHOR = "anchor"
    private const val KEY_DIRECTION = "direction"
    private const val KEY_OFFSET = "offsetMinutes"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_ZONE = "zoneId"
    private const val KEY_PLACE = "placeName"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_VIBRATE = "vibrate"
    private const val KEY_RINGTONE = "ringtoneUri"
    private const val KEY_CLAMP = "clamp"
    private const val KEY_EARLIEST = "earliest"
    private const val KEY_LATEST = "latest"
}
