package no.stormberry.sunapp.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole of the first-run notice that is not layout: whether it opens.
 *
 * `shouldShowFirstRunNotice` takes two integers and touches no Context, which is what lets
 * every branch be asserted on a plain JVM rather than needing Robolectric or a device. The
 * copy itself is not tested here; a test that retyped the sentences would only assert that
 * two files agree with each other, and would have to be edited every time the wording did.
 */
class FirstRunNoticeTest {

    @Test
    fun `a fresh install is shown the notice`() {
        // 0 is what SharedPreferences returns when the key has never been written.
        assertTrue(shouldShowFirstRunNotice(seenVersion = 0))
    }

    @Test
    fun `an install that has acknowledged this version is never shown it again`() {
        assertFalse(shouldShowFirstRunNotice(seenVersion = FIRST_RUN_NOTICE_VERSION))
    }

    @Test
    fun `materially new wording is shown to an install that read the old wording`() {
        assertTrue(shouldShowFirstRunNotice(seenVersion = 1, currentVersion = 2))
    }

    @Test
    fun `a downgrade or a restored backup does not resurrect a notice already read`() {
        // The install has read version 3 on a later APK. This build only knows version 2,
        // and showing an older, shorter notice would be a regression dressed as diligence.
        assertFalse(shouldShowFirstRunNotice(seenVersion = 3, currentVersion = 2))
    }

    @Test
    fun `the shipped version is above the fresh-install sentinel`() {
        // Guards the one mistake that would silently disable the notice for everybody: a
        // FIRST_RUN_NOTICE_VERSION of 0 is indistinguishable from having seen nothing, so
        // 0 < 0 is false and no install would ever be shown it.
        assertTrue(FIRST_RUN_NOTICE_VERSION >= 1)
    }
}
