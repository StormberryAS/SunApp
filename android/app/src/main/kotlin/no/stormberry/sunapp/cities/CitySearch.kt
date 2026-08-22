package no.stormberry.sunapp.cities

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.cos

/**
 * Atomic letters that NFD cannot take apart.
 *
 * Normalisation splits `é` into `e` plus a combining acute, so stripping the
 * combining marks folds it. It does nothing at all for `ø`, `æ`, `ð`, `ł` and
 * their friends: those are single indivisible code points with no base letter
 * to fall back to, and NFD leaves them exactly as they were. Without this map
 * a Norwegian typing "tromso" gets nothing back for Tromsø, which is the whole
 * country's problem rather than an edge case.
 *
 * KEPT IN SYNC, BY HAND, with `ATOMIC` in `SunApp/cities.js` and `ATOMIC_FOLD`
 * in `SunApp/update_cities.py`. All three must agree. The Python one produces
 * the `fold` column that ships in the asset; the JavaScript one folds queries
 * in the web app; this one folds queries on the phone. If any pair drifts, the
 * stored folds and the typed query stop describing the same alphabet and
 * searches quietly return the wrong thing rather than failing loudly.
 */
private val ATOMIC: Map<Char, String> = mapOf(
    'ø' to "o",   // ø  o with stroke
    'æ' to "ae",  // æ  ash
    'ð' to "d",   // ð  eth
    'þ' to "th",  // þ  thorn
    'ł' to "l",   // ł  l with stroke
    'đ' to "d",   // đ  d with stroke
    'ß' to "ss",  // ß  sharp s
    'œ' to "oe",  // œ  ligature oe
    'ŋ' to "n",   // ŋ  eng
    'ħ' to "h",   // ħ  h with stroke
    'ı' to "i",   // ı  dotless i
    'ŧ' to "t",   // ŧ  t with stroke
    'ĳ' to "ij",  // ĳ  ligature ij
)

/**
 * The Combining Diacritical Marks block, U+0300 to U+036F.
 *
 * Written as escapes rather than as literals because the literals are
 * zero-width and would be invisible in this file, and an invisible constant is
 * an invitation to break it by accident. This is the same range the JavaScript
 * regex uses, so the two strip identically.
 */
private const val COMBINING_FIRST = '\u0300'
private const val COMBINING_LAST = '\u036F'

/**
 * Reduces a string to the searchable form the catalogue stores.
 *
 * This is a direct port of `globalThis.foldQuery` in `SunApp/cities.js` and it
 * must stay one. The stored `fold` and `alt` columns were produced by the
 * Python twin of the same function, so a query folded any other way is being
 * compared against a form nobody wrote.
 *
 * The four steps are ordered, and the order matters:
 *
 *  1. NFD, which separates `Herāt` into `Hera` plus a combining macron plus `t`.
 *  2. Drop everything in the combining block, leaving `Herat`.
 *  3. Lowercase, whole-string rather than per-character, because Java and
 *     JavaScript both apply final-sigma and other special-casing rules that a
 *     character-at-a-time loop would miss.
 *  4. Expand the atomic letters above, which had to wait for step 3 so that
 *     `Ø` and `ø` take the same path.
 *
 * Cheap enough to call per keystroke on a short query, and called exactly once
 * per country at parse time. It is never called per city: those folds ship
 * precomputed in the asset.
 */
fun foldQuery(s: String): String {
    val decomposed = Normalizer.normalize(s, Normalizer.Form.NFD)

    val stripped = StringBuilder(decomposed.length)
    for (ch in decomposed) {
        if (ch < COMBINING_FIRST || ch > COMBINING_LAST) stripped.append(ch)
    }

    val lowered = stripped.toString().lowercase()

    // Most strings contain no atomic letter at all, so scan first and only
    // build a second buffer when there is something to replace.
    var needsExpansion = false
    for (ch in lowered) {
        if (ATOMIC.containsKey(ch)) {
            needsExpansion = true
            break
        }
    }
    if (!needsExpansion) return lowered

    val expanded = StringBuilder(lowered.length + 4)
    for (ch in lowered) {
        val replacement = ATOMIC[ch]
        if (replacement != null) expanded.append(replacement) else expanded.append(ch)
    }
    return expanded.toString()
}

/**
 * Search and nearest-neighbour timezone resolution over a [CityTable].
 *
 * Pure: no `android.*`, no state, no cache. Both entry points are linear scans
 * of the 25,007-row list, which sounds alarming and is not. A scan comparing
 * precomputed folds costs about 0.8 ms on a desktop JVM and a few milliseconds
 * on a phone, which is inside a frame and well inside the gap between two
 * keystrokes, and it is the only structure that answers a leading-wildcard
 * query without an index that would be larger than the data itself.
 */
object CitySearch {

    /**
     * Best [limit] matches for a typed query, prefix matches first.
     *
     * Mirrors `onCityInput` in `SunApp/app.js`, including its ordering, and the
     * ordering is the part that earns its keep. A flat substring filter over
     * 25,007 cities buries the obvious answer: typing "erdal" returned
     * Cloverdale, South Riverdale and Terdāl above Erdal, and with eight rows
     * on screen the place actually being typed fell off the list. Ranking
     * prefix matches ahead of substring matches fixes that without any scoring
     * machinery.
     *
     * Within each of the two bands, order is the table's own order, which the
     * generator sorts by country then name. That is arbitrary but stable, and
     * stable matters more than clever here: the same three keystrokes must
     * produce the same list every time, or the row a person is reaching for
     * moves under their finger.
     *
     * A match on [City.alt] counts as a name match, not a country one, because
     * "cologne" is a name for Köln; a person typing it is naming the city, not
     * narrowing by country. [City.cfold] is checked for substring matches only,
     * so that typing "no" does not put all of Norway ahead of Nome.
     *
     * @param limit rows to return. Eight by default, matching the web app's
     *   dropdown, which is as many as fits on a phone above the keyboard.
     */
    fun search(table: CityTable, query: String, limit: Int = 8): List<City> {
        if (limit <= 0) return emptyList()

        val folded = foldQuery(query.trim())
        // A query of nothing but diacritics folds away to nothing, and matching
        // the empty string against every city would hand back an arbitrary
        // first eight. The web app has the same hole; refusing it here is a
        // deliberate improvement rather than a drift, because it can only ever
        // suppress a meaningless result.
        if (folded.isEmpty()) return emptyList()

        val prefix = ArrayList<City>(limit)
        val substring = ArrayList<City>(limit)

        for (city in table.cities) {
            if (city.fold.startsWith(folded) || city.alt.startsWith(folded)) {
                prefix.add(city)
                // Prefix matches always outrank substring ones, so once there
                // are enough of them nothing later in the table can change the
                // answer. Common short queries stop within the first few
                // thousand rows instead of scanning all 25,007.
                if (prefix.size >= limit) return prefix
            } else if (
                city.fold.contains(folded) ||
                // The emptiness guard is the one hand-optimisation in this
                // chain and it earns its place: 97 per cent of rows have no
                // exonym, and `contains` is a substring search rather than the
                // length comparison `startsWith` gets away with above.
                (city.alt.isNotEmpty() && city.alt.contains(folded)) ||
                city.cfold.contains(folded)
            ) {
                // Only ever used to top up a short prefix band, so there is
                // never a reason to hold more than `limit` of them. The scan
                // still has to run to the end, because a prefix match can
                // appear at any row.
                if (substring.size < limit) substring.add(city)
            }
        }

        if (prefix.isEmpty()) return substring
        if (substring.isEmpty()) return prefix

        val merged = ArrayList<City>(limit)
        merged.addAll(prefix)
        for (city in substring) {
            if (merged.size >= limit) break
            merged.add(city)
        }
        return merged
    }

    /**
     * IANA zone of the catalogue city nearest to a coordinate, or null if the
     * table is empty.
     *
     * This exists for the manual-coordinate path, where a person types a
     * latitude and longitude and there is no city to inherit a zone from. It is
     * a port of `nearestCityTimezone` in `SunApp/app.js`. Timezones are large
     * political regions and the catalogue is dense wherever people live, so the
     * nearest city's zone is the right answer in practice and needs no lookup
     * table of zone polygons, no network call and no extra megabyte in the APK.
     *
     * Distance is equirectangular and left squared, since only the ordering is
     * wanted. Longitude degrees are narrowed by `cos(mean latitude)` so that a
     * degree of longitude in Norway is not treated as a degree at the equator,
     * and the difference is wrapped at 180 so that a point just east of the
     * antimeridian is not measured the long way round the planet.
     *
     * ## Caveat above roughly 80 degrees
     *
     * The `cos(mean latitude)` weight collapses towards zero as it approaches
     * the pole, so at extreme latitudes longitude stops contributing to the
     * distance at all and the pick becomes effectively longitude-blind: it
     * returns whichever city is nearest in latitude alone, which can be on the
     * far side of the Arctic. The catalogue's northernmost entry is
     * Longyearbyen at 78.22 N, so the failure is only reachable by typing
     * coordinates by hand, and the zones up there are sparse enough that the
     * answer is usually right anyway. It is documented rather than fixed
     * because the fix is a proper great-circle distance for a case no user of
     * this app has yet had, and the honest note costs nothing.
     */
    fun nearestTimezone(table: CityTable, latDeg: Double, lonDeg: Double): String? {
        var best: City? = null
        var bestDistance = Double.MAX_VALUE

        for (city in table.cities) {
            var deltaLon = abs(city.lon - lonDeg)
            if (deltaLon > 180.0) deltaLon = 360.0 - deltaLon
            val deltaLat = city.lat - latDeg
            val weightedLon = deltaLon * cos(Math.toRadians((latDeg + city.lat) / 2.0))
            val distance = weightedLon * weightedLon + deltaLat * deltaLat
            if (distance < bestDistance) {
                bestDistance = distance
                best = city
            }
        }

        return best?.tz
    }
}
