package no.stormberry.labs.carousel

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every card has its icon, and every icon is the current conversion of the website's SVG: each
 * generated drawable names the SHA-256 of the SVG it was made from, so editing an SVG without
 * running svg2vd.py again fails here.
 */
class LabsCarouselIconsTest {

    private fun repoDir(name: String): File? =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, name) }
            .firstOrNull { it.isDirectory }

    @Test fun everyCardHasAnIcon() {
        for (app in LabsApp.entries) {
            assertNotEquals(app.slug, 0, labsSwitcherIcon(app.slug))
        }
        assertEquals(0, labsSwitcherIcon("no-such-app"))
    }

    @Test fun theIconsAreTheCurrentConversionOfTheWebsiteIcons() {
        val svgs = repoDir("switcher-icons")
        assertTrue("switcher-icons not found above the working directory", svgs != null)
        val drawables = File("src/main/res/drawable")
        assertTrue("run from the app module", drawables.isDirectory)
        val slugs = svgs!!.listFiles { f -> f.name.endsWith(".svg") }!!.map { it.nameWithoutExtension }.sorted()
        assertEquals(LabsApp.entries.map { it.slug }.sorted(), slugs)
        for (slug in slugs) {
            val svg = File(svgs, "$slug.svg")
            val xml = File(drawables, "labs_switcher_$slug.xml")
            assertTrue("${xml.name} missing: run svg2vd.py", xml.exists())
            val sha = MessageDigest.getInstance("SHA-256").digest(svg.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertTrue("${xml.name} is stale: run svg2vd.py", xml.readText().contains("(sha256 $sha)"))
        }
    }
}
