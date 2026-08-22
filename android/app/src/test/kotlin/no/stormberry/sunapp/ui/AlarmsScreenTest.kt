package no.stormberry.sunapp.ui

import no.stormberry.sunapp.alarm.AlarmCapabilityGap
import no.stormberry.sunapp.alarm.model.Occurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The pure half of the alarms list: the clock, the day words, the countdown and the choice of
 * which permission gap earns a banner.
 *
 * Every one of these is a function of its arguments. `Instant.now()` appears nowhere, which is
 * what lets "tomorrow" and "in 7 h 12 min" be asserted rather than described.
 */
class AlarmsScreenTest {

    private val oslo: ZoneId = ZoneId.of("Europe/Oslo")
    private val tokyo: ZoneId = ZoneId.of("Asia/Tokyo")

    /** 2026-08-21 06:06 Europe/Oslo, which is 04:06 UTC. */
    private val bergenSunrise: Instant = Instant.parse("2026-08-21T04:06:00Z")

    @Test
    fun `the clock is read in whichever zone is asked for`() {
        assertEquals("06:06", formatFireClock(bergenSunrise, oslo))
        // The same instant, on a phone that has travelled. This is why the list prints the
        // device's zone: 06:06 would be a lie about when the phone makes a noise.
        assertEquals("13:06", formatFireClock(bergenSunrise, tokyo))
    }

    @Test
    fun `the day is named relative to the reader, not spelled out`() {
        assertEquals("today", formatFireDay(bergenSunrise, oslo, LocalDate.of(2026, 8, 21)))
        assertEquals("tomorrow", formatFireDay(bergenSunrise, oslo, LocalDate.of(2026, 8, 20)))
        assertEquals("Fri 21 Aug", formatFireDay(bergenSunrise, oslo, LocalDate.of(2026, 8, 17)))
    }

    @Test
    fun `the same instant can be tomorrow in one zone and today in another`() {
        // 22:30 in Oslo on 21 August is 05:30 on 22 August in Tokyo. A row that showed one
        // date for both would be wrong for one of the two readers.
        val lateEvening = Instant.parse("2026-08-21T20:30:00Z")
        assertEquals("today", formatFireDay(lateEvening, oslo, LocalDate.of(2026, 8, 21)))
        assertEquals("tomorrow", formatFireDay(lateEvening, tokyo, LocalDate.of(2026, 8, 21)))
    }

    @Test
    fun `the polar warning names the day it is about`() {
        val today = LocalDate.of(2026, 12, 21)
        assertEquals("today", describeFallbackDay(today, today))
        assertEquals("tomorrow", describeFallbackDay(LocalDate.of(2026, 12, 22), today))
        assertEquals("on Fri 25 Dec", describeFallbackDay(LocalDate.of(2026, 12, 25), today))
    }

    @Test
    fun `countdowns truncate downwards and never read as zero`() {
        val now = Instant.parse("2026-08-20T20:54:00Z")
        assertEquals("in 7 h 12 min", formatCountdown(now, bergenSunrise))
        assertEquals("in 40 min", formatCountdown(now, now.plusSeconds(40 * 60)))
        assertEquals("in 1 h", formatCountdown(now, now.plusSeconds(60 * 60)))
        // Truncating, not rounding: ninety seconds is "in 1 min", never "in 2 min", because
        // rounding up invites somebody to look away for longer than they have.
        assertEquals("in 1 min", formatCountdown(now, now.plusSeconds(90)))
        // Under a minute is named rather than shown as "in 0 min", which reads as broken.
        assertEquals("in under a minute", formatCountdown(now, now.plusSeconds(30)))
        assertEquals("any moment now", formatCountdown(now, now.minusSeconds(30)))
    }

    /* -------------------------------------------------------------- *
     * Which gap earns the banner
     * -------------------------------------------------------------- */

    @Test
    fun `no gaps means no banner`() {
        assertNull(alarmBanner(emptyList()))
    }

    @Test
    fun `a blocking gap outranks a degrading one`() {
        val gaps = listOf(AlarmCapabilityGap.NOTIFICATIONS, AlarmCapabilityGap.EXACT_ALARMS)
        assertEquals(AlarmCapabilityGap.NOTIFICATIONS, alarmBanner(gaps))
        // Order in the list must not decide it: severity does.
        assertEquals(AlarmCapabilityGap.NOTIFICATIONS, alarmBanner(gaps.reversed()))
    }

    @Test
    fun `a degradation still gets said out loud`() {
        // "Your alarm may be several minutes late" is worth a banner even though the alarm
        // does still ring, which is why this is not filtered down to blocking gaps only.
        assertEquals(
            AlarmCapabilityGap.EXACT_ALARMS,
            alarmBanner(listOf(AlarmCapabilityGap.EXACT_ALARMS, AlarmCapabilityGap.FULL_SCREEN_INTENT)),
        )
    }

    /** A row of the list needs no more than this. */
    private fun occurrence(fireAt: Instant) = Occurrence(
        ruleId = "r",
        anchorDate = fireAt.atZone(oslo).toLocalDate(),
        anchorAt = fireAt,
        fireAt = fireAt,
        usedFallback = false,
        clamped = false,
    )

    @Test
    fun `the soonest alarm is the one the header counts down to`() {
        val soon = occurrence(Instant.parse("2026-08-21T04:06:00Z"))
        val later = occurrence(Instant.parse("2026-08-21T19:20:00Z"))
        val next = listOf(later, soon).minByOrNull { it.fireAt }
        assertEquals(soon, next)
    }
}
