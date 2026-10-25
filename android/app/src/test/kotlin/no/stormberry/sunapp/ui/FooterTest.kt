package no.stormberry.sunapp.ui

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import no.stormberry.sunapp.ui.components.CITY_LIST_DATE
import no.stormberry.sunapp.ui.components.FooterIcons
import no.stormberry.sunapp.ui.components.FooterTargets
import no.stormberry.sunapp.ui.components.HERO_TAGLINE
import no.stormberry.sunapp.ui.components.LICENCE_BSD_2_CLAUSE
import no.stormberry.sunapp.ui.components.LICENCE_CC_BY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.time.LocalDate

/**
 * The parts of the hero and the footer that are not layout: where every footer link goes,
 * that the icon row's marks are well formed, and the two bits of copy that are rules.
 *
 * The footer sentences are not retyped here; a test that copied them would only assert that
 * two files agree and would have to change with every edit of the wording.
 */
class FooterTest {

    @Test
    fun `the links CheckLink's footer has go to the same places`() {
        assertEquals("https://stormberry.as", FooterTargets.SITE)
        assertEquals("https://linkedin.com/company/StormberryAS", FooterTargets.LINKEDIN)
        assertEquals("https://github.com/StormberryAS", FooterTargets.GITHUB)
        assertEquals(
            "nostr:npub1zz9w77p2jkn95t0st9tcuea8zdn2vknf0ctrwyd4g46w3kswdccqesr0pa",
            FooterTargets.NOSTR,
        )
        assertEquals(
            "http://stormberryr7fz5xdopi7f265d7id27bgizc6uxeliaepzstompcfpyd.onion",
            FooterTargets.ONION,
        )
        assertEquals(
            "https://virksomhet.brreg.no/nb/oppslag/enheter/937751249",
            FooterTargets.ORG_REGISTER,
        )
    }

    @Test
    fun `Privacy and Disclaimer open what this app has always opened`() {
        assertEquals("https://stormberry.as/privacy.html", FooterTargets.PRIVACY)
        assertEquals("https://sun.stormberry.as/disclaimer.html", FooterTargets.DISCLAIMER)
    }

    @Test
    fun `every target is a plain web address or a nostr address, with no sign-in details`() {
        for (url in FooterTargets.all) {
            val uri = URI(url)
            assertTrue(url, uri.scheme in setOf("https", "http", "nostr"))
            assertEquals(url, null, uri.rawUserInfo)
            if (uri.scheme == "nostr") {
                assertTrue(url, uri.isOpaque && !uri.rawSchemeSpecificPart.startsWith("/"))
            } else {
                assertFalse(url, uri.host.isNullOrEmpty())
            }
        }
        // Thirteen links in all: wordmark, four icons, the register, Privacy and Disclaimer,
        // then the five of the credit sentence.
        assertEquals(13, FooterTargets.all.size)
        assertEquals(13, FooterTargets.all.toSet().size)
    }

    @Test
    fun `the credit sentence links each part to its source and each licence to its text`() {
        assertEquals("https://github.com/mourner/suncalc", FooterTargets.SUNCALC)
        assertEquals(
            "https://github.com/mourner/suncalc/blob/master/LICENSE",
            FooterTargets.SUNCALC_LICENCE,
        )
        assertEquals("https://www.geonames.org/", FooterTargets.GEONAMES)
        assertEquals("https://www.kartverket.no/en/api-and-data", FooterTargets.KARTVERKET)
        assertEquals("https://creativecommons.org/licenses/by/4.0/", FooterTargets.CC_BY)
        assertEquals(5, FooterTargets.credits.size)
    }

    @Test
    fun `every licence label stays on one line`() {
        for (label in listOf(LICENCE_BSD_2_CLAUSE, LICENCE_CC_BY)) {
            assertFalse(label, label.contains(' '))
            assertFalse(label, label.contains('-'))
        }
        assertEquals("BSD\u00A02\u2011Clause", LICENCE_BSD_2_CLAUSE)
        assertEquals("CC\u00A0BY\u00A04.0", LICENCE_CC_BY)
    }

    @Test
    fun `the footer credits only third-party projects and data, and NOTICE documents every credit and what was left out`() {
        // Marcos, 2026-10-24: icons, programming languages, platform libraries and the font
        // are credited in NOTICE and the README, not in the footer.
        val footer = FooterTargets.all.joinToString("\n")
        for (left in listOf(
            "lucide", "JetBrains", "androidx", "desugar", "openjdk", "apache.org", "rsms.me",
            "openfontlicense",
        )) {
            assertFalse(left, footer.contains(left, ignoreCase = true))
        }
        val notice = repoFile("NOTICE")
        assumeTrue("NOTICE not reachable from the test's working directory", notice != null)
        val text = notice!!.readText()
        val needles = listOf(
            "SunCalc", "Inter", "GeoNames", "Kartverket", "BSD 2-Clause", "OFL-1.1", "CC BY 4.0",
            "desugar_jdk_libs", "Classpath Exception", "Lucide", "ISC", "ostrich", "onion",
            "Kotlin", "Jetpack", "Material", "Apache License 2.0",
        )
        for (needle in needles) {
            assertTrue(needle, text.contains(needle))
        }
        val noticeUrls = listOf(
            FooterTargets.SUNCALC, "https://rsms.me/inter/", FooterTargets.GEONAMES,
            "https://github.com/google/desugar_jdk_libs", "https://openjdk.org/legal/gplv2+ce.html",
            "https://github.com/lucide-icons/lucide",
            "https://github.com/lucide-icons/lucide/blob/main/LICENSE",
            "https://github.com/JetBrains/kotlin", "https://github.com/androidx/androidx",
            "https://www.apache.org/licenses/LICENSE-2.0",
        )
        for (url in noticeUrls) {
            assertTrue(url, text.contains(url))
        }
        // The README names them too, so a reader of either finds the full list.
        val readme = repoFile("README.md")
        assumeTrue("README.md not reachable from the test's working directory", readme != null)
        val readmeText = readme!!.readText()
        for (needle in listOf("SunCalc", "GeoNames", "Kartverket", "Inter", "Lucide", "Kotlin", "Jetpack")) {
            assertTrue(needle, readmeText.contains(needle))
        }
    }

    @Test
    fun `the four footer marks build, and the Nostr outline survives parsing whole`() {
        listOf(FooterIcons.linkedIn, FooterIcons.gitHub, FooterIcons.nostr, FooterIcons.tor)
            .forEach { assertTrue(it.name, it.defaultWidth.value == 24f) }
        // 3,169 characters of path data are 138 segments, one closed contour; a truncated or
        // mis-split outline would have fewer, or would not end in a close.
        val nostrNodes = (FooterIcons.nostr.root[0] as VectorPath).pathData
        assertEquals(138, nostrNodes.size)
        assertEquals(PathNode.Close, nostrNodes.last())
    }

    @Test
    fun `the city list date is a real date, and not one from the future`() {
        val date = LocalDate.parse(CITY_LIST_DATE)
        assertFalse(date.isAfter(LocalDate.now().plusDays(1)))
    }

    @Test
    fun `the hero tagline is the website's, word for word`() {
        val html = repoFile("index.html")
        assumeTrue("index.html not reachable from the test's working directory", html != null)
        val match = Regex("""<p class="tagline">(.*?)</p>""").find(html!!.readText())
        assertTrue("no tagline in index.html", match != null)
        assertEquals(match!!.groupValues[1].replace("<br>", "\n"), HERO_TAGLINE)
    }

    @Test
    fun `the tagline follows the house rules on dashes`() {
        assertFalse(HERO_TAGLINE.contains('\u2014'))
        assertFalse(HERO_TAGLINE.contains("--"))
    }

    /** A file at the repository root, found from the module directory the JVM test runs in. */
    private fun repoFile(name: String): File? =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .take(4)
            .map { File(it, name) }
            .firstOrNull { it.isFile }
}
