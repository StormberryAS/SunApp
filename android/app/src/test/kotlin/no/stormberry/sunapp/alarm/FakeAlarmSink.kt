package no.stormberry.sunapp.alarm

/**
 * An [AlarmSink] that records what it was asked to do instead of talking to `AlarmManager`.
 *
 * The real sink is the one part of the alarm path a unit test cannot reach, so the whole
 * design pushes every decision above it and this fake is what collects the results. It
 * records an ordered log rather than only the final sets, because two of the properties
 * [AlarmPlanner.apply] promises are about *sequence* and not about state: cancels happen
 * before arms, and a rule that survives a re-plan is never momentarily unarmed. A pair of
 * sets cannot tell those apart from the alternative that leaves a gap.
 *
 * Deliberately not tolerant of failure the way the production sink must be. The interface
 * KDoc tells implementations to swallow a `SecurityException` from `setAlarmClock`, because
 * one revoked grant must not stop a boot receiver arming everything else; this fake instead
 * records faithfully and never throws, so that a test failure here always means the planner
 * asked for the wrong thing rather than the fake having opinions of its own.
 */
internal class FakeAlarmSink : AlarmSink {

    /** One recorded call, in the order it arrived. */
    sealed interface Call {
        data class Arm(val planned: PlannedAlarm) : Call
        data class Cancel(val requestCode: Int) : Call
    }

    private val log = mutableListOf<Call>()

    /** Every call in order. The ordering assertions read this; the rest read the views below. */
    val calls: List<Call> get() = log.toList()

    /** Everything armed, in the order the planner armed it. */
    val armed: List<PlannedAlarm> get() = log.filterIsInstance<Call.Arm>().map { it.planned }

    /** Every request code cancelled, in order, including any cancelled more than once. */
    val cancelled: List<Int> get() = log.filterIsInstance<Call.Cancel>().map { it.requestCode }

    /** The request codes currently armed, which is what a caller would persist between runs. */
    val armedCodes: Set<Int> get() = armed.map { it.requestCode }.toSet()

    /** The fire instants armed, in order. The shorthand most assertions actually want. */
    val armedFireTimes: List<java.time.Instant> get() = armed.map { it.occurrence.fireAt }

    /** Clear the log without building a new sink, so a test can replay boot or a re-plan
     *  against the same instance and compare the second round in isolation. */
    fun reset() = log.clear()

    override fun arm(planned: PlannedAlarm) {
        log += Call.Arm(planned)
    }

    override fun cancel(requestCode: Int) {
        log += Call.Cancel(requestCode)
    }
}
