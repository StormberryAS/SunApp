package no.stormberry.sunapp.data

import no.stormberry.sunapp.alarm.OccurrenceEngine
import no.stormberry.sunapp.alarm.model.AlarmRule
import no.stormberry.sunapp.alarm.model.Clamp
import no.stormberry.sunapp.alarm.model.Direction
import no.stormberry.sunapp.solar.SolarEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * [AlarmStore]'s round trip and, more importantly, its behaviour when handed a document it
 * does not fully understand.
 *
 * The forward-compatibility half is the part that earns its keep. A user can install a newer
 * SunApp, create a rule using a field this version has never heard of, and roll back; if
 * reading that row threw, every alarm on the device would be gone at once. So the tolerance
 * policy is asserted case by case rather than trusted: unknown keys ignored, missing optional
 * keys defaulted, a present-but-unreadable field degrading the rule to visible-and-disabled,
 * an unidentifiable row dropped, and nothing anywhere throwing.
 *
 * The store is exercised through the [AlarmRuleFile] seam with an in-memory implementation,
 * which is the whole reason that seam exists: no device, no Robolectric, no
 * `SharedPreferences`.
 */
class AlarmStoreTest {

    /**
     * [AlarmRuleFile] backed by a variable.
     *
     * Starts null rather than empty, because "nothing has ever been written" and "an empty
     * document was written" are genuinely different states on a first launch and the store is
     * required to survive both.
     */
    private class MemoryRuleFile(var text: String? = null) : AlarmRuleFile {
        var writes: Int = 0
            private set

        override fun read(): String? = text

        override fun write(text: String) {
            this.text = text
            writes++
        }
    }

    /** Every optional field populated, so a round trip has something to lose. */
    private val fullyPopulated = AlarmRule(
        id = "3f2a1c66-0d4b-4f1e-9a77-2b8e5c0d1a34",
        label = "Sunrise over Ulriken",
        anchor = SolarEvent.SUNSET,
        direction = Direction.AFTER,
        offsetMinutes = 480,
        latDeg = 60.3913,
        lonDeg = 5.3221,
        zoneId = "Europe/Oslo",
        placeName = "Bergen",
        clamp = Clamp(earliest = LocalTime.of(5, 30), latest = LocalTime.of(7, 0)),
        enabled = true,
        ringtoneUri = "content://media/internal/audio/media/42",
        vibrate = false,
    )

    /** Every optional field at its default, which is what the editor actually creates. */
    private val minimal = AlarmRule(
        id = "9c1d0e22-7a33-4b5f-8e10-6d4a2f7b9c05",
        label = "",
        anchor = SolarEvent.SUNRISE,
        direction = Direction.AT,
        offsetMinutes = 0,
        latDeg = 78.2233,
        lonDeg = 15.6469,
        zoneId = "Europe/Oslo",
        placeName = "Longyearbyen",
        clamp = null,
        enabled = true,
        ringtoneUri = null,
        vibrate = true,
    )

    private fun roundTrip(vararg rules: AlarmRule): List<AlarmRule> =
        AlarmStore.decode(AlarmStore.encode(rules.toList()))

    /** The rules array of a freshly encoded document, ready to be vandalised by a test. */
    private fun encodedDocument(vararg rules: AlarmRule): JSONObject =
        JSONObject(AlarmStore.encode(rules.toList()))

    // -------------------------------------------------------------------------------------
    // The round trip
    // -------------------------------------------------------------------------------------

    /** Every field, compared by value equality on the data class so that a field added later
     *  and forgotten in [AlarmStore.encode] fails here rather than silently resetting to a
     *  default on the user's next launch. */
    @Test
    fun `a fully populated rule survives the round trip unchanged`() {
        assertEquals(listOf(fullyPopulated), roundTrip(fullyPopulated))
    }

    /** The nulls survive as nulls rather than coming back as empty strings or zero-valued
     *  clamps, which is the usual way a hand-written mapping loses information. */
    @Test
    fun `a rule with every optional field absent survives the round trip unchanged`() {
        val decoded = roundTrip(minimal)

        assertEquals(listOf(minimal), decoded)
        assertNull(decoded.single().clamp)
        assertNull(decoded.single().ringtoneUri)
        assertEquals("", decoded.single().label)
    }

    /** Both half-open clamps, because a bound that was silently promoted from null to
     *  midnight would clamp every alarm to a time the user never chose. */
    @Test
    fun `a clamp with only one bound keeps the other bound null`() {
        val floorOnly = fullyPopulated.copy(id = "floor", clamp = Clamp(earliest = LocalTime.of(6, 15)))
        val ceilingOnly = fullyPopulated.copy(id = "ceiling", clamp = Clamp(latest = LocalTime.of(8, 45)))

        val decoded = AlarmStore.decode(AlarmStore.encode(listOf(floorOnly, ceilingOnly)))

        assertEquals(LocalTime.of(6, 15), decoded[0].clamp!!.earliest)
        assertNull(decoded[0].clamp!!.latest)
        assertNull(decoded[1].clamp!!.earliest)
        assertEquals(LocalTime.of(8, 45), decoded[1].clamp!!.latest)
    }

    /**
     * An inert clamp decodes as no clamp at all.
     *
     * `Clamp(null, null)` is what the editor holds while the section is open and no bound has
     * been picked, and [AlarmRule.hasEffectiveClamp] already treats it as absent everywhere,
     * so normalising it on the way in is a simplification rather than a loss. Asserted so that
     * the one field which deliberately does not round trip is a documented decision rather
     * than a discovery.
     */
    @Test
    fun `an inert clamp with both bounds null decodes as no clamp`() {
        val decoded = roundTrip(fullyPopulated.copy(clamp = Clamp()))

        assertNull(decoded.single().clamp)
        assertFalse(decoded.single().hasEffectiveClamp)
    }

    /** A disabled rule stays disabled. The alternative, re-enabling on load, would ring an
     *  alarm the user had switched off. */
    @Test
    fun `the enabled flag survives in both directions`() {
        assertTrue(roundTrip(fullyPopulated.copy(enabled = true)).single().enabled)
        assertFalse(roundTrip(fullyPopulated.copy(enabled = false)).single().enabled)
    }

    /** Every anchor and every direction, not just the three the 1.1.0 picker offers. The
     *  engine supports fourteen events, a later version may surface more of them, and a rule
     *  written by that version has to survive a rollback through this reader. */
    @Test
    fun `every anchor and direction survives the round trip`() {
        val rules = SolarEvent.entries.flatMap { anchor ->
            Direction.entries.map { direction ->
                fullyPopulated.copy(id = "$anchor-$direction", anchor = anchor, direction = direction)
            }
        }

        assertEquals(rules, AlarmStore.decode(AlarmStore.encode(rules)))
    }

    /** Negative and large offsets are obeyed rather than quietly normalised. A rule that
     *  stored -90 must not come back as 90 and ring three hours out. */
    @Test
    fun `unusual offsets survive the round trip`() {
        for (offset in listOf(-90, 0, 1, 1439, 1440, 4321)) {
            assertEquals(
                "offset $offset",
                offset,
                roundTrip(fullyPopulated.copy(offsetMinutes = offset)).single().offsetMinutes,
            )
        }
    }

    /** Coordinates keep enough precision to be the same place. The city table carries four
     *  decimals, which is about eleven metres, and rounding to two would move a rule far
     *  enough to shift a high-latitude sunrise by minutes. */
    @Test
    fun `coordinates survive the round trip at full precision`() {
        val decoded = roundTrip(fullyPopulated).single()

        assertEquals(60.3913, decoded.latDeg, 0.0)
        assertEquals(5.3221, decoded.lonDeg, 0.0)
    }

    /** Negative coordinates and a southern-hemisphere zone, because a sign dropped in the
     *  mapping is invisible in Bergen and catastrophic in Wellington. */
    @Test
    fun `southern and western coordinates survive the round trip`() {
        val wellington = fullyPopulated.copy(
            latDeg = -41.2866,
            lonDeg = 174.7756,
            zoneId = "Pacific/Auckland",
            placeName = "Wellington",
        )
        val quito = fullyPopulated.copy(id = "quito", latDeg = -0.2202, lonDeg = -78.5123, zoneId = "America/Guayaquil")

        assertEquals(
            listOf(wellington, quito),
            AlarmStore.decode(AlarmStore.encode(listOf(wellington, quito))),
        )
    }

    /** Order is preserved, because the list is what the UI renders and a reshuffle on every
     *  launch would look like corruption. */
    @Test
    fun `rule order is preserved`() {
        val rules = (1..5).map { fullyPopulated.copy(id = "rule-$it", label = "Alarm $it") }

        assertEquals(rules.map { it.id }, AlarmStore.decode(AlarmStore.encode(rules)).map { it.id })
    }

    /** An empty list is a legitimate state, reached by deleting the last rule, and must not
     *  be confused with never having written anything. */
    @Test
    fun `an empty list round trips as an empty list`() {
        assertTrue(AlarmStore.decode(AlarmStore.encode(emptyList())).isEmpty())
    }

    /**
     * The end-to-end guarantee that actually matters: a rule that has been through storage
     * rings at the same instant as one that has not.
     *
     * Field-by-field equality is the proxy; this is the property. It closes the gap where a
     * mapping preserves everything `equals` sees while losing something the engine reads.
     */
    @Test
    fun `a stored and reloaded rule computes exactly the same occurrences`() {
        val reloaded = roundTrip(fullyPopulated).single()
        val from = LocalDate.of(2026, 6, 18)

        assertEquals(
            OccurrenceEngine.preview(fullyPopulated, from),
            OccurrenceEngine.preview(reloaded, from),
        )
        assertNotNull(OccurrenceEngine.occurrenceFor(reloaded, from))
    }

    // -------------------------------------------------------------------------------------
    // Forward compatibility: the reason this class is hand written
    // -------------------------------------------------------------------------------------

    /**
     * A field this version has never heard of is ignored, not rejected.
     *
     * The scenario is concrete: a later SunApp adds "ring only on weekdays", the user creates
     * a rule with it, then rolls back. This reader has to hand that rule back working, minus
     * the field it cannot honour.
     */
    @Test
    fun `an unknown field inside a rule is ignored rather than throwing`() {
        val document = encodedDocument(fullyPopulated)
        document.getJSONArray("rules").getJSONObject(0)
            .put("daysOfWeek", JSONArray(listOf("MON", "TUE")))
            .put("autoSilenceMinutes", 15)
            .put("somethingNested", JSONObject().put("a", 1))

        assertEquals(listOf(fullyPopulated), AlarmStore.decode(document.toString()))
    }

    /** The same at the document level, where a later version would put its own metadata. */
    @Test
    fun `an unknown top level key is ignored rather than throwing`() {
        val document = encodedDocument(fullyPopulated)
            .put("exportedAt", "2027-01-01T00:00:00Z")
            .put("schemaExtensions", JSONArray(listOf("weekdays")))

        assertEquals(listOf(fullyPopulated), AlarmStore.decode(document.toString()))
    }

    /**
     * A future format version is read, not refused.
     *
     * The version field exists so a future version has somewhere to declare itself; a reader
     * that branched on it would break exactly the rollback this whole class is written to
     * survive.
     */
    @Test
    fun `a document from a future format version still reads`() {
        val document = encodedDocument(fullyPopulated).put("version", 99)

        assertEquals(listOf(fullyPopulated), AlarmStore.decode(document.toString()))
    }

    /** And the version is written, so that future version has something to look at. */
    @Test
    fun `the encoded document declares its format version`() {
        assertEquals(
            AlarmStore.FORMAT_VERSION,
            encodedDocument(minimal).getInt("version"),
        )
    }

    /** A bare array is accepted as well as the wrapper object, so a hand-edited file or an
     *  export from before the wrapper existed still reads. */
    @Test
    fun `a bare JSON array is accepted`() {
        val array = encodedDocument(fullyPopulated).getJSONArray("rules")

        assertEquals(listOf(fullyPopulated), AlarmStore.decode(array.toString()))
    }

    /** Leading and trailing whitespace does not defeat the array sniff. */
    @Test
    fun `a bare JSON array with surrounding whitespace is accepted`() {
        val array = encodedDocument(fullyPopulated).getJSONArray("rules")

        assertEquals(listOf(fullyPopulated), AlarmStore.decode("  $array  "))
    }

    // -------------------------------------------------------------------------------------
    // Degradation: a rule that cannot be read faithfully is kept, visible and switched off
    // -------------------------------------------------------------------------------------

    /**
     * An anchor name this version does not know disables the rule rather than guessing.
     *
     * Guessing means ringing at the wrong time, which for an alarm clock is the worst
     * available outcome. Dropping the row means the alarm silently disappears, which is the
     * second worst. Keeping it, visible and switched off, is the only option that neither
     * lies nor forgets.
     */
    @Test
    fun `an unrecognised anchor disables the rule rather than dropping or guessing it`() {
        val document = encodedDocument(fullyPopulated)
        document.getJSONArray("rules").getJSONObject(0).put("anchor", "BLUE_HOUR_START")

        val decoded = AlarmStore.decode(document.toString())

        assertEquals("the row is kept so the editor can repair it", 1, decoded.size)
        assertFalse("but it must not be allowed to ring", decoded.single().enabled)
        assertEquals(fullyPopulated.id, decoded.single().id)
    }

    /** Likewise an unrecognised direction, which falls back to AT so the stored magnitude
     *  survives untouched for the user to reuse once they re-pick the side. */
    @Test
    fun `an unrecognised direction disables the rule and keeps the magnitude`() {
        val document = encodedDocument(fullyPopulated)
        document.getJSONArray("rules").getJSONObject(0).put("direction", "SIDEWAYS")

        val decoded = AlarmStore.decode(document.toString()).single()

        assertFalse(decoded.enabled)
        assertEquals(Direction.AT, decoded.direction)
        assertEquals(480, decoded.offsetMinutes)
    }

    /**
     * A time zone this tzdb no longer knows disables the rule.
     *
     * Not hypothetical: `Arctic/Longyearbyen` has been moved into tzdb's backward-compatible
     * set, and the desugared `java.time` shipped to API 24 and 25 carries its own copy of the
     * database. A rule stored against a retired identifier is exactly the row this policy
     * keeps alive instead of crashing the scheduler, which is the contract `OccurrenceEngine`
     * relies on when it lets `ZoneId.of` throw.
     */
    @Test
    fun `an unusable time zone disables the rule and leaves a resolvable placeholder`() {
        val document = encodedDocument(fullyPopulated)
        document.getJSONArray("rules").getJSONObject(0).put("zoneId", "Mars/Olympus_Mons")

        val decoded = AlarmStore.decode(document.toString()).single()

        assertFalse(decoded.enabled)
        assertEquals(ZoneId.systemDefault().id, decoded.zoneId)
        assertNotNull(
            "the placeholder must be constructible or the engine throws on a disabled row",
            ZoneId.of(decoded.zoneId),
        )
    }

    /** A clamp bound that is not a time disables the rule, because a bound silently dropped
     *  would let the alarm track the sun past the floor the user set. */
    @Test
    fun `an unreadable clamp bound disables the rule`() {
        val document = encodedDocument(fullyPopulated)
        document.getJSONArray("rules").getJSONObject(0).getJSONObject("clamp").put("latest", "half seven")

        val decoded = AlarmStore.decode(document.toString()).single()

        assertFalse(decoded.enabled)
        assertEquals(LocalTime.of(5, 30), decoded.clamp!!.earliest)
        assertNull(decoded.clamp!!.latest)
    }

    /** An offset that is not a number disables the rule rather than defaulting it to zero and
     *  ringing at the bare anchor. */
    @Test
    fun `an unreadable offset disables the rule`() {
        val document = encodedDocument(fullyPopulated)
        document.getJSONArray("rules").getJSONObject(0).put("offsetMinutes", "eight hours")

        val decoded = AlarmStore.decode(document.toString()).single()

        assertFalse(decoded.enabled)
        assertEquals(0, decoded.offsetMinutes)
    }

    /** A degraded rule stays degraded even when the stored `enabled` said true. The flag is
     *  the point of the whole policy, so it must not be recoverable by accident. */
    @Test
    fun `degradation overrides a stored enabled flag`() {
        val document = encodedDocument(fullyPopulated.copy(enabled = true))
        document.getJSONArray("rules").getJSONObject(0).put("anchor", "NOT_AN_EVENT")

        assertFalse(AlarmStore.decode(document.toString()).single().enabled)
    }

    // -------------------------------------------------------------------------------------
    // Rows with nothing to repair are dropped; corrupt documents cost nothing
    // -------------------------------------------------------------------------------------

    /** No id means no request code and nothing for the editor to address, so there is nothing
     *  to keep. */
    @Test
    fun `a row with no usable id is dropped`() {
        for (bad in listOf(JSONObject.NULL, "", "   ")) {
            val document = encodedDocument(fullyPopulated)
            document.getJSONArray("rules").getJSONObject(0).put("id", bad)

            assertTrue("id $bad should drop the row", AlarmStore.decode(document.toString()).isEmpty())
        }
    }

    /** No coordinates means no honest default. 0,0 is the Gulf of Guinea, and a rule that
     *  quietly moved there would ring hours out rather than not at all. */
    @Test
    fun `a row with no usable coordinates is dropped`() {
        for (key in listOf("lat", "lon")) {
            val document = encodedDocument(fullyPopulated)
            document.getJSONArray("rules").getJSONObject(0).put(key, "somewhere north")

            assertTrue("$key should drop the row", AlarmStore.decode(document.toString()).isEmpty())
        }

        val missing = encodedDocument(fullyPopulated)
        missing.getJSONArray("rules").getJSONObject(0).remove("lat")
        assertTrue(AlarmStore.decode(missing.toString()).isEmpty())
    }

    /**
     * One bad row costs one row, never the list.
     *
     * The failure mode being ruled out is the one where a single corrupt entry takes every
     * other alarm on the device with it.
     */
    @Test
    fun `a bad row costs one rule rather than the whole list`() {
        val good = fullyPopulated.copy(id = "good-1")
        val alsoGood = minimal.copy(id = "good-2")
        val document = encodedDocument(good, fullyPopulated.copy(id = "bad"), alsoGood)
        document.getJSONArray("rules").getJSONObject(1).put("id", "")

        assertEquals(listOf("good-1", "good-2"), AlarmStore.decode(document.toString()).map { it.id })
    }

    /** An entry that is not even an object is skipped without disturbing its neighbours. */
    @Test
    fun `a non object entry in the array is skipped`() {
        val array = encodedDocument(fullyPopulated).getJSONArray("rules")
        array.put("this is not a rule")
        array.put(7)

        assertEquals(listOf(fullyPopulated), AlarmStore.decode(array.toString()))
    }

    /** Nothing stored yet. The first launch, and the most common call this class ever gets. */
    @Test
    fun `a null or blank document reads as no rules`() {
        assertTrue(AlarmStore.decode(null).isEmpty())
        assertTrue(AlarmStore.decode("").isEmpty())
        assertTrue(AlarmStore.decode("    ").isEmpty())
    }

    /**
     * A truncated or otherwise corrupt document reads as an empty list rather than throwing.
     *
     * The caller is a boot receiver or a cold start. An exception there is a crash loop on
     * launch, which is unrecoverable without clearing app data; an empty list is a visible,
     * repairable loss.
     */
    @Test
    fun `a corrupt document reads as no rules rather than throwing`() {
        val truncated = AlarmStore.encode(listOf(fullyPopulated)).take(60)

        for (document in listOf(truncated, "{", "[", "not json at all", "{,}", " ")) {
            assertTrue("[$document] should read as empty", AlarmStore.decode(document).isEmpty())
        }
    }

    /** A well-formed document with no rules array, which is what a completely different
     *  application's preferences file would look like. */
    @Test
    fun `a document with no rules array reads as no rules`() {
        assertTrue(AlarmStore.decode("""{"version":1,"somethingElse":true}""").isEmpty())
    }

    /** A rules key holding the wrong type is treated as absent, not as an error. */
    @Test
    fun `a rules key of the wrong type reads as no rules`() {
        assertTrue(AlarmStore.decode("""{"version":1,"rules":"oops"}""").isEmpty())
    }

    // -------------------------------------------------------------------------------------
    // The file seam
    // -------------------------------------------------------------------------------------

    /** Save then load, through the interface the Android implementation sits behind. */
    @Test
    fun `save then load through a file returns the same rules`() {
        val file = MemoryRuleFile()

        AlarmStore.save(file, listOf(fullyPopulated, minimal))

        assertEquals(1, file.writes)
        assertEquals(listOf(fullyPopulated, minimal), AlarmStore.load(file))
    }

    /** Loading a file that has never been written is an empty list, not a crash and not a
     *  null. */
    @Test
    fun `loading a file that was never written returns an empty list`() {
        assertTrue(AlarmStore.load(MemoryRuleFile()).isEmpty())
    }

    /** The save is a single atomic document, so there is no window in which half a rule set
     *  is on disk. */
    @Test
    fun `saving replaces the whole document in one write`() {
        val file = MemoryRuleFile()

        AlarmStore.save(file, listOf(fullyPopulated, minimal))
        AlarmStore.save(file, listOf(minimal))

        assertEquals(2, file.writes)
        assertEquals(listOf(minimal), AlarmStore.load(file))
    }

    /** Saving nothing genuinely empties the file rather than leaving the previous rules
     *  behind, which is what deleting the last alarm has to do. */
    @Test
    fun `saving an empty list clears the stored rules`() {
        val file = MemoryRuleFile()

        AlarmStore.save(file, listOf(fullyPopulated))
        AlarmStore.save(file, emptyList())

        assertTrue(AlarmStore.load(file).isEmpty())
    }

    /** Two save-load cycles are identical to one. A mapping that lost or added something on
     *  each pass would drift a little every time the user edited a rule. */
    @Test
    fun `repeated save and load cycles are stable`() {
        val file = MemoryRuleFile()
        AlarmStore.save(file, listOf(fullyPopulated, minimal))

        val first = AlarmStore.load(file)
        AlarmStore.save(file, first)
        val second = AlarmStore.load(file)

        assertEquals(first, second)
        assertEquals(listOf(fullyPopulated, minimal), second)
    }
}
