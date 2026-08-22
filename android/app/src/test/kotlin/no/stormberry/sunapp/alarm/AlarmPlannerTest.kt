package no.stormberry.sunapp.alarm

import no.stormberry.sunapp.alarm.model.Direction
import no.stormberry.sunapp.solar.SolarEvent
import org.junit.Assert.assertEquals
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
            }
        }
        assertEquals(listOf("cancel:$stale", "arm:${AlarmPlanner.requestCodeFor("kept")}"), shape)
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
}
