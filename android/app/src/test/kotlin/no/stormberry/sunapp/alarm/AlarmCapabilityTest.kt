package no.stormberry.sunapp.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision half of [AlarmCapability], which is the only part of the alarm runtime a JVM
 * test can reach at all.
 *
 * Everything else in `alarm/` below the engine talks to `AlarmManager`, `NotificationManager`
 * or a foreground service, and is covered by the manual device matrix instead. What is tested
 * here is the part most likely to be wrong in a way nobody notices: the API-level rules. A
 * gap reported on a version where the switch does not exist is a permission sheet the user
 * cannot dismiss and cannot satisfy, on the oldest and least powerful devices this app
 * supports.
 */
class AlarmCapabilityTest {

    private companion object {
        const val NOUGAT = 24
        const val R = 30
        const val S = 31
        const val TIRAMISU = 33
        const val UPSIDE_DOWN_CAKE = 34
    }

    @Test
    fun reportsNothingWhenThePlatformAllowsEverything() {
        val gaps = AlarmCapability.gapsFrom(
            sdkInt = UPSIDE_DOWN_CAKE,
            notificationsEnabled = true,
            canScheduleExactAlarms = true,
            canUseFullScreenIntent = true,
        )

        assertEquals(emptyList<AlarmCapabilityGap>(), gaps)
    }

    /**
     * The regression this file exists for. On API 24 to 30 there is no exact-alarm toggle and
     * no full-screen-intent toggle, so even a probe that answers false must produce nothing to
     * ask the user for.
     */
    @Test
    fun neverReportsUnaskableGapsBeforeAndroid12() {
        for (sdk in NOUGAT..R) {
            val gaps = AlarmCapability.gapsFrom(
                sdkInt = sdk,
                notificationsEnabled = true,
                canScheduleExactAlarms = false,
                canUseFullScreenIntent = false,
            )

            assertEquals("API $sdk should have no askable gaps", emptyList<AlarmCapabilityGap>(), gaps)
        }
    }

    @Test
    fun reportsBlockedNotificationsOnEveryVersion() {
        for (sdk in listOf(NOUGAT, R, S, TIRAMISU, UPSIDE_DOWN_CAKE)) {
            val gaps = AlarmCapability.gapsFrom(
                sdkInt = sdk,
                notificationsEnabled = false,
                canScheduleExactAlarms = true,
                canUseFullScreenIntent = true,
            )

            assertEquals("API $sdk", listOf(AlarmCapabilityGap.NOTIFICATIONS), gaps)
        }
    }

    @Test
    fun reportsExactAlarmsFromAndroid12Onwards() {
        val onAndroid11 = AlarmCapability.gapsFrom(R, true, canScheduleExactAlarms = false, canUseFullScreenIntent = true)
        val onAndroid12 = AlarmCapability.gapsFrom(S, true, canScheduleExactAlarms = false, canUseFullScreenIntent = true)

        assertTrue(onAndroid11.isEmpty())
        assertEquals(listOf(AlarmCapabilityGap.EXACT_ALARMS), onAndroid12)
    }

    /** Full-screen intents were ungated until Android 14, including on 12 and 13. */
    @Test
    fun reportsFullScreenIntentOnlyFromAndroid14Onwards() {
        val onAndroid13 = AlarmCapability.gapsFrom(TIRAMISU, true, true, canUseFullScreenIntent = false)
        val onAndroid14 = AlarmCapability.gapsFrom(UPSIDE_DOWN_CAKE, true, true, canUseFullScreenIntent = false)

        assertTrue(onAndroid13.isEmpty())
        assertEquals(listOf(AlarmCapabilityGap.FULL_SCREEN_INTENT), onAndroid14)
    }

    /**
     * Worst first, so a caller with room for one row shows the one that means the alarm will
     * not ring at all rather than the one that means it will not take over the screen.
     */
    @Test
    fun ordersGapsWorstFirst() {
        val gaps = AlarmCapability.gapsFrom(
            sdkInt = UPSIDE_DOWN_CAKE,
            notificationsEnabled = false,
            canScheduleExactAlarms = false,
            canUseFullScreenIntent = false,
        )

        assertEquals(
            listOf(
                AlarmCapabilityGap.NOTIFICATIONS,
                AlarmCapabilityGap.EXACT_ALARMS,
                AlarmCapabilityGap.FULL_SCREEN_INTENT,
            ),
            gaps,
        )
    }

    /**
     * Exactly one gap stops an alarm ringing. The other two degrade it, and the UI copy for
     * the two cases is different in kind: one keeps the alarm and warns, the other has to
     * tell the user their alarm cannot work.
     */
    @Test
    fun onlyBlockedNotificationsAreFatal() {
        assertTrue(AlarmCapabilityGap.NOTIFICATIONS.blocking)
        assertFalse(AlarmCapabilityGap.EXACT_ALARMS.blocking)
        assertFalse(AlarmCapabilityGap.FULL_SCREEN_INTENT.blocking)
    }

    /**
     * 1.1.4, A-1: the "Ringing alarms" channel blocked or turned below High is the
     * NOTIFICATIONS gap, from Android 8 (where channels begin). Importance values are
     * NotificationManager's: NONE 0, MIN 1, LOW 2, DEFAULT 3, HIGH 4, MAX 5.
     */
    @Test
    fun aRingChannelBlockedOrBelowHighCountsAsNotificationsOff() {
        for (sdk in listOf(26, S, TIRAMISU, UPSIDE_DOWN_CAKE, 36)) {
            for (importance in listOf(0, 1, 2, 3)) {
                assertEquals(
                    "API $sdk, importance $importance",
                    listOf(AlarmCapabilityGap.NOTIFICATIONS),
                    AlarmCapability.gapsFrom(sdk, true, true, true, ringChannelImportance = importance),
                )
            }
            for (importance in listOf(4, 5)) {
                assertEquals(
                    "API $sdk, importance $importance",
                    emptyList<AlarmCapabilityGap>(),
                    AlarmCapability.gapsFrom(sdk, true, true, true, ringChannelImportance = importance),
                )
            }
            // Not created yet: it is then created at High, so nothing is wrong.
            assertTrue(AlarmCapability.gapsFrom(sdk, true, true, true, ringChannelImportance = null).isEmpty())
        }
    }

    /** API 24 and 25 have no channels: nothing to read, nothing reported, whatever is passed. */
    @Test
    fun noChannelGapBeforeAndroid8() {
        for (sdk in listOf(NOUGAT, 25)) {
            assertTrue(AlarmCapability.gapsFrom(sdk, true, true, true, ringChannelImportance = null).isEmpty())
            assertTrue(AlarmCapability.gapsFrom(sdk, true, true, true, ringChannelImportance = 2).isEmpty())
            assertFalse(AlarmCapability.ringChannelLowered(sdk, 0))
        }
    }

    /** Notifications off and the channel lowered is still one gap, not two. */
    @Test
    fun notificationsOffAndAChannelLoweredIsOneGap() {
        assertEquals(
            listOf(AlarmCapabilityGap.NOTIFICATIONS, AlarmCapabilityGap.EXACT_ALARMS),
            AlarmCapability.gapsFrom(S, false, false, true, ringChannelImportance = 2),
        )
    }

    /**
     * Fix round 3 (code review C-1): the "Ringing alarms, backup" channel rings the alarm when the
     * service cannot (A-2, A-4), so turned down it hides that ring too, and the banner says so.
     * High (4) and above, or a channel not created yet (null), is no gap; below High always is.
     */
    @Test
    fun aLoweredBackupChannelCountsAsNotificationsRefused() {
        for (sdk in listOf(26, S, 32, TIRAMISU, UPSIDE_DOWN_CAKE, 36)) {
            for (importance in 0..3) {
                assertEquals(
                    "API $sdk, backup importance $importance",
                    listOf(AlarmCapabilityGap.NOTIFICATIONS),
                    AlarmCapability.gapsFrom(sdk, true, true, true, ringChannelImportance = 4, backupChannelImportance = importance),
                )
            }
            for (importance in listOf(4, 5, null)) {
                assertTrue(
                    "API $sdk, backup importance $importance",
                    AlarmCapability.gapsFrom(sdk, true, true, true, ringChannelImportance = 4, backupChannelImportance = importance).isEmpty(),
                )
            }
        }
        // Before Android 8 there are no channels to turn down.
        assertTrue(AlarmCapability.gapsFrom(NOUGAT, true, true, true, backupChannelImportance = 0).isEmpty())
        // Both channels lowered is still one gap.
        assertEquals(
            listOf(AlarmCapabilityGap.NOTIFICATIONS),
            AlarmCapability.gapsFrom(S, true, true, true, ringChannelImportance = 2, backupChannelImportance = 2),
        )
    }

    /**
     * The fix button opens the page of the one ringing channel that is turned down, and the app's
     * notification page (which lists both) when both are, when neither is, or when notifications
     * are off altogether (fix round 3, code review C-1).
     */
    @Test
    fun theFixOpensThePageOfTheLoweredChannel() {
        assertEquals(AlarmService.CHANNEL_RING, AlarmCapability.channelToFix(S, true, 2, 4))
        assertEquals(AlarmService.CHANNEL_RING_BACKUP, AlarmCapability.channelToFix(S, true, 4, 2))
        assertEquals(AlarmService.CHANNEL_RING_BACKUP, AlarmCapability.channelToFix(UPSIDE_DOWN_CAKE, true, null, 0))
        assertNull(AlarmCapability.channelToFix(S, true, 2, 2))
        assertNull(AlarmCapability.channelToFix(S, true, 4, 4))
        assertNull(AlarmCapability.channelToFix(S, true, null, null))
        assertNull(AlarmCapability.channelToFix(S, false, 2, 4))
        assertNull(AlarmCapability.channelToFix(NOUGAT, true, 2, 2))
    }

    /**
     * 1.1.4: the texts no longer make the two false claims of 1.1.3 ("it cannot make a sound";
     * "several minutes late"). The wording (T-1, T-2, T-4) was approved by Marcos on 22 October 2026.
     */
    @Test
    fun theGapTextsMakeNoFalseClaim() {
        assertFalse(AlarmCapabilityGap.NOTIFICATIONS.explanation.contains("cannot make a sound"))
        assertFalse(AlarmCapabilityGap.EXACT_ALARMS.explanation.contains("several minutes"))
        assertTrue(AlarmCapabilityGap.NOTIFICATIONS.explanation.contains("Open SunApp to stop it"))
        assertTrue(AlarmCapabilityGap.EXACT_ALARMS.explanation.contains("late or not at all"))
        for (gap in AlarmCapabilityGap.entries) {
            for (text in listOf(gap.title, gap.explanation, gap.fixLabel)) {
                assertFalse("${gap.name}: no em or en dash", text.contains('\u2014') || text.contains('\u2013'))
                assertFalse("${gap.name}: no double hyphen", text.contains("--"))
            }
        }
    }

    /**
     * Fix round 3 (Android check C-1): a Settings screen opened from SunApp runs in a task of its
     * own. On top of SunApp's task it was the first thing SunApp showed when opened to stop a
     * ringing alarm, one Back press away from the ring screen. Read as text, because an Intent
     * cannot be built on the plain JVM (the framework classes are stubs).
     */
    @Test
    fun settingsScreensOpenInATaskOfTheirOwn() {
        val code = source("alarm/AlarmCapability.kt")
        assertTrue(
            "settingsIntent adds FLAG_ACTIVITY_NEW_TASK to every screen",
            Regex("""fun settingsIntent\(context: Context, gap: AlarmCapabilityGap\): Intent =\s*settingsIntentFor\(context, gap\)\.addFlags\(Intent\.FLAG_ACTIVITY_NEW_TASK\)""")
                .containsMatchIn(code),
        )
        assertTrue(
            "the battery list opens in its own task too",
            Regex("""Intent\(Settings\.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS\)\.addFlags\(Intent\.FLAG_ACTIVITY_NEW_TASK\)""")
                .containsMatchIn(code),
        )
        // The permission sheet opens no Settings screen but these.
        val sheet = source("ui/PermissionSheet.kt")
        val opened = Regex("""activity\.open\(([^)]*\))""").findAll(sheet).map { it.groupValues[1] }.toList()
        assertTrue("the sheet opens Settings screens", opened.isNotEmpty())
        for (arg in opened) assertTrue(arg, arg.startsWith("AlarmCapability.settingsIntent(activity, gap"))
    }

    /** Every gap has to be able to explain itself, or the sheet shows an empty row. */
    @Test
    fun everyGapCarriesUserFacingText() {
        for (gap in AlarmCapabilityGap.entries) {
            assertTrue(gap.name, gap.title.isNotBlank())
            assertTrue(gap.name, gap.explanation.isNotBlank())
            assertTrue(gap.name, gap.fixLabel.isNotBlank())
        }
    }

    /** The rationale is the promise made in the owner's confirmed decision 4. */
    @Test
    fun rationaleSaysAlarmsOnlyAndNoLocation() {
        assertTrue(AlarmCapability.RATIONALE.contains("only for alarms"))
        assertTrue(AlarmCapability.RATIONALE.contains("never asks for your location"))
    }

    @Test
    fun recognisesSubBrandsAndIsCaseInsensitive() {
        assertEquals(
            AlarmCapability.oemGuidance("Xiaomi"),
            AlarmCapability.oemGuidance("Redmi"),
        )
        assertEquals(
            AlarmCapability.oemGuidance("OnePlus"),
            AlarmCapability.oemGuidance("realme"),
        )
        assertNotNull(AlarmCapability.oemGuidance("samsung"))
        assertNotNull(AlarmCapability.oemGuidance("HUAWEI"))
    }

    /**
     * A device with no vendor power manager gets no advice at all. Inventing steps for a
     * Pixel would send the user hunting through Settings for a screen that does not exist.
     */
    /** A main source file, by its path below the app's package, read as text. */
    private fun source(relative: String): String {
        val path = "src/main/kotlin/no/stormberry/sunapp/$relative"
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(java.io.File(dir, path), java.io.File(dir, "app/$path"), java.io.File(dir, "android/app/$path"))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        error("$relative not found above ${System.getProperty("user.dir")}")
    }

    @Test
    fun saysNothingAboutDevicesWithNoVendorPowerManager() {
        assertNull(AlarmCapability.oemGuidance("Google"))
        assertNull(AlarmCapability.oemGuidance("Fairphone"))
        assertNull(AlarmCapability.oemGuidance(""))
    }
}
