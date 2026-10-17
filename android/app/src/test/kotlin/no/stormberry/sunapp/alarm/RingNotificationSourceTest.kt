package no.stormberry.sunapp.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the ringing notification against the defect fixed in 1.1.3: a ringing notification that
 * Android treats as silent, which loses its full-screen intent. There were two ways in: the app's
 * own `setSilent(true)`, and the system's auto-grouping with a leftover notice.
 *
 * NotificationCompat implements `setSilent` by putting the notification in a group called
 * "silent" with GROUP_ALERT_SUMMARY, and Android never alerts a muted group child, so the
 * full-screen intent is dropped and a locked phone shows only a notification. Nothing about
 * that fails to compile, lint does not flag it, and the build still rings: the only symptom is
 * a ring screen that never appears, which is why it reached a release.
 *
 * This reads AlarmService.kt as text because the project tests on the plain JVM, where
 * NotificationCompat cannot build a Notification (the framework classes are stubs), and adding
 * Robolectric for one assertion would be the first framework emulation in a test suite that
 * has deliberately avoided it. It is a static guard, not a behavioural test: the behaviour
 * itself is proven on an emulator with the screen locked, as recorded in the release notes.
 * Comments are stripped before matching, so explaining the rule in the source does not trip it.
 */
class RingNotificationSourceTest {

    private val code: String by lazy { stripComments(findSource().readText()) }

    @Test
    fun theRingingNotificationIsNotSilentAndKeepsItsFullScreenIntent() {
        val body = functionBody("buildRingNotification")

        assertTrue("posts on the ringing channel", body.contains("Builder(this, CHANNEL_RING)"))
        assertTrue("carries the full-screen intent", body.contains(".setFullScreenIntent(ring, true)"))
        assertFalse(
            "setSilent suppresses the full-screen intent; the channel provides the silence",
            body.contains(".setSilent("),
        )
        assertTrue(body.contains(".setCategory(NotificationCompat.CATEGORY_ALARM)"))
        assertTrue(body.contains(".setPriority(NotificationCompat.PRIORITY_MAX)"))
        assertTrue(body.contains(".setVisibility(NotificationCompat.VISIBILITY_PUBLIC)"))
    }

    /**
     * The second way the ring screen was lost: a leftover snoozed or missed notice makes two
     * notifications, Android auto-groups them and marks the auto-grouped ring notification
     * silent itself. The notice has to be gone before the ring notification is posted.
     */
    @Test
    fun theNoticeIsCancelledBeforeTheRingNotificationIsPosted() {
        val body = functionBody("onStartCommand")
        val cancel = body.indexOf("cancelNotice(this)")
        val post = body.indexOf("startInForeground(buildRingNotification(")
        assertTrue("onStartCommand cancels the notice", cancel >= 0)
        assertTrue("onStartCommand posts the ring notification", post >= 0)
        assertTrue("the notice is cancelled first", cancel < post)
        assertTrue(functionBody("cancelNotice").contains(".cancel(NOTIFICATION_ID_NOTICE)"))
    }

    /** The general form of the rule, so a second full-screen notification cannot repeat it. */
    @Test
    fun noNotificationWithAFullScreenIntentIsSilent() {
        val chains = builderChains()
        assertTrue("found the three notification builders", chains.size >= 3)
        for (chain in chains.filter { it.contains(".setFullScreenIntent(") }) {
            assertFalse("silent full-screen notification:\n$chain", chain.contains(".setSilent("))
        }
    }

    /**
     * With setSilent gone, the channel is the only thing keeping the notification quiet, so
     * the service's crescendo tone is the one sound. A channel's sound is fixed once created,
     * so this also pins the id: CHANNEL_RING has had no sound since 1.1.0, and keeping the id
     * keeps every installed copy's existing channel, and any choices the user made on it.
     */
    @Test
    fun theRingingChannelHasNoSoundAndNoVibrationOfItsOwn() {
        assertEquals("alarm_ring", AlarmService.CHANNEL_RING)
        val channels = functionBody("ensureChannels")
        val ring = channels.substringAfter("Builder(CHANNEL_RING,").substringBefore(".build()")
        assertTrue(ring.contains("IMPORTANCE_HIGH"))
        assertTrue("no channel sound", ring.contains(".setSound(null, null)"))
        assertTrue("no channel vibration", ring.contains(".setVibrationEnabled(false)"))
    }

    private fun functionBody(name: String): String {
        val start = code.indexOf("fun $name(")
        assertTrue("fun $name not found in AlarmService.kt", start >= 0)
        val open = code.indexOf('{', start)
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return code.substring(open, i + 1)
            }
        }
        error("unbalanced braces after fun $name")
    }

    private fun builderChains(): List<String> =
        Regex("""NotificationCompat\.Builder\(.*?\.build\(\)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(code)
            .map { it.value }
            .toList()

    private companion object {
        const val RELATIVE = "src/main/kotlin/no/stormberry/sunapp/alarm/AlarmService.kt"

        /** Gradle runs unit tests from the module directory; walk up in case an IDE does not. */
        fun findSource(): File {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            while (dir != null) {
                for (candidate in listOf(File(dir, RELATIVE), File(dir, "app/$RELATIVE"), File(dir, "android/app/$RELATIVE"))) {
                    if (candidate.isFile) return candidate
                }
                dir = dir.parentFile
            }
            error("AlarmService.kt not found above ${System.getProperty("user.dir")}")
        }

        /**
         * Drops block comments and line comments. Naive on purpose: no string literal in
         * AlarmService.kt contains a comment marker. A URL in a string there would need a
         * real lexer here.
         */
        fun stripComments(source: String): String =
            source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .lines()
                .joinToString("\n") { line ->
                    val at = line.indexOf("//")
                    if (at >= 0) line.substring(0, at) else line
                }
    }
}
