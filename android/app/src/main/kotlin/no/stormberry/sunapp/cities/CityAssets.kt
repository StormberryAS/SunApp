package no.stormberry.sunapp.cities

import android.content.Context
import java.io.IOException
import java.io.InputStreamReader

/**
 * The only place in the app that touches `AssetManager`.
 *
 * Everything else in this package takes a [Reader][java.io.Reader] or a
 * [CityTable], which is what keeps the parser and the search testable under
 * plain JUnit with no device and no Robolectric.
 */
object CityAssets {

    /**
     * Path inside the APK's assets.
     *
     * The file is not checked in under `app/src/main/assets/`. It is copied
     * there at build time by the `copyCityData` Gradle task from
     * `SunApp/data/cities.tsv`, which is the single source of truth shared with
     * the web app and the eight sibling Labs apps. Duplicating it here would
     * guarantee the two drift.
     */
    private const val ASSET_PATH = "data/cities.tsv"

    @Volatile
    private var cached: CityTable? = null

    /**
     * Loads and parses the catalogue, once per process.
     *
     * Parsing the 25,007 rows costs 12 to 26 ms warm on a desktop JVM, 35 to
     * 50 ms on the first run with a cold JIT, and retains roughly 4.1 MiB.
     * Expect a multiple of the time on a mid-range phone. Paying that once is fine;
     * paying it on every recomposition, rotation or return from the background
     * is not, hence the cache. It is held for the life of the process and
     * never invalidated, because the asset cannot change without the app being
     * replaced.
     *
     * Double-checked locking with a `@Volatile` field rather than `by lazy`,
     * because `lazy` cannot take the [Context] the asset needs without either
     * capturing one in a static field, which leaks whatever `Context` happened
     * to be first, or being initialised from `Application`, which would put the
     * parse on the startup path for a screen the user may never open.
     *
     * The `Context` argument is used and discarded inside this call; nothing
     * here retains it.
     *
     * Callers should treat this as blocking IO, and must not call it during
     * startup: that would trade a delay nobody notices for one everybody does,
     * on behalf of a screen the user may never open. First focus of the search
     * field is the right moment. If that ever shows up as a dropped frame on a
     * slow device, move the call to a background thread rather than splitting
     * the asset; the file is one thing precisely so that it stays one thing.
     *
     * @throws IOException if the asset is missing from the APK, which means the
     *   `copyCityData` task did not run and the build is broken.
     * @throws IllegalArgumentException if the asset is malformed.
     */
    fun load(context: Context): CityTable {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val table = context.assets.open(ASSET_PATH).use { stream ->
                // The asset is stored deflated, so AssetManager hands back an
                // inflating stream. CityTable.parse adds the buffering, which
                // is what keeps that from becoming a syscall per character.
                CityTable.parse(InputStreamReader(stream, Charsets.UTF_8))
            }
            cached = table
            return table
        }
    }
}
