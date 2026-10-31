package no.stormberry.labs.carousel

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's carousel is its website's carousel: same heading, same tick box, same cards in the
 * same order with the same addresses, the same app marked as current. The page is the source of
 * truth, so this reads the index.html of the repository the app is built from and fails the
 * moment either side is edited alone.
 */
class LabsCarouselWebParityTest {

    private fun repoFile(name: String): File? =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, name) }
            .firstOrNull { it.exists() }

    private fun section(): String {
        val html = repoFile("index.html")
        assertTrue("index.html not found above the working directory", html != null)
        val m = Regex("""<section class="app-switcher-container".*?</section>""", RegexOption.DOT_MATCHES_ALL)
            .find(html!!.readText())
        assertTrue("no app-switcher section in index.html", m != null)
        return m!!.value
    }

    private data class WebCard(val url: String, val title: String, val current: Boolean, val hidden: Boolean)

    private fun webCards(): List<WebCard> =
        Regex("""<a href="([^"]+)" class="switcher-card"([^>]*)>.*?<span>([^<]+)</span></a>""")
            .findAll(section())
            .map {
                val attrs = it.groupValues[2]
                WebCard(it.groupValues[1], it.groupValues[3], "aria-current" in attrs, "aria-hidden=\"true\"" in attrs)
            }
            .toList()

    @Test fun theHeadingAndTheTickBoxSayWhatTheWebSays() {
        val s = section()
        assertTrue(s, s.contains(""">${LabsCarouselText.TITLE}</h2>"""))
        assertTrue(s, s.contains("""class="switcher-pause-label">${LabsCarouselText.PAUSE}</label>"""))
    }

    @Test fun theCardsAreTheWebsiteCardsInTheSameOrder() {
        val cards = webCards()
        val firstSet = cards.takeWhile { !it.hidden }
        assertTrue("the web carousel has a visible set", firstSet.isNotEmpty())
        val current = firstSet.last()
        assertTrue("the web's last card is its own app", current.current)
        val app = LabsApp.entries.single { it.title == current.title }
        assertEquals(
            firstSet.map { it.title to it.url },
            LabsApp.cardsFor(app).map { it.title to it.url },
        )
        // The copies the web hides from screen readers repeat the same set.
        assertEquals(0, cards.size % firstSet.size)
        assertTrue(cards.drop(firstSet.size).all { it.hidden })
    }
}
