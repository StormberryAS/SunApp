package no.stormberry.sunapp.alarm

/**
 * The seam between the alarm engine, which is pure and fully tested, and `AlarmManager`,
 * which is neither.
 *
 * Everything interesting about SunApp's scheduling (which rule fires next, what happens on
 * the night the clocks change, what a Longyearbyen rule does in December) is decided by
 * [AlarmPlanner] and `OccurrenceEngine` above this interface, on the JVM, against a fixed
 * clock. Below it there is one implementation that does nothing but translate a
 * [PlannedAlarm] into `setAlarmClock` and a request code into `cancel`. Keeping that
 * translation stupid is the point: it is the only part of the alarm path a unit test cannot
 * reach, so the less judgement it contains the less can go wrong unobserved.
 *
 * Implementations must be tolerant rather than strict. `setAlarmClock` can throw
 * `SecurityException` even immediately after `canScheduleExactAlarms()` returned true,
 * because the grant can be revoked between the check and the call, and an exception escaping
 * a boot receiver would leave every other alarm unarmed. Swallow, record, and let the
 * diagnostics screen show the gap.
 */
interface AlarmSink {

    /**
     * Arm [planned], replacing any alarm already registered under the same request code.
     *
     * Replacement rather than duplication is what makes re-planning idempotent: a boot, a
     * time-zone change and a rule edit can all call this for the same rule and leave exactly
     * one pending alarm, because the request code is derived from the rule id and stays
     * stable for the life of the rule.
     */
    fun arm(planned: PlannedAlarm)

    /**
     * Cancel whatever is registered under [requestCode], if anything.
     *
     * Takes the code rather than the rule because the common caller is cleaning up after a
     * rule that no longer exists, and there is nothing left to pass. Cancelling a code that
     * was never armed must be a no-op, not an error.
     */
    fun cancel(requestCode: Int)
}
