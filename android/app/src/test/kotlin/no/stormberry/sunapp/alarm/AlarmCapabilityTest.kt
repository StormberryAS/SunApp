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
    @Test
    fun saysNothingAboutDevicesWithNoVendorPowerManager() {
        assertNull(AlarmCapability.oemGuidance("Google"))
        assertNull(AlarmCapability.oemGuidance("Fairphone"))
        assertNull(AlarmCapability.oemGuidance(""))
    }
}
