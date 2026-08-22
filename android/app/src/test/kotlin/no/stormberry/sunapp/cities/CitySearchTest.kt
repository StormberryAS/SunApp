package no.stormberry.sunapp.cities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.util.Locale

/**
 * Search and timezone-resolution tests.
 *
 * Half of these are diacritic cases, which is not padding: every one of them
 * was once a real miss on the web app. The catalogue spells places the way the
 * locals do, and people type them the way their keyboard allows.
 */
class CitySearchTest {

    // ---------------------------------------------------------------- folding

    @Test
    fun foldStripsCombiningAccents() {
        assertEquals("herat", foldQuery("Herāt"))
        assertEquals("durres", foldQuery("Durrës"))
        assertEquals("goteborg", foldQuery("Göteborg"))
        assertEquals("sao paulo", foldQuery("São Paulo"))
    }

    @Test
    fun foldExpandsTheAtomicLetters() {
        // NFD cannot decompose any of these: there is no base letter plus mark
        // to strip, so without the ATOMIC map they would survive the fold and
        // never match an ASCII query.
        assertEquals("tromso", foldQuery("Tromsø"))
        assertEquals("aero", foldQuery("Ærø"))
        assertEquals("thorshofn", foldQuery("Þórshöfn"))
        assertEquals("lodz", foldQuery("Łódź"))
        assertEquals("da nang", foldQuery("Đà Nẵng"))
        assertEquals("strasse", foldQuery("Straße"))
        assertEquals("hamrun", foldQuery("Ħamrun"))
        assertEquals("oeuvre", foldQuery("Œuvre"))
        assertEquals("nqn", foldQuery("Ŋqŋ"))
        assertEquals("ti", foldQuery("ŧı"))
        assertEquals("ijssel", foldQuery("Ĳssel"))
    }

    @Test
    fun foldIsIdempotent() {
        // The stored fold column is fed back through search comparisons, so
        // folding an already-folded string must be a no-op or the second pass
        // would change what the first pass agreed on.
        for (sample in listOf("Tromsø", "Herāt", "Łódź", "Straße", "Ĳssel", "Køge")) {
            val once = foldQuery(sample)
            assertEquals(once, foldQuery(once))
        }
    }

    @Test
    fun foldHandlesTheEmptyString() {
        assertEquals("", foldQuery(""))
    }

    // ---------------------------------------------------------------- ranking

    @Test
    fun prefixMatchesOutrankSubstringMatches() {
        // The bug this ordering exists for: "erdal" used to return Cloverdale,
        // South Riverdale and Terdāl above Erdal, and with eight visible rows
        // the place being typed fell off the list entirely.
        val results = CitySearch.search(TestCatalogue.table, "erdal")
        assertEquals("Erdal", results.first().name)
        assertEquals("Europe/Oslo", results.first().tz)
        assertTrue("Expected the substring band to top up the list", results.size > 1)
    }

    @Test
    fun findsAccentedNamesFromAnAsciiQuery() {
        assertTrue(namesOf("tromso").contains("Tromsø"))
        assertTrue(namesOf("Herat").contains("Herāt"))
        assertTrue(namesOf("kleppesto").contains("Kleppestø"))
        assertTrue(namesOf("askoy").contains("Askøy"))
        assertTrue(namesOf("florvag").contains("Florvåg"))
    }

    @Test
    fun findsLocalNamesFromAnEnglishExonym() {
        // These come from the alt column. Nobody typing English guesses
        // "Göteborg" or "Köln" unprompted, and nobody transliterating Dari
        // guesses that GeoNames chose the macrons.
        assertTrue(namesOf("gothenburg").contains("Göteborg"))
        assertTrue(namesOf("cologne").contains("Köln"))
        assertTrue(namesOf("mazari sharif").contains("Mazār-e Sharīf"))
    }

    @Test
    fun findsAccentedNamesFromTheAccentedSpelling() {
        // Both halves of the comparison fold, so typing the local spelling has
        // to work as well as typing the ASCII one.
        assertTrue(namesOf("Tromsø").contains("Tromsø"))
        assertTrue(namesOf("Herāt").contains("Herāt"))
    }

    @Test
    fun matchesOnCountryAsWellAsName() {
        // No city fold starts with "norway", so every hit here comes from the
        // country fold, which is checked for substrings only.
        val results = CitySearch.search(TestCatalogue.table, "norway", limit = 5)
        assertEquals(5, results.size)
        assertTrue(results.all { it.country == "Norway" })
    }

    @Test
    fun honoursTheLimit() {
        assertEquals(8, CitySearch.search(TestCatalogue.table, "a").size)
        assertEquals(3, CitySearch.search(TestCatalogue.table, "a", limit = 3).size)
        assertEquals(0, CitySearch.search(TestCatalogue.table, "a", limit = 0).size)
    }

    @Test
    fun caseAndSurroundingSpaceDoNotMatter() {
        val plain = namesOf("bergen")
        assertEquals(plain, namesOf("  BERGEN  "))
        assertEquals(plain, namesOf("Bergen"))
    }

    @Test
    fun returnsNothingForAQueryThatFoldsAway() {
        // A stray combining mark folds to the empty string. Matching that
        // against every city would hand back an arbitrary first eight, which
        // reads as a broken search rather than an empty one.
        assertTrue(CitySearch.search(TestCatalogue.table, "\u0301").isEmpty())
        assertTrue(CitySearch.search(TestCatalogue.table, "   ").isEmpty())
    }

    @Test
    fun returnsNothingForAQueryThatMatchesNothing() {
        assertTrue(CitySearch.search(TestCatalogue.table, "zzzqqqxxx").isEmpty())
    }

    // ------------------------------------------------------- nearest timezone

    @Test
    fun nearestTimezoneNearBergenIsOslo() {
        // Kleppestø, where this app was written. Any of the seven Askøy entries
        // is within a few kilometres, and all of them are Europe/Oslo.
        assertEquals(
            "Europe/Oslo",
            CitySearch.nearestTimezone(TestCatalogue.table, 60.4079, 5.2341),
        )
        // Bergen city centre, and a point out in the fjord between the two.
        assertEquals(
            "Europe/Oslo",
            CitySearch.nearestTimezone(TestCatalogue.table, 60.3913, 5.3221),
        )
        assertEquals(
            "Europe/Oslo",
            CitySearch.nearestTimezone(TestCatalogue.table, 60.42, 5.10),
        )
    }

    @Test
    fun nearestTimezoneAgreesWithObviousCases() {
        assertEquals("Europe/London", CitySearch.nearestTimezone(TestCatalogue.table, 51.5074, -0.1278))
        assertEquals("America/Sao_Paulo", CitySearch.nearestTimezone(TestCatalogue.table, -22.9068, -43.1729))
        assertEquals("Asia/Tokyo", CitySearch.nearestTimezone(TestCatalogue.table, 35.6895, 139.6917))
    }

    @Test
    fun nearestTimezoneWrapsAcrossTheAntimeridian() {
        // A synthetic table, because the real one has no pair of cities that
        // isolates the wrap. Query at 179.5 W: the 179 E city is 1.5 degrees
        // away across the line and 358.5 the long way round, while the 170 W
        // city is 9.5 degrees away. Subtracting the raw longitudes puts the
        // near city 358.5 degrees off and hands the answer to the wrong one.
        val table = CityTable.parse(
            StringReader(
                "Fiji\tUSA\nPacific/Fiji\tPacific/Honolulu\n" +
                    "Near Antimeridian\t\t0\t0\t1790000\t0\t\n" +
                    "Far East Of It\t\t1\t0\t-1700000\t1\t"
            )
        )
        assertEquals("Pacific/Fiji", CitySearch.nearestTimezone(table, 0.0, -179.5))
        assertEquals("Pacific/Fiji", CitySearch.nearestTimezone(table, 0.0, 179.5))
    }

    @Test
    fun nearestTimezoneOfAnEmptyTableIsNull() {
        // Callers fall back to the device zone. Returning a wrong zone would be
        // worse than admitting there is no answer.
        assertNull(CitySearch.nearestTimezone(CityTable(emptyList()), 60.0, 5.0))
    }

    @Test
    fun searchIsFastEnoughForAKeystroke() {
        // Not a benchmark, a tripwire. If a linear scan of 25,007 rows ever
        // stops being cheap enough to run on the main thread per keystroke, the
        // design decision in CityTable's KDoc needs revisiting.
        val table = TestCatalogue.table
        val queries = listOf("berg", "sao p", "koln", "zzzqqq", "norway", "erdal")
        repeat(20) { queries.forEach { CitySearch.search(table, it) } }

        val start = System.nanoTime()
        repeat(100) { queries.forEach { CitySearch.search(table, it) } }
        val perQueryMs = (System.nanoTime() - start) / 1e6 / (100 * queries.size)

        println(String.format(Locale.ROOT, "CitySearch.search: %.3f ms per query", perQueryMs))
        assertTrue("Search took %.1f ms per query".format(Locale.ROOT, perQueryMs), perQueryMs < 20.0)
    }

    private fun namesOf(query: String): List<String> =
        CitySearch.search(TestCatalogue.table, query).map { it.name }
}
