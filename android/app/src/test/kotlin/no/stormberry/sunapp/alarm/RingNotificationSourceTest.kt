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
        val cancel = body.indexOf("ringAlone(this)")
        val post = body.indexOf("startInForeground(buildRingNotification(")
        assertTrue("onStartCommand cancels the notices", cancel >= 0)
        assertTrue("onStartCommand posts the ring notification", post >= 0)
        assertTrue("the notices are cancelled first", cancel < post)
        // Since 1.1.4 notices are tagged per rule, so every notice is cancelled, whatever its tag.
        val alone = functionBody("ringAlone")
        assertTrue(alone.contains("activeNotifications"))
        assertTrue(alone.contains("it.id == NOTIFICATION_ID_NOTICE"))
        assertTrue(alone.contains("manager.cancel(it.tag, it.id)"))
    }

    /**
     * 1.1.4, A-2: the backup ring notification is the alarm when the service cannot ring, so it
     * obeys the same two rules: it is not silent, and it is posted alone.
     */
    @Test
    fun theBackupRingIsAudibleAndPostedAlone() {
        val backup = functionBody("buildBackupNotification")
        assertTrue("its own channel, with a sound", backup.contains("Builder(context, CHANNEL_RING_BACKUP)"))
        assertTrue(backup.contains(".setFullScreenIntent(ring, true)"))
        assertFalse(backup.contains(".setSilent("))
        assertFalse(backup.contains(".setOnlyAlertOnce("))
        assertTrue("repeats until answered", backup.contains("Notification.FLAG_INSISTENT"))
        assertTrue(backup.contains(".setTimeoutAfter(AUTO_SILENCE_MS)"))
        assertTrue(backup.contains(".setDeleteIntent(gone)"))

        val channels = functionBody("ensureChannels")
        val channel = channels.substringAfter("Builder(CHANNEL_RING_BACKUP,").substringBefore(".setShowBadge(")
        assertTrue(channel.contains("IMPORTANCE_HIGH"))
        assertTrue("the alarm sound", channel.contains("Settings.System.DEFAULT_ALARM_ALERT_URI"))
        assertTrue("on the alarm stream", channel.contains("AudioAttributes.USAGE_ALARM"))
        assertTrue(channel.contains(".setVibrationEnabled(true)"))
        assertFalse(channel.contains(".setSound(null"))

        val post = functionBody("postBackupRing")
        assertTrue("posted alone", post.indexOf("ringAlone(context)") in 0 until post.indexOf("postPlain("))
    }

    /**
     * 1.1.4, A-6: the snoozed notice gets Dismiss and is tagged per rule; it still carries no
     * full-screen intent, which is what makes its setSilent safe.
     */
    @Test
    fun theSnoozedNoticeHasDismissAndNoFullScreenIntent() {
        val body = functionBody("postSnoozedNotice")
        assertTrue(body.contains("Builder(context, CHANNEL_NOTICES)"))
        assertFalse(body.contains(".setFullScreenIntent("))
        assertTrue(body.contains(".addAction(0, \"Dismiss\", dismissPendingIntent(context, snooze.ruleId))"))
        assertTrue(body.contains("tag = snooze.ruleId"))
        val missed = functionBody("postMissed")
        assertFalse(missed.contains(".setFullScreenIntent("))
    }

    /** A-4: a refused foreground falls back to the backup ring held in the foreground, never to stopping silently. */
    @Test
    fun aRefusedForegroundRingsThroughTheBackup() {
        val body = functionBody("onStartCommand")
        assertTrue(body.contains("ringBackupFromService(alarm)"))
        assertFalse("1.1.3's plain fallback is gone", code.contains("postFallbackNotification"))
        val backup = functionBody("ringBackupFromService")
        assertTrue(backup.contains("ServiceCompat.startForeground(this, NOTIFICATION_ID_RING, backup"))
    }

    /** The general form of the rule, so a second full-screen notification cannot repeat it. */
    @Test
    fun noNotificationWithAFullScreenIntentIsSilent() {
        val chains = builderChains()
        assertTrue("found the four notification builders", chains.size >= 4)
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
