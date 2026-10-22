package no.stormberry.sunapp.alarm

import android.content.Context
import java.io.File

/**
 * DEBUG BUILDS ONLY (src/debug): a way for the emulator test to make the ringing service's
 * foreground start fail, so the backup ring of 1.1.4 (A-4) can be seen working. The test creates
 * the marker file with run-as, which works on a debuggable build only, and removes it after. The
 * release build has its own TestHooks (src/release) that always answers false. Ported from
 * EclipseTracker.
 */
internal object TestHooks {
    private const val FAIL_FOREGROUND = "test-fail-foreground"

    fun failForeground(context: Context): Boolean =
        runCatching { File(context.createDeviceProtectedStorageContext().filesDir, FAIL_FOREGROUND).exists() }.getOrDefault(false)
}
