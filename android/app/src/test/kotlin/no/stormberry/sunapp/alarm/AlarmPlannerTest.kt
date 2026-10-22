package no.stormberry.sunapp.alarm

import no.stormberry.sunapp.alarm.model.Direction
import no.stormberry.sunapp.solar.SolarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The scheduler, driven by a fixed [Clock] and a recording sink, so that a reboot, a firing
 * and a backwards clock jump are three lines of test each instead of a device and a night.
 */
class AlarmPlannerTest {

    private fun clockAt(iso: String): Clock = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)

    /** 22:00 UTC on 20 June, which is midnight local on the 21st in Bergen. */
    private val midsummerEve = clockAt("2026-06-20T22:00:00Z")

    @Test
    fun plansOneAlarmPerEnabledRule() {
        val sunrise = testRule(id = "sunrise", anchor = SolarEvent.SUNRISE)
        val sunset = testRule(id = "sunset", anchor = SolarEvent.SUNSET)

        val plan = AlarmPlanner.plan(listOf(sunrise, sunset), midsummerEve)

        assertEquals(2, plan.size)
        assertEquals(Instant.parse("2026-06-21T02:11:05.149Z"), plan[0].occurrence.fireAt)
        assertEquals(Instant.parse("2026-06-21T21:12:19.394Z"), plan[1].occurrence.fireAt)
    }

    /** Sorted by fire time, so the caller reading `plan.first()` gets the alarm that is
     *  actually next rather than whichever rule happened to be first in the list. */
    @Test
    fun planIsSortedByFireTime() {
        val late = testRule(id = "late", anchor = SolarEvent.SUNSET)
        val early = testRule(id = "early", anchor = SolarEvent.SUNRISE)

        val plan = AlarmPlanner.plan(listOf(late, early), midsummerEve)

        assertEquals(listOf("early", "late"), plan.map { it.occurrence.ruleId })
    }

    @Test
    fun disabledRulesAreNotPlanned() {
        val on = testRule(id = "on")
        val off = testRule(id = "off", enabled = false)

        val plan = AlarmPlanner.plan(listOf(on, off), midsummerEve)

        assertEquals(listOf("on"), plan.map { it.occurrence.ruleId })
    }

    /**
     * Switching a rule off cancels its pending alarm rather than leaving it to ring.
     *
     * The previously-armed set is what makes this possible without the planner keeping state:
     * the caller hands back what it armed last time, and anything not in the new plan is
     * cancelled.
     */
    @Test
    fun applyCancelsTheAlarmForARuleThatWasSwitchedOff() {
        val sunrise = testRule(id = "sunrise", anchor = SolarEvent.SUNRISE)
        val sunset = testRule(id = "sunset", anchor = SolarEvent.SUNSET)
        val sink = FakeAlarmSink()

        val armed = AlarmPlanner.sync(listOf(sunrise, sunset), midsummerEve, sink)
        assertEquals(2, sink.armed.size)
        assertTrue(sink.cancelled.isEmpty())

        sink.reset()
        val stillArmed = AlarmPlanner.sync(
            listOf(sunrise, sunset.copy(enabled = false)),
            midsummerEve,
            sink,
            previouslyArmed = armed,
        )

        assertEquals(listOf(AlarmPlanner.requestCodeFor("sunset")), sink.cancelled)
        assertEquals(setOf(AlarmPlanner.requestCodeFor("sunrise")), stillArmed)
    }

    /**
     * A rule that survives a re-plan is never momentarily unarmed. Cancelling everything and
     * re-arming would leave a window, short but real, in which a reboot or a crash loses the
     * alarm entirely.
     */
    @Test
    fun applyCancelsBeforeItArms() {
        val kept = testRule(id = "kept")
        val sink = FakeAlarmSink()
        val stale = 12345678 and 0x7FFFFFFE

        AlarmPlanner.sync(listOf(kept), midsummerEve, sink, previouslyArmed = setOf(stale))

        val shape = sink.calls.map {
            when (it) {
                is FakeAlarmSink.Call.Arm -> "arm:${it.planned.requestCode}"
                is FakeAlarmSink.Call.Cancel -> "cancel:${it.requestCode}"
                // 1.1.4, A-2: apply ends with the backstop request, here "none" (no backstop asked).
                is FakeAlarmSink.Call.Guard -> "guard:${it.next?.requestCode}"
            }
        }
        assertEquals(
            listOf("cancel:$stale", "arm:${AlarmPlanner.requestCodeFor("kept")}", "guard:null"),
            shape,
        )
    }

    /** A rule still in the plan is not cancelled on the way past, or the replacement would be
     *  a fresh registration rather than an in-place update. */
    @Test
    fun applyDoesNotCancelACodeItIsAboutToRearm() {
        val kept = testRule(id = "kept")
        val code = AlarmPlanner.requestCodeFor("kept")
        val sink = FakeAlarmSink()

        AlarmPlanner.sync(listOf(kept), midsummerEve, sink, previouslyArmed = setOf(code))

        assertTrue(sink.cancelled.isEmpty())
        assertEquals(setOf(code), sink.armedCodes)
    }

    /**
     * Re-planning from a stored snapshot after a reboot arms exactly the same set. This is
     * the property that makes the boot receiver safe to run blind: it does not need to know
     * what was armed before the device went down, because a full recompute reproduces it.
     */
    @Test
    fun replanningAfterARebootArmsTheSameAlarms() {
        val rules = listOf(
            testRule(id = "sunrise", anchor = SolarEvent.SUNRISE),
            testRule(id = "noon", anchor = SolarEvent.SOLAR_NOON, direction = Direction.BEFORE, offsetMinutes = 360),
        )

        val before = AlarmPlanner.plan(rules, midsummerEve)
        val afterReboot = AlarmPlanner.plan(rules, midsummerEve)

        assertEquals(before, afterReboot)
    }

    /**
     * Once an alarm has fired, planning from that instant produces the following day.
     *
     * This is the re-arm the fire receiver performs before it does anything that can throw,
     * and it is the whole recurrence mechanism: there is no repeating alarm, only a chain of
     * one-shots each computed from the sun on the day it is armed.
     */
    @Test
    fun planningFromTheMomentAnAlarmFiredMovesToTheNextDay() {
        val rule = testRule(id = "sunrise", anchor = SolarEvent.SUNRISE)
        val fired = clockAt("2026-06-21T02:11:05.149Z")

        val plan = AlarmPlanner.plan(listOf(rule), fired)

        assertEquals(1, plan.size)
        assertEquals(LocalDate.of(2026, 6, 22), plan.single().occurrence.anchorDate)
        assertEquals(Instant.parse("2026-06-22T02:11:18.282Z"), plan.single().occurrence.fireAt)
    }

    /**
     * A clock that jumped backwards re-arms the alarm it had already passed, rather than
     * skipping a day or failing.
     *
     * The planner cannot tell a jumped clock from a correct one, and it should not try. Its
     * job is to answer "given that it is now T, what should be pending", which for a T before
     * this morning's alarm is this morning's alarm. Deciding not to actually ring on a wake-up
     * that arrives suspiciously early belongs in the receiver, which can compare the wake-up
     * against the instant it armed.
     */
    @Test
    fun aBackwardsClockJumpRearmsTheAlarmItHadPassed() {
        val rule = testRule(id = "sunrise", anchor = SolarEvent.SUNRISE)
        val jumpedBack = clockAt("2026-06-21T01:00:00Z")

        val plan = AlarmPlanner.plan(listOf(rule), jumpedBack)

        assertEquals(Instant.parse("2026-06-21T02:11:05.149Z"), plan.single().occurrence.fireAt)
    }

    /**
     * The rule carries its own zone, so the device's zone is irrelevant to an unclamped fire
     * time. Two rules over the same coordinates with different stored zones fire at the same
     * instant; only the local rendering differs.
     */
    @Test
    fun theStoredZoneDoesNotMoveAnUnclampedFireTime() {
        val oslo = testRule(id = "oslo", zoneId = "Europe/Oslo")
        val utc = testRule(id = "utc", zoneId = "UTC")

        val plan = AlarmPlanner.plan(listOf(oslo, utc), midsummerEve)

        assertEquals(2, plan.size)
        assertEquals(plan[0].occurrence.fireAt, plan[1].occurrence.fireAt)
    }

    /**
     * A rule whose stored zone this tzdb no longer knows is skipped, and the other alarms are
     * armed anyway.
     *
     * This runs inside a boot receiver. One unusable row must never be able to stop every
     * other alarm on the device from being re-armed, which is what an exception escaping here
     * would do. `AlarmStore` already disables such a row on the way in, so this is the second
     * line of defence rather than the first.
     */
    @Test
    fun aRuleWithAnUnknownZoneIsSkippedRatherThanThrowing() {
        val broken = testRule(id = "broken", zoneId = "Arctic/NoSuchPlace")
        val good = testRule(id = "good")

        val plan = AlarmPlanner.plan(listOf(broken, good), midsummerEve)

        assertEquals(listOf("good"), plan.map { it.occurrence.ruleId })
    }

    /** A rule with no next occurrence at all yields no plan entry rather than a null one. */
    @Test
    fun nextOccurrenceIsNullOnlyWhenThereIsGenuinelyNothing() {
        // Solar noon exists everywhere, every day, so there is no honest input that produces
        // null. Asserting the shape of the contract is still worth a line: if this ever
        // starts returning null for an ordinary rule, something has broken badly.
        val rule = testRule(anchor = SolarEvent.SOLAR_NOON)

        assertTrue(OccurrenceEngine.nextOccurrence(rule, Instant.parse("2026-06-21T00:00:00Z")) != null)
        assertNull(AlarmPlanner.plan(emptyList(), midsummerEve).firstOrNull())
    }

    // -------------------------------------------------------------------------------------
    // Request codes
    // -------------------------------------------------------------------------------------

    /** The same rule id always produces the same code, across processes and app versions.
     *  Anything else strands a pending alarm that nothing can cancel. */
    @Test
    fun requestCodesAreStableForAnId() {
        assertEquals(AlarmPlanner.requestCodeFor("rule-1"), AlarmPlanner.requestCodeFor("rule-1"))
        assertNotEquals(AlarmPlanner.requestCodeFor("rule-1"), AlarmPlanner.requestCodeFor("rule-2"))
    }

    /** Renaming a rule must not change its code, or the edit would add a second alarm beside
     *  the first instead of replacing it. */
    @Test
    fun requestCodeDependsOnTheIdAndNothingElse() {
        val original = testRule(id = "abc", label = "Morning")
        val renamed = original.copy(label = "Early start", offsetMinutes = 45, direction = Direction.AFTER)

        assertEquals(
            AlarmPlanner.requestCodeFor(original.id),
            AlarmPlanner.requestCodeFor(renamed.id),
        )
    }

    /**
     * Base codes are even and snooze codes are the odd sibling, which makes a collision
     * between a rule's own alarm and its snooze arithmetically impossible rather than merely
     * improbable. Snoozing must not cancel tomorrow's alarm.
     */
    @Test
    fun snoozeCodesCanNeverCollideWithBaseCodes() {
        val ids = listOf("a", "rule-1", "8f2c", "", "a much longer identifier than usual")

        val bases = ids.map { AlarmPlanner.requestCodeFor(it) }
        val snoozes = ids.map { AlarmPlanner.snoozeRequestCodeFor(it) }

        assertTrue(bases.all { it % 2 == 0 })
        assertTrue(bases.all { it >= 0 })
        assertTrue(snoozes.all { it % 2 == 1 })
        assertTrue((bases.toSet() intersect snoozes.toSet()).isEmpty())
    }

    // ------------------------------------------------------------------- 1.1.4 additions

    /**
     * A-2: the backstop follows the nearest alarm only, and only when asked for, which
     * AlarmScheduling does on Android 12 and 12L alone (FireRules.backstopWanted).
     */
    @Test
    fun theBackstopIsRequestedForTheNearestAlarmOnlyOnAndroid12() {
        val sunrise = testRule(id = "sunrise", anchor = SolarEvent.SUNRISE)
        val sunset = testRule(id = "sunset", anchor = SolarEvent.SUNSET)
        val plan = AlarmPlanner.plan(listOf(sunset, sunrise), midsummerEve)

        for (sdk in listOf(24, 30, 31, 32, 33, 34, 36)) {
            val sink = FakeAlarmSink()
            AlarmPlanner.apply(plan, sink, backstop = FireRules.backstopWanted(sdk))
            assertEquals("API $sdk asks exactly once", 1, sink.guards.size)
            val guarded = sink.guards.single()
            if (sdk == 31 || sdk == 32) {
                assertEquals("API $sdk guards the nearest", plan.first(), guarded)
                assertEquals("sunrise", guarded?.occurrence?.ruleId)
            } else {
                assertNull("API $sdk cancels any backstop", guarded)
            }
            // The backstop comes after every arm, so it never stands in for an unarmed alarm.
            assertTrue(sink.calls.last() is FakeAlarmSink.Call.Guard)
        }
    }

    @Test
    fun noRulesMeansTheBackstopIsCancelled() {
        val sink = FakeAlarmSink()
        AlarmPlanner.apply(emptyList(), sink, backstop = true)
        assertEquals(listOf<PlannedAlarm?>(null), sink.guards)
    }

    /**
     * The last-rung guard: an occurrence that has rung is never armed again, which is what
     * stops the backstop, a backwards clock correction, or an arrival under two seconds early
     * from ringing it twice (NOTES "A-8 related").
     */
    @Test
    fun anOccurrenceThatHasRungIsNotArmedAgain() {
        val sunrise = testRule(id = "sunrise", anchor = SolarEvent.SUNRISE)
        val first = AlarmPlanner.plan(listOf(sunrise), midsummerEve).single().occurrence.fireAt

        // The alarm arrived one second early, or the clock was set back by an hour after it rang.
        for (now in listOf(first.minusSeconds(1), first.minusSeconds(3600))) {
            val replanned = AlarmPlanner.plan(
                listOf(sunrise),
                now,
                mapOf("sunrise" to first.toEpochMilli()),
            ).single().occurrence.fireAt
            assertTrue("re-armed after $now: $replanned", replanned.isAfter(first))
            assertEquals(LocalDate.of(2026, 6, 22), replanned.atZone(OSLO).toLocalDate())
        }

        // Without the record the same re-plan arms the occurrence that rang: the 1.1.3 defect.
        val withoutRecord = AlarmPlanner.plan(listOf(sunrise), first.minusSeconds(1)).single()
        assertEquals(first, withoutRecord.occurrence.fireAt)
    }

    /** Another rule's last ring changes nothing for this one, and an older ring does not hold it back. */
    @Test
    fun theLastRungRecordIsPerRuleAndOnlyEverPushesForward() {
        val sunrise = testRule(id = "sunrise", anchor = SolarEvent.SUNRISE)
        val now = midsummerEve.instant()
        val expected = AlarmPlanner.plan(listOf(sunrise), now).single().occurrence.fireAt

        val otherRule = AlarmPlanner.plan(listOf(sunrise), now, mapOf("other" to expected.toEpochMilli())).single()
        val olderRing = AlarmPlanner.plan(listOf(sunrise), now, mapOf("sunrise" to now.minusSeconds(86_400).toEpochMilli())).single()

        assertEquals(expected, otherRule.occurrence.fireAt)
        assertEquals(expected, olderRing.occurrence.fireAt)
    }
    /**
     * Fix round 1 (verify 0, Android S-1, reproduced on the API 36 emulator): the clock jumps two
     * hours forward past an alarm, which rings at once and late; the clock is then set back. The
     * late ring is not recorded, so the re-plan arms the real occurrence again. In the build that
     * verify 0 checked it was recorded, the real occurrence was skipped and nothing rang.
     */
    @Test
    fun aRingAfterAForwardClockJumpDoesNotUseUpTheRealOccurrence() {
        val sunrise = testRule(id = "sunrise", anchor = SolarEvent.SUNRISE)
        val t1 = AlarmPlanner.plan(listOf(sunrise), midsummerEve).single().occurrence.fireAt

        // The clock jumps two hours forward; the alarm arrives two hours late by the clock.
        val arrival = t1.plusSeconds(2 * 3600)
        assertEquals(FireDecision.Ring, FireRules.decide(t1.toEpochMilli(), arrival.toEpochMilli(), null))
        for (inexact in listOf(false, true)) {
            assertFalse(FireRules.shouldRecordRing(t1.toEpochMilli(), arrival.toEpochMilli(), inexact))
        }

        // Not recorded, so after the clock is set back the real occurrence is armed again.
        val afterSetBack = AlarmPlanner.plan(listOf(sunrise), t1.minusSeconds(29 * 60), emptyMap()).single()
        assertEquals(t1, afterSetBack.occurrence.fireAt)
    }

    /** An on-time ring and then a small backwards correction (3 minutes): the occurrence is not armed twice. */
    @Test
    fun anOnTimeRingAndASmallSetBackDoNotRingTwice() {
        val sunrise = testRule(id = "sunrise", anchor = SolarEvent.SUNRISE)
        val t1 = AlarmPlanner.plan(listOf(sunrise), midsummerEve).single().occurrence.fireAt
        assertTrue(FireRules.shouldRecordRing(t1.toEpochMilli(), t1.toEpochMilli() + 500L, inexact = false))
        val record = FireRules.recordRung(emptyMap(), "sunrise", t1.toEpochMilli())

        val now = t1.minusSeconds(3 * 60)
        val pruned = FireRules.pruneRung(record, now.toEpochMilli())
        assertEquals(record, pruned)
        val replanned = AlarmPlanner.plan(listOf(sunrise), now, pruned).single().occurrence.fireAt
        assertTrue(replanned.isAfter(t1))
    }

    /**
     * An on-time ring and then a large backwards set (two hours, code review C-1): nobody can tell
     * which was wrong, so the record is pruned and the occurrence rings again, as in 1.1.3, and
     * when it arrives it is not taken for one that has rung.
     */
    @Test
    fun anOnTimeRingAndALargeSetBackRingAgain() {
        val sunrise = testRule(id = "sunrise", anchor = SolarEvent.SUNRISE)
        val t1 = AlarmPlanner.plan(listOf(sunrise), midsummerEve).single().occurrence.fireAt
        val record = FireRules.recordRung(emptyMap(), "sunrise", t1.toEpochMilli())

        val now = t1.minusSeconds(2 * 3600)
        val pruned = FireRules.pruneRung(record, now.toEpochMilli())
        assertTrue(pruned.isEmpty())
        assertEquals(t1, AlarmPlanner.plan(listOf(sunrise), now, pruned).single().occurrence.fireAt)
        assertEquals(FireDecision.Ring, FireRules.decide(t1.toEpochMilli(), t1.toEpochMilli(), pruned["sunrise"]))
    }

    /**
     * Fix round 1 (verify 0, Android C-1): on Android 12 and 12L the backstop stands in for a
     * waiting snooze when it comes before the nearest alarm, and for the alarm otherwise.
     */
    @Test
    fun theBackstopStandsInForASnoozeThatComesFirst() {
        val sunrise = testRule(id = "sunrise", anchor = SolarEvent.SUNRISE)
        val plan = AlarmPlanner.plan(listOf(sunrise), midsummerEve)
        val alarmAt = plan.single().occurrence.fireAt
        val sooner = snooze("sunset", alarmAt.minusSeconds(60))
        val later = snooze("sunset", alarmAt.plusSeconds(60))

        val sink = FakeAlarmSink()
        AlarmPlanner.apply(plan, sink, backstop = true, snoozes = listOf(later, sooner))
        assertEquals(listOf<PlannedAlarm?>(sooner), sink.guards)

        val sink2 = FakeAlarmSink()
        AlarmPlanner.apply(plan, sink2, backstop = true, snoozes = listOf(later))
        assertEquals(listOf<PlannedAlarm?>(plan.single()), sink2.guards)

        val sink3 = FakeAlarmSink()
        AlarmPlanner.apply(emptyList(), sink3, backstop = true, snoozes = listOf(later))
        assertEquals(listOf<PlannedAlarm?>(later), sink3.guards)

        // Elsewhere the backstop is cancelled, snoozes or not.
        val sink4 = FakeAlarmSink()
        AlarmPlanner.apply(plan, sink4, backstop = false, snoozes = listOf(sooner))
        assertEquals(listOf<PlannedAlarm?>(null), sink4.guards)
        // A snooze is armed by the caller, never by apply.
        assertEquals(plan, sink.armed)
    }

    /**
     * Fix round 2 (verify 1, code review S-1), at the level of what reaches AlarmManager: a
     * re-plan that is not after a restart keeps a stored snooze eleven minutes overdue and arms
     * it again, for its own past instant, on the rule's snooze code (so it rings at once). The
     * re-plan after a restart cancels it instead, and a deleted rule's snooze is cancelled by both.
     */
    @Test
    fun aReplanNotAfterARestartReArmsAnOverdueSnooze() {
        val now = midsummerEve.instant().toEpochMilli()
        val overdue = PendingSnooze("sunrise", now - 11 * 60_000L, "Sunrise")
        val deleted = PendingSnooze("deleted", now + 60_000L, "Deleted")
        val live = setOf("sunrise")

        val sink = FakeAlarmSink()
        val waiting = AlarmPlanner.applySnoozes(FireRules.sortSnoozes(listOf(overdue, deleted), live, now, dropLate = false), sink, OSLO)
        assertEquals(listOf(overdue), waiting)
        assertEquals(listOf(AlarmPlanner.snoozePlanned(overdue, OSLO)), sink.armed)
        assertEquals(Instant.ofEpochMilli(overdue.at), sink.armed.single().occurrence.fireAt)
        assertEquals(AlarmPlanner.snoozeRequestCodeFor("sunrise"), sink.armed.single().requestCode)
        assertEquals(listOf(AlarmPlanner.snoozeRequestCodeFor("deleted")), sink.cancelled)
        // Cancels come before arms, as in apply.
        assertTrue(sink.calls.first() is FakeAlarmSink.Call.Cancel)

        val afterRestart = FakeAlarmSink()
        val left = AlarmPlanner.applySnoozes(FireRules.sortSnoozes(listOf(overdue, deleted), live, now, dropLate = true), afterRestart, OSLO)
        assertEquals(emptyList<PendingSnooze>(), left)
        assertEquals(emptyList<PlannedAlarm>(), afterRestart.armed)
        assertEquals(
            setOf(AlarmPlanner.snoozeRequestCodeFor("sunrise"), AlarmPlanner.snoozeRequestCodeFor("deleted")),
            afterRestart.cancelled.toSet(),
        )
    }

    private fun snooze(ruleId: String, at: Instant) = PlannedAlarm(
        no.stormberry.sunapp.alarm.model.Occurrence(
            ruleId = ruleId,
            anchorDate = at.atZone(OSLO).toLocalDate(),
            anchorAt = at,
            fireAt = at,
            usedFallback = false,
            clamped = false,
        ),
        AlarmPlanner.snoozeRequestCodeFor(ruleId),
    )
}
