package no.stormberry.sunapp.cities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.time.ZoneId
import java.util.Locale

/**
 * Parser tests against the real 25,007-row catalogue.
 *
 * These are the tests that fail when `update_cities.py` changes shape, which is
 * the only way this app finds out. The generator writes to nine sibling apps
 * and none of them would notice a seventh column becoming an eighth.
 */
class CityTableTest {

    @Test
    fun parsesTheWholeShippedCatalogue() {
        // The count is the assertion; the timing is reported rather than
        // asserted, because a CI runner's wall clock is nobody's phone. Five
        // runs, best reported alongside the first: the first pays the page
        // cache and a cold JIT, which is what the app pays on a real launch,
        // and the best is what the code costs once it is warm. Both numbers
        // are useful and neither on its own is honest.
        var table: CityTable? = null
        val timingsMs = DoubleArray(5)
        for (run in timingsMs.indices) {
            val start = System.nanoTime()
            table = TestCatalogue.parseFile()
            timingsMs[run] = (System.nanoTime() - start) / 1e6
        }
        val cities = table!!.cities

        assertEquals(25_007, cities.size)
        println(
            String.format(
                Locale.ROOT,
                "CityTable.parse: %,d cities, first run %.1f ms, best of %d %.1f ms",
                cities.size,
                timingsMs[0],
                timingsMs.size,
                timingsMs.min(),
            )
        )
    }

    @Test
    fun everyRowIsFullyPopulated() {
        for (city in TestCatalogue.table.cities) {
            assertTrue("Blank name in catalogue", city.name.isNotEmpty())
            // fold is reconstructed from the name when the column is blank, so
            // it must never come out empty even though most rows omit it.
            assertTrue("Blank fold for ${city.name}", city.fold.isNotEmpty())
            assertTrue("Blank country for ${city.name}", city.country.isNotEmpty())
            assertTrue("Blank cfold for ${city.name}", city.cfold.isNotEmpty())
            assertTrue("Latitude out of range for ${city.name}", city.lat in -90.0..90.0)
            assertTrue("Longitude out of range for ${city.name}", city.lon in -180.0..180.0)
        }
    }

    @Test
    fun foldColumnAgreesWithFoldQuery() {
        // The fold column was written by the Python fold(); this asserts the
        // Kotlin foldQuery() would have produced the same thing. If the two
        // ever drift, this is where it surfaces, rather than in a user's search
        // returning nothing for a place they can see on the website.
        for (city in TestCatalogue.table.cities) {
            assertEquals(
                "Stored fold disagrees with foldQuery for ${city.name}",
                foldQuery(city.name),
                city.fold,
            )
        }
    }

    @Test
    fun everyTimezoneIsAResolvableZoneId() {
        // A tzdb release can retire a zone into a backward-compatibility link
        // and then drop the link. Arctic/Longyearbyen is exactly that kind of
        // candidate and is in this catalogue. Catching it here beats catching
        // it as an alarm that silently stops firing on Svalbard.
        val zones = TestCatalogue.table.cities.map { it.tz }.toSortedSet()
        for (zone in zones) {
            ZoneId.of(zone)
        }
        assertTrue("Suspiciously few distinct zones: ${zones.size}", zones.size > 300)
    }

    @Test
    fun localAskoyPlacesSurviveRegeneration() {
        // These sit far below the population cutoff and are in the catalogue
        // only because update_cities.py carries them in EXTRA_MANUAL_CITIES.
        // A regeneration that drops that list is a regression, and this is the
        // test that says so.
        val expected = listOf(
            "Askøy", "Kleppestø", "Florvåg", "Strusshamn", "Erdal", "Hetlevik", "Follese",
        )
        for (name in expected) {
            val city = TestCatalogue.city(name)
            assertEquals("Wrong timezone for $name", "Europe/Oslo", city.tz)
            assertEquals("Wrong country for $name", "Norway", city.country)
            // All seven are in Askøy kommune, west of Bergen.
            assertTrue("$name is nowhere near Askøy", city.lat in 60.3..60.5 && city.lon in 5.0..5.4)
        }
    }

    @Test
    fun decodesPalettesAndScaledCoordinates() {
        val tromso = TestCatalogue.city("Tromsø")
        assertEquals("Norway", tromso.country)
        assertEquals("Europe/Oslo", tromso.tz)
        // Coordinates are stored as integers at 1e4 and must come back exact.
        assertEquals(69.6489, tromso.lat, 0.0)
        assertEquals(18.9551, tromso.lon, 0.0)
        assertEquals("tromso", tromso.fold)
    }

    @Test
    fun reconstructsBlankFoldAndBlankAlt() {
        // Erdal's fold column is blank because it equals the lowercased name,
        // and it has no English exonym. Both blanks must decode to something
        // usable rather than to an empty string that never matches.
        val erdal = TestCatalogue.city("Erdal")
        assertEquals("erdal", erdal.fold)
        assertEquals("", erdal.alt)

        // Köln carries both: a fold that differs from the name and an exonym.
        val koln = TestCatalogue.city("Köln")
        assertEquals("koln", koln.fold)
        assertEquals("cologne", koln.alt)
    }

    @Test
    fun toleratesRowsWithoutTheExonymColumn() {
        // The seventh column arrived after the format did. A file written
        // before it must still parse, with an empty alt, rather than force nine
        // apps to regenerate in lockstep.
        val packed = "Norway\tSweden\nEurope/Oslo\tEurope/Stockholm\n" +
            "Bergen\t\t0\t603894\t53221\t0\n" +
            "Göteborg\tgoteborg\t1\t577072\t119668\t1\tgothenburg"
        val table = CityTable.parse(StringReader(packed))

        assertEquals(2, table.cities.size)
        assertEquals("", table.cities[0].alt)
        assertEquals("bergen", table.cities[0].fold)
        assertEquals("Europe/Oslo", table.cities[0].tz)
        assertEquals("gothenburg", table.cities[1].alt)
        assertEquals("Sweden", table.cities[1].country)
    }

    @Test
    fun handlesNegativeCoordinates() {
        val packed = "Chile\nAmerica/Santiago\nPunta Arenas\t\t0\t-531500\t-708333\t0\t"
        val city = CityTable.parse(StringReader(packed)).cities.single()
        assertEquals(-53.15, city.lat, 0.0)
        assertEquals(-70.8333, city.lon, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsATruncatedRow() {
        CityTable.parse(StringReader("Norway\nEurope/Oslo\nBergen\t\t0\t603894"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsANonNumericCoordinate() {
        CityTable.parse(StringReader("Norway\nEurope/Oslo\nBergen\t\t0\tnorth\t53221\t0\t"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnOutOfRangePaletteIndex() {
        CityTable.parse(StringReader("Norway\nEurope/Oslo\nBergen\t\t7\t603894\t53221\t0\t"))
    }

    @Test
    fun ignoresATrailingNewline() {
        // The generator writes none, but an editor that adds one must not
        // produce a phantom city with an empty name.
        val table = CityTable.parse(StringReader("Norway\nEurope/Oslo\nBergen\t\t0\t603894\t53221\t0\t\n"))
        assertEquals(1, table.cities.size)
        assertFalse(table.cities.any { it.name.isEmpty() })
    }
}
