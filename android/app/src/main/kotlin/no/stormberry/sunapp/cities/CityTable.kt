package no.stormberry.sunapp.cities

import java.io.BufferedReader
import java.io.Reader

/**
 * The parsed city catalogue, and the parser for it.
 *
 * Deliberately free of any `android.*` import. The parser takes a [Reader],
 * not a `Context` or an `AssetManager`, so the whole of it runs under plain
 * JUnit against the real 25,007-row file in `SunApp/data/cities.tsv`. Reading
 * assets is [CityAssets]' job and nothing else's.
 *
 * ## Why no database
 *
 * The obvious Android reflex here is Room, or at least a bundled SQLite file.
 * Both are wrong for this shape of data. The catalogue is 909 KiB of text that
 * is never written and never queried by anything an index could help with:
 * every search is a leading-wildcard substring match, which no B-tree serves.
 * An unindexed SQLite copy of the same rows is larger than the TSV, indices on
 * top of it would be dead weight, and Room would drag KSP into the build for a
 * table with one query. A linear scan over an in-memory list answers a
 * keystroke in about 0.8 ms on a desktop JVM and a few milliseconds on a
 * phone, comfortably inside a frame, which is the only number that matters.
 * The cost is 4.1 MiB of heap held for the life of the process, which is the
 * price of not having a database.
 *
 * ## Why the file is packed
 *
 * Country and timezone names repeat thousands of times, so the format hoists
 * them into two palettes on the first two lines and stores indices in the
 * rows. Coordinates are integers scaled by 1e4 rather than decimal text.
 * Together that took the file from roughly 97 bytes per city to 37.
 */
class CityTable(val cities: List<City>) {

    companion object {

        /**
         * Parses the packed catalogue.
         *
         * Format, as emitted by `SunApp/update_cities.py`, which is the
         * authority if this comment and that script ever disagree:
         *
         * ```
         * line 1   country palette, tab-separated
         * line 2   timezone palette, tab-separated
         * line 3+  name \t fold \t countryIdx \t latE4 \t lonE4 \t tzIdx \t alt
         * ```
         *
         * `fold` is written only when it differs from `name.lowercase()`, and
         * `alt` is usually empty, so most rows carry two empty fields. That is
         * the compression: 25,007 rows pay two tab characters rather than two
         * repeated strings.
         *
         * The row loop is written the unfashionable way on purpose. It splits
         * with [String.indexOf] rather than [String.split], and reads the four
         * integer fields straight out of the line rather than through
         * `substring` plus `toInt`. `split` on a 25,007-row file allocates
         * 25,007 lists and 175,049 strings, of which we keep three per row;
         * the rest is garbage handed to a collector that then runs during the
         * user's first interaction with the app. Doing it by hand is about
         * three times faster and allocates roughly a third as much, which is
         * the difference between a parse the user notices and one they do not.
         *
         * The reader is buffered here rather than at every call site, so a
         * caller holding a bare `InputStreamReader` cannot accidentally pay a
         * syscall per character.
         *
         * @throws IllegalArgumentException if the file is truncated or a row
         *   is malformed. There is no partial-parse mode: a catalogue that is
         *   half-read is worse than one that is absent, because the failure
         *   shows up later as a place that mysteriously cannot be found.
         */
        fun parse(reader: Reader): CityTable {
            val buffered = reader as? BufferedReader ?: BufferedReader(reader, BUFFER_BYTES)

            val countryLine = buffered.readLine()
                ?: throw IllegalArgumentException("City catalogue is empty: no country palette")
            val zoneLine = buffered.readLine()
                ?: throw IllegalArgumentException("City catalogue is truncated: no timezone palette")

            // Two lines, so the convenient split is affordable here in a way it
            // is not in the row loop below.
            val countries = countryLine.split('\t')
            val zones = zoneLine.split('\t')

            // Fold the palette once: 244 entries against 25,007 cities. The
            // upstream country names happen to be anglicised ASCII today, so
            // this is currently a no-op, but the invariant that City.cfold is
            // comparable with a folded query has to hold whoever supplies the
            // names next, and 244 calls is not a price worth negotiating.
            val countryFolds = Array(countries.size) { foldQuery(countries[it]) }

            // Sizing hint only. The catalogue is 25,007 rows today and grows
            // by hundreds per regeneration rather than thousands, so this saves
            // roughly twenty grow-and-copy cycles without pinning the parser to
            // an exact count that a regeneration would falsify.
            val cities = ArrayList<City>(EXPECTED_ROWS)

            var line = buffered.readLine()
            while (line != null) {
                // The generator writes no trailing newline, but tolerate blank
                // lines rather than fail on a file that has been through an
                // editor that adds one.
                if (line.isNotEmpty()) cities.add(parseRow(line, countries, countryFolds, zones))
                line = buffered.readLine()
            }

            return CityTable(cities)
        }

        private const val BUFFER_BYTES = 1 shl 16
        private const val EXPECTED_ROWS = 26_000

        private fun parseRow(
            line: String,
            countries: List<String>,
            countryFolds: Array<String>,
            zones: List<String>,
        ): City {
            val t1 = line.indexOf('\t')
            val t2 = if (t1 < 0) -1 else line.indexOf('\t', t1 + 1)
            val t3 = if (t2 < 0) -1 else line.indexOf('\t', t2 + 1)
            val t4 = if (t3 < 0) -1 else line.indexOf('\t', t3 + 1)
            val t5 = if (t4 < 0) -1 else line.indexOf('\t', t4 + 1)
            require(t5 >= 0) { "Malformed city row, expected at least six tabs: $line" }

            // The seventh field arrived with the English-exonym pass. A file
            // written before it simply ends after tzIdx, and an empty alt is
            // exactly what such a row means, so accept both rather than force
            // a lockstep regeneration of nine apps.
            val t6 = line.indexOf('\t', t5 + 1)
            val tzEnd = if (t6 < 0) line.length else t6

            val name = line.substring(0, t1)
            val storedFold = line.substring(t1 + 1, t2)
            val countryIdx = readInt(line, t2 + 1, t3)
            val latE4 = readInt(line, t3 + 1, t4)
            val lonE4 = readInt(line, t4 + 1, t5)
            val tzIdx = readInt(line, t5 + 1, tzEnd)

            require(countryIdx in countries.indices) { "Country index $countryIdx out of range: $line" }
            require(tzIdx in zones.indices) { "Timezone index $tzIdx out of range: $line" }

            return City(
                name = name,
                // Blank means "identical to the lowercased name", which is
                // true of 80 per cent of rows and is the single biggest saving
                // in the format.
                fold = if (storedFold.isEmpty()) name.lowercase() else storedFold,
                alt = if (t6 < 0) "" else line.substring(t6 + 1),
                country = countries[countryIdx],
                cfold = countryFolds[countryIdx],
                lat = latE4 / 1e4,
                lon = lonE4 / 1e4,
                tz = zones[tzIdx],
            )
        }

        /**
         * Reads a signed decimal integer from `line[from until to]` without
         * cutting a substring first.
         *
         * `Integer.parseInt(CharSequence, int, int, int)` would do this, but it
         * is API 33 on Android and this app runs from 24. Four fields per row
         * times 25,007 rows is 100,028 substrings avoided, which is the whole
         * point of the exercise.
         */
        private fun readInt(line: String, from: Int, to: Int): Int {
            require(to > from) { "Empty numeric field in city row: $line" }
            var i = from
            val negative = line[i] == '-'
            if (negative) i++
            require(i < to) { "Numeric field is a bare sign in city row: $line" }
            var value = 0
            while (i < to) {
                val digit = line[i].code - '0'.code
                require(digit in 0..9) { "Non-numeric field in city row: $line" }
                value = value * 10 + digit
                i++
            }
            return if (negative) -value else value
        }
    }
}
