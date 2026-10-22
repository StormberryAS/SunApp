package no.stormberry.sunapp.alarm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The decisions of 1.1.4 (ported from EclipseTracker's fix B, with fix rounds C to E), each
 * replayed on the JVM against a fixed clock. The receiver, the scheduler and the service only
 * carry out what is decided here.
 */
class FireRulesTest {

    private val t = 1_782_000_000_000L
    private val minute = 60_000L

    // ------------------------------------------------------------------- an alarm arriving

    @Test
    fun anAlarmOnTimeRings() {
        assertSame(FireDecision.Ring, FireRules.decide(t, t, lastRung = null))
        assertSame(FireDecision.Ring, FireRules.decide(t, t + 500, lastRung = t - 86_400_000L))
    }

    /** Up to two seconds early is scheduling slop, as in 1.1.3; more is a clock jump. */
    @Test
    fun anAlarmMoreThanTwoSecondsEarlyIsAClockJump() {
        assertSame(FireDecision.Ring, FireRules.decide(t, t - 2_000L, null))
        assertSame(FireDecision.Early, FireRules.decide(t, t - 2_001L, null))
        assertSame(FireDecision.Early, FireRules.decide(t, t - 3_600_000L, null))
    }

    /**
     * The Android 12 backstop and the exact alarm both arrive for one occurrence, or the clock is
     * set back after a ring and the occurrence is delivered again: the second never rings.
     */
    @Test
    fun anOccurrenceThatHasRungDoesNotRingAgain() {
        assertSame(FireDecision.AlreadyRung, FireRules.decide(t, t + 1_000L, lastRung = t))
        assertSame(FireDecision.AlreadyRung, FireRules.decide(t, t + 9 * minute, lastRung = t + minute))
        // The next occurrence, a day later, rings.
        assertSame(FireDecision.Ring, FireRules.decide(t + 86_400_000L, t + 86_400_000L, lastRung = t))
    }

    /** An alarm's own occurrence rings late, as in 1.1.3: its inexact fallback can legitimately be late. */
    @Test
    fun anAlarmArrivingLateStillRings() {
        assertSame(FireDecision.Ring, FireRules.decide(t, t + 55 * minute, null))
    }

    /**
     * Fix round 1 (verify 0): a record far ahead of the clock means the clock was set back a long
     * way after the ring; it no longer counts, and the occurrence rings again rather than being
     * skipped. Up to an hour ahead it still counts.
     */
    @Test
    fun aRecordFarAheadOfTheClockNoLongerCounts() {
        val hour = 60 * minute
        assertSame(FireDecision.AlreadyRung, FireRules.decide(t, t - 2_000L, lastRung = t + hour - 2_000L))
        assertSame(FireDecision.Ring, FireRules.decide(t, t - 2_000L, lastRung = t + hour))
        assertTrue(FireRules.rungCounts(t + hour, t))
        assertFalse(FireRules.rungCounts(t + hour + 1, t))
        assertTrue(FireRules.rungCounts(t - 86_400_000L, t))
    }

    /**
     * Fix round 1 (verify 0, Android S-1): only an on-time ring is recorded. An exact alarm more
     * than two minutes late arrived because the clock jumped forward past it; an inexact alarm or
     * a backstop may be held back up to an hour, so it has 65 minutes.
     */
    @Test
    fun onlyAnOnTimeRingIsRecorded() {
        for (inexact in listOf(false, true)) {
            assertTrue("on time, inexact=$inexact", FireRules.shouldRecordRing(t, t, inexact))
            assertTrue("1.5 s early, inexact=$inexact", FireRules.shouldRecordRing(t, t - 1_500L, inexact))
            assertFalse("3 h late, inexact=$inexact", FireRules.shouldRecordRing(t, t + 3 * 60 * minute, inexact))
            assertFalse("no armed instant, inexact=$inexact", FireRules.shouldRecordRing(0L, t, inexact))
        }
        assertTrue(FireRules.shouldRecordRing(t, t + 90_000L, inexact = false))
        assertTrue(FireRules.shouldRecordRing(t, t + 2 * minute, inexact = false))
        assertFalse(FireRules.shouldRecordRing(t, t + 2 * minute + 1, inexact = false))
        assertTrue(FireRules.shouldRecordRing(t, t + 65 * minute, inexact = true))
        assertFalse(FireRules.shouldRecordRing(t, t + 65 * minute + 1, inexact = true))
    }

    /** When in doubt, ring: no armed instant or no readable record never silences an alarm. */
    @Test
    fun anUnknownInstantOrRecordRings() {
        assertSame(FireDecision.Ring, FireRules.decide(0L, t, lastRung = t + minute))
        assertSame(FireDecision.Ring, FireRules.decide(t, t, lastRung = null))
    }

    // ------------------------------------------------------------------ a snooze arriving

    @Test
    fun aStoredSnoozeOnTimeRings() {
        assertSame(FireDecision.Ring, FireRules.decideSnooze(t, t, stored = true))
        assertSame(FireDecision.Ring, FireRules.decideSnooze(t, t + 10 * minute, stored = true))
    }

    /**
     * Fix round 1 (verify 0, code review S-1): a stored snooze that AlarmManager delivers rings
     * however late it arrives, as an alarm's own occurrence does. On Android 12 with exact alarms
     * off every snooze is inexact and Doze can hold it back well past ten minutes; it used to be
     * dropped then with a Missed notice. Only a re-plan after a restart drops an overdue snooze
     * (decision D-5, aReplanSortsTheSnoozes).
     */
    @Test
    fun aStoredSnoozeRingsHoweverLateItArrives() {
        assertSame(FireDecision.Ring, FireRules.decideSnooze(t, t + 10 * minute + 1, stored = true))
        assertSame(FireDecision.Ring, FireRules.decideSnooze(t, t + 19 * minute, stored = true))
        assertSame(FireDecision.Ring, FireRules.decideSnooze(t, t + 3 * 60 * minute, stored = null))
        assertSame(FireDecision.Gone, FireRules.decideSnooze(t, t + 19 * minute, stored = false))
    }

    /** A-6: a cancelled snooze still delivered (deleted or switched-off rule, Dismiss) does not ring. */
    @Test
    fun aSnoozeNoLongerStoredIsGone() {
        assertSame(FireDecision.Gone, FireRules.decideSnooze(t, t, stored = false))
    }

    /** Unreadable storage, or a snooze armed before 1.1.4 stored any: it rings. */
    @Test
    fun aSnoozeWhoseStorageIsUnknownRings() {
        assertSame(FireDecision.Ring, FireRules.decideSnooze(t, t, stored = null))
        assertSame(FireDecision.Early, FireRules.decideSnooze(t, t - 5_000L, stored = null))
    }

    // ------------------------------------------------------------------------- the backstop

    @Test
    fun theBackstopIsWantedOnAndroid12And12LOnly() {
        for (sdk in 21..40) {
            assertEquals("API $sdk", sdk == 31 || sdk == 32, FireRules.backstopWanted(sdk))
        }
    }

    /**
     * Fix round 3 (code review C-2): only a reboot wipes AlarmManager. Android keeps an app's
     * alarms through an update, so the re-plan after MY_PACKAGE_REPLACED must not drop a snooze
     * that Android still holds, however overdue; nor after a clock, zone or grant change.
     */
    @Test
    fun onlyARebootWipesTheAlarms() {
        assertTrue(FireRules.wipesAlarms("android.intent.action.BOOT_COMPLETED"))
        assertTrue(FireRules.wipesAlarms("android.intent.action.LOCKED_BOOT_COMPLETED"))
        for (action in listOf(
            "android.intent.action.MY_PACKAGE_REPLACED",
            "android.intent.action.TIMEZONE_CHANGED",
            "android.intent.action.TIME_SET",
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            "android.intent.action.LOCALE_CHANGED",
            "",
            null,
        )) {
            assertFalse("$action", FireRules.wipesAlarms(action))
        }
        // What that means for a snooze held through an update: it is kept and armed again.
        val now = 1_800_000_000_000L
        val held = PendingSnooze("r1", now - 30 * 60_000L, "Up")
        val sort = FireRules.sortSnoozes(listOf(held), setOf("r1"), now, dropLate = FireRules.wipesAlarms("android.intent.action.MY_PACKAGE_REPLACED"))
        assertEquals(listOf(held), sort.keep)
        assertTrue(sort.late.isEmpty())
    }

    /**
     * Verify 3 (Android check): an app update keeps the alarms but removes the app's
     * notifications, so the snoozed notices come back after an update as after a reboot. A
     * clock, zone, grant or language change removes nothing, so nothing is posted again.
     */
    @Test
    fun aRebootOrAnUpdateClearsTheNotices() {
        for (action in listOf(
            "android.intent.action.BOOT_COMPLETED",
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            "android.intent.action.MY_PACKAGE_REPLACED",
        )) {
            assertTrue("$action", FireRules.clearsNotices(action))
        }
        for (action in listOf(
            "android.intent.action.TIMEZONE_CHANGED",
            "android.intent.action.TIME_SET",
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            "android.intent.action.LOCALE_CHANGED",
            "",
            null,
        )) {
            assertFalse("$action", FireRules.clearsNotices(action))
        }
    }

    /**
     * Fix round 3 (code review C-2, Android check C-2), read as text because a broadcast receiver
     * cannot run on the plain JVM: the system-event receiver asks [FireRules.wipesAlarms] whether
     * the re-plan may drop an overdue snooze, keeps no list of its own that could name an update,
     * and after a reboot or an app update ([FireRules.clearsNotices], verify 3) posts the snoozed
     * notices again, after the re-plan has sorted them.
     */
    @Test
    fun aRebootBringsTheSnoozedNoticesBack() {
        val code = receiverSource()
        assertFalse("no restart list of its own", code.contains("RESTART_ACTIONS"))
        val decide = code.indexOf("val restart = FireRules.wipesAlarms(action)")
        val replan = code.indexOf("AlarmScheduling.replan(appContext, afterRestart = restart)")
        val repost = code.indexOf("if (FireRules.clearsNotices(action)) AlarmService.repostNotices(appContext)")
        assertFalse("reposts on more than a reboot", code.contains("if (restart) AlarmService.repostNotices"))
        assertTrue("decides with wipesAlarms", decide >= 0)
        assertTrue("re-plans with that answer", replan > decide)
        assertTrue("reposts the notices after the re-plan", repost > replan)
    }

    private fun receiverSource(): String {
        val path = "src/main/kotlin/no/stormberry/sunapp/alarm/SystemEventReceiver.kt"
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(java.io.File(dir, path), java.io.File(dir, "app/$path"), java.io.File(dir, "android/app/$path"))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        error("SystemEventReceiver.kt not found above ${System.getProperty("user.dir")}")
    }

    // ------------------------------------------------------------------------------ arming

    /** A-7: exact first, inexact when that throws anything, FAILED when both do; never throws. */
    @Test
    fun armingFallsBackAndNeverThrows() {
        val seen = mutableListOf<String>()
        assertEquals(ArmResult.EXACT, FireRules.armWithFallback({ seen += "exact" }, { seen += "inexact" }))
        assertEquals(listOf("exact"), seen)

        var reported: RuntimeException? = null
        assertEquals(
            ArmResult.INEXACT,
            FireRules.armWithFallback(
                exact = { throw SecurityException("revoked") },
                inexact = {},
                onExactFailure = { reported = it },
            ),
        )
        assertTrue(reported is SecurityException)

        assertEquals(
            ArmResult.INEXACT,
            FireRules.armWithFallback({ throw IllegalStateException("vendor cap") }, {}),
        )
        assertEquals(
            ArmResult.FAILED,
            FireRules.armWithFallback(
                exact = { throw IllegalStateException("vendor cap") },
                inexact = { throw IllegalStateException("vendor cap") },
                onExactFailure = { throw IllegalStateException("a broken reporter changes nothing") },
            ),
        )
    }

    // -------------------------------------------------------------------- the last ring

    @Test
    fun planningStartsAfterTheLastRingWhenThatIsLater() {
        val now = Instant.ofEpochMilli(t)
        assertEquals(now, FireRules.planAfter(now, null))
        assertEquals(now, FireRules.planAfter(now, t - 1))
        assertEquals(now, FireRules.planAfter(now, t))
        assertEquals(Instant.ofEpochMilli(t + 1_500L), FireRules.planAfter(now, t + 1_500L))
        // Fix round 1: no further ahead than the horizon.
        assertEquals(Instant.ofEpochMilli(t + 60 * minute), FireRules.planAfter(now, t + 60 * minute))
        assertEquals(now, FireRules.planAfter(now, t + 60 * minute + 1))
    }

    @Test
    fun aRecordBeyondTheHorizonIsPruned() {
        val map = mapOf("past" to t - minute, "near" to t + 60 * minute, "far" to t + 60 * minute + 1)
        assertEquals(mapOf("past" to t - minute, "near" to t + 60 * minute), FireRules.pruneRung(map, t))
    }

    @Test
    fun theLastRingOnlyMovesForwardAndIsPrunedWithItsRule() {
        val once = FireRules.recordRung(emptyMap(), "a", t)
        assertEquals(mapOf("a" to t), once)
        assertEquals(mapOf("a" to t), FireRules.recordRung(once, "a", t - minute))
        assertEquals(mapOf("a" to t + minute), FireRules.recordRung(once, "a", t + minute))
        val two = FireRules.recordRung(once, "b", t)
        assertEquals(mapOf("b" to t), FireRules.pruneRung(two, setOf("b", "c")))
    }

    @Test
    fun theLastRingRoundTripsAndSkipsWhatItCannotRead() {
        val map = mapOf("a" to t, "b" to t + 1)
        assertEquals(map, FireRules.decodeRung(FireRules.encodeRung(map)))
        assertEquals(emptyMap<String, Long>(), FireRules.decodeRung(null))
        assertEquals(emptyMap<String, Long>(), FireRules.decodeRung(""))
        val odd = JSONObject().put("a", t).put("b", "soon").put("c", -1).put("d", JSONArray()).toString()
        assertEquals(mapOf("a" to t), FireRules.decodeRung(odd))
    }

    // --------------------------------------------------------------------------- snoozes

    @Test
    fun aSnoozeRoundTripsThroughStorage() {
        val snoozes = listOf(PendingSnooze("rule-1", t, "Morning"), PendingSnooze("rule-2", t + minute, "Sunset walk"))
        assertEquals(snoozes, FireRules.decodeSnoozes(FireRules.encodeSnoozes(snoozes)))
        assertEquals(emptyList<PendingSnooze>(), FireRules.decodeSnoozes(null))
        assertEquals(emptyList<PendingSnooze>(), FireRules.decodeSnoozes("[]"))
    }

    /** Only a snooze exactly as this version writes it is read back. */
    @Test
    fun aStoredSnoozeThatIsNotExactlyOneOfOursIsSkipped() {
        val good = PendingSnooze("r", t, "L").toJson()
        val rows = JSONArray()
            .put(good)
            .put(JSONObject(good.toString()).put("v", 2))
            .put(JSONObject(good.toString()).put("extra", true))
            .put(JSONObject(good.toString()).put("rule", ""))
            .put(JSONObject(good.toString()).put("at", 0))
            .put(JSONObject(good.toString()).put("at", "soon"))
            .put(JSONObject(good.toString()).put("label", "x".repeat(PendingSnooze.MAX_LABEL + 1)))
            .put("not an object")
        assertEquals(listOf(PendingSnooze("r", t, "L")), FireRules.decodeSnoozes(rows.toString()))
    }

    @Test(expected = Exception::class)
    fun unreadableSnoozeStorageThrowsSoTheCallerCanRing() {
        FireRules.decodeSnoozes("{not json")
    }

    @Test
    fun aNewSnoozeReplacesTheRulesEarlierOne() {
        val first = PendingSnooze("r", t, "L")
        val other = PendingSnooze("s", t, "M")
        val again = PendingSnooze("r", t + 9 * minute, "L")
        assertEquals(listOf(other, again), FireRules.putSnooze(listOf(first, other), again))
        assertEquals(listOf(other), FireRules.removeSnooze(listOf(first, other), "r"))
    }

    /**
     * A-6 and D-5, the three outcomes of the re-plan after a restart: a snooze of a deleted or
     * switched-off rule is cancelled; one that came due more than ten minutes ago is missed; the
     * rest are armed again.
     */
    @Test
    fun aReplanSortsTheSnoozes() {
        val now = t
        val kept = PendingSnooze("kept", now + 5 * minute, "Kept")
        val dueNow = PendingSnooze("due", now - 3 * minute, "Due a moment ago")
        val late = PendingSnooze("late", now - 11 * minute, "Too late")
        val deleted = PendingSnooze("deleted", now + 5 * minute, "Deleted")
        val switchedOff = PendingSnooze("off", now + 5 * minute, "Off")
        val sort = FireRules.sortSnoozes(
            listOf(kept, dueNow, late, deleted, switchedOff),
            liveRuleIds = setOf("kept", "due", "late"),
            now = now,
            dropLate = true,
        )
        // Due three minutes ago after a restart: armed again for its past instant, so it rings
        // at once (decision D-5); the fire receiver forgets it before re-planning.
        assertEquals(listOf(dueNow, kept), sort.keep)
        assertEquals(listOf(late), sort.late)
        assertEquals(listOf(deleted, switchedOff), sort.gone)
    }

    /**
     * Fix round 2 (verify 1, code review S-1): any re-plan other than the one after a restart (the
     * app opening, another rule's alarm firing, a rule edit, a clock change, a snooze) drops no
     * overdue snooze. On Android 12 with exact alarms off, Doze may still be holding the snooze
     * back, and dropping it then left nothing to ring when it arrived and a notice that was untrue.
     * It is kept and armed again; a deleted or switched-off rule's snooze is still cancelled.
     */
    @Test
    fun onlyTheReplanAfterARestartDropsAnOverdueSnooze() {
        val now = t
        val justLate = PendingSnooze("a", now - 11 * minute, "Eleven minutes overdue")
        val longLate = PendingSnooze("b", now - 3 * 60 * minute, "Three hours overdue")
        val soon = PendingSnooze("c", now + 5 * minute, "Soon")
        val deleted = PendingSnooze("gone", now - 11 * minute, "Deleted")
        val all = listOf(justLate, longLate, soon, deleted)
        val live = setOf("a", "b", "c")

        val notRestart = FireRules.sortSnoozes(all, live, now, dropLate = false)
        assertEquals(listOf(longLate, justLate, soon), notRestart.keep)
        assertEquals(emptyList<PendingSnooze>(), notRestart.late)
        assertEquals(listOf(deleted), notRestart.gone)

        val restart = FireRules.sortSnoozes(all, live, now, dropLate = true)
        assertEquals(listOf(soon), restart.keep)
        assertEquals(listOf(justLate, longLate), restart.late)
        assertEquals(listOf(deleted), restart.gone)

        // The limit itself: ten minutes overdue is still kept after a restart, a millisecond more is not.
        val edge = PendingSnooze("a", now - FireRules.SNOOZE_LATE_LIMIT_MS, "Edge")
        assertEquals(listOf(edge), FireRules.sortSnoozes(listOf(edge), live, now, dropLate = true).keep)
        assertEquals(listOf(edge), FireRules.sortSnoozes(listOf(edge), live, now + 1, dropLate = true).late)
    }

    @Test
    fun aReplanKeepsOneSnoozePerRuleAndAtMostSixteen() {
        val many = (0 until 20).map { PendingSnooze("r$it", t + it * minute, "L$it") }
        val twice = listOf(PendingSnooze("x", t + minute, "old"), PendingSnooze("x", t + 2 * minute, "new"))
        val live = many.mapTo(HashSet()) { it.ruleId } + "x"

        for (dropLate in listOf(true, false)) {
            assertEquals(FireRules.MAX_SNOOZES, FireRules.sortSnoozes(many, live, t, dropLate).keep.size)
            assertEquals(listOf(PendingSnooze("x", t + 2 * minute, "new")), FireRules.sortSnoozes(twice, live, t, dropLate).keep)
        }
    }

    // ---------------------------------------------------------------- a second alarm (A-5)

    @Test
    fun aDifferentAlarmStartingWhileOneRingsReportsTheFirst() {
        val first = ringing("a", t)
        val second = ringing("b", t + minute)
        assertEquals(first, FireRules.replaced(first, second))
        // A snooze of the same rule is another ring of it, with another instant.
        assertEquals(first, FireRules.replaced(first, ringing("a", t + 9 * minute)))
        assertNull(FireRules.replaced(first, ringing("a", t)))
        assertNull(FireRules.replaced(null, second))
    }

    @Test
    fun aRingsKeyIsItsRuleAndInstant() {
        assertEquals("a@$t", ringing("a", t).key)
        assertEquals("@$t", ringing(null, t).key)
        assertFalse(ringing("a", t).key == ringing("a", t + 1).key)
    }

    private fun ringing(ruleId: String?, at: Long) = RingingAlarm(
        ruleId = ruleId,
        label = "Alarm",
        placeName = "Bergen",
        anchorText = "at sunrise",
        fireAtMillis = at,
        usedFallback = false,
        isSnooze = false,
        ringtoneUri = null,
        vibrate = true,
    )
}
