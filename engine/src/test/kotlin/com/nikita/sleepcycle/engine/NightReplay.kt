package com.nikita.sleepcycle.engine

import java.time.Instant

/**
 * A faithful stand-in for the whole app-layer alarm loop, so a test can drive a night the way a real (or
 * simulated) night actually runs it: ticks scheduled by [nextSyncDelay], the phone alarm armed and re-armed
 * by every tick, that alarm actually FIRING when its instant arrives, and the four facts
 * PhoneAlarmReceiver records at fire time fed back into the next [computeAlarmPlan] call.
 *
 * Every rule below mirrors one named app-layer function; the engine module cannot import them (they live in
 * :app, on top of Android), so they are restated here and named in comments so a change on either side is
 * easy to find:
 *  - arming: NightOrchestrator.armPhoneAlarmIfNeeded / shouldArmPhoneAlarm
 *  - fire-time attribution: PhoneAlarmReceiver.recordWakeOrNapFired / NightOrchestrator.firedAlarmIsWakeAlarm
 *  - the morning-alarm latch: NightOrchestrator.latchMorningAlarmAt
 *  - the sleep timeline: BandDataSimulator.buildSimulatedSegments (the Debug screen's "Asleep" toggle)
 *
 * When a tick and an armed alarm fall on the same instant, the alarm is delivered FIRST. On a real phone
 * that is a race, and this is the optimistic side of it - the side where the firing is recorded correctly.
 * The pessimistic side (a tick landing first and re-arming over an alarm already in flight) is the G3
 * mismatch PhoneAlarmReceiver.recordWakeOrNapFired already logs; nothing asserted here depends on it.
 */
internal class NightReplay(
    private val setting: NightSettings,
    private val config: EngineConfig = EngineConfig()
) {
    /** One flip of the Debug screen's "Asleep" toggle, or the band reporting the same transition. */
    private data class SleepMark(val asleep: Boolean, val at: Instant)

    /** One alarm that actually rang: the instant it was armed for, and the mode of the plan it was attributed to (null when attribution failed). */
    data class Firing(val firedFor: Instant, val mode: AlarmMode?)

    private val marks = mutableListOf<SleepMark>()
    private var nextTickAt: Instant? = null
    private var armedAlarmAt: Instant? = null
    private var phoneAlarmFiredFor: Instant? = null
    private var lastNapAlarmFiredAt: Instant? = null
    private var morningAlarmAt: Instant? = null

    /** F6: the instant the MAIN wake alarm fired, as PhoneAlarmReceiver would have recorded it. */
    var wakeAlarmFiredAt: Instant? = null
        private set

    /** F2/G8: how many nap alarms have actually rung this night. */
    var napAlarmsUsed: Int = 0
        private set

    /** The plan the last tick produced - the app's NightState.lastPlan. */
    var lastPlan: AlarmPlan? = null
        private set

    /** Every alarm that rang this night, in order. */
    val firings = mutableListOf<Firing>()

    /** Every arming of the phone alarm, in order: the tick that armed it, and the instant it was armed for. */
    val armings = mutableListOf<Pair<Instant, Instant>>()

    /** Every tick's own plan, in order, for asserting on a whole sequence rather than one call. */
    val plans = mutableListOf<AlarmPlan>()

    /** Runs every tick and every alarm firing due up to and including [at]. */
    fun advanceTo(at: String) {
        advanceTo(instant(at))
    }

    /** The Debug screen's "Asleep" toggle flipped to asleep at [at]; like the real screen, it also requests an immediate tick. */
    fun markAsleep(at: String) {
        mark(asleep = true, at = at)
    }

    /** The Debug screen's "Asleep" toggle flipped to awake at [at] - the owner marking "I am not asleep any more". */
    fun markAwake(at: String) {
        mark(asleep = false, at = at)
    }

    /** The distinct instants the phone alarm was armed for by the ticks from [at] onward - what the owner watching the night screen would see the alarm time do. */
    fun armedTargetsAfter(at: String): List<Instant> {
        val from = instant(at)
        return armings.filter { (armedAt, _) -> !armedAt.isBefore(from) }.map { (_, target) -> target }.distinct()
    }

    /** Every alarm that rang at or after [at]. */
    fun firingsAfter(at: String): List<Firing> {
        val from = instant(at)
        return firings.filter { !it.firedFor.isBefore(from) }
    }

    private fun mark(asleep: Boolean, at: String) {
        val markAt = instant(at)
        advanceTo(markAt)
        marks.add(SleepMark(asleep, markAt))
        runTick(markAt)
    }

    private fun advanceTo(at: Instant) {
        while (true) {
            val due = listOfNotNull(nextTickAt, armedAlarmAt).filter { !it.isAfter(at) }.minOrNull() ?: return
            if (armedAlarmAt == due) fireAlarm(due) else runTick(due)
        }
    }

    /**
     * PhoneAlarmReceiver.onReceive: the armed alarm rings, records the instant it was scheduled for, and is
     * attributed to either the main wake alarm or a nap from the plan in effect at that moment
     * (recordWakeOrNapFired - attribution is skipped when that plan's own wakeAt no longer matches).
     */
    private fun fireAlarm(at: Instant) {
        armedAlarmAt = null
        phoneAlarmFiredFor = at
        val firedPlan = lastPlan?.takeIf { it.wakeAt == at }
        firings.add(Firing(at, firedPlan?.mode))
        if (firedPlan == null) return
        // NightOrchestrator.firedAlarmIsWakeAlarm, H8 included: a firing at the latched morning alarm time is
        // the wake alarm whatever mode the plan that armed it carried.
        if (firedPlan.mode != AlarmMode.NAP || at == morningAlarmAt) {
            wakeAlarmFiredAt = at
        } else {
            napAlarmsUsed = (napAlarmsUsed + 1).coerceAtMost(MAX_NAP_ALARMS)
            lastNapAlarmFiredAt = at
        }
    }

    /** NightOrchestrator.runNightTick: read the timeline, compute the plan, arm the alarm, latch the morning alarm, schedule the next tick. */
    private fun runTick(at: Instant) {
        val plan = computeAlarmPlan(
            segmentsAt(at), setting, at, morningAlarmAt, testZone, config, wakeAlarmFiredAt, napAlarmsUsed, lastNapAlarmFiredAt,
            phoneAlarmFiredFor
        )
        armPhoneAlarmIfNeeded(plan, at)
        morningAlarmAt = latchMorningAlarmAt(morningAlarmAt, plan)
        lastPlan = plan
        plans.add(plan)
        nextTickAt = nextSyncDelay(plan, at, config)?.let { at.plus(it) }
    }

    /** NightOrchestrator.armPhoneAlarmIfNeeded: a null wake time cancels, an instant that already fired or is not in the future is left alone, anything else is (re-)armed. */
    private fun armPhoneAlarmIfNeeded(plan: AlarmPlan, now: Instant) {
        val wakeAt = plan.wakeAt
        if (wakeAt == null) {
            armedAlarmAt = null
            return
        }
        if (wakeAt == phoneAlarmFiredFor || !wakeAt.isAfter(now)) return
        armedAlarmAt = wakeAt
        armings.add(now to wakeAt)
    }

    /** NightOrchestrator.latchMorningAlarmAt: only a FULL_CYCLES or DEADLINE_ONLY plan sets the night's morning alarm time, and a null wakeAt never erases it (H8). */
    private fun latchMorningAlarmAt(previous: Instant?, plan: AlarmPlan): Instant? = when (plan.mode) {
        AlarmMode.FULL_CYCLES, AlarmMode.DEADLINE_ONLY -> plan.wakeAt ?: previous
        AlarmMode.NAP, AlarmMode.FINISHED -> previous
    }

    /** BandDataSimulator.buildSimulatedSegments: each mark opens a segment that runs to the next mark, or to [now] for the most recent one. */
    private fun segmentsAt(now: Instant): List<SleepSegment> =
        marks.indices.mapNotNull { index ->
            val mark = marks[index]
            val end = marks.getOrNull(index + 1)?.at ?: now
            if (!end.isAfter(mark.at)) {
                null
            } else {
                SleepSegment(mark.at, end, if (mark.asleep) SegmentKind.LIGHT else SegmentKind.AWAKE)
            }
        }

    /** A readable trace of the whole night, for a failure message that says what the sequence actually did. */
    fun trace(): String = buildString {
        plans.forEach { appendLine("plan ${it.mode} wakeAt=${it.wakeAt}") }
        firings.forEach { appendLine("fired ${it.firedFor} attributedTo=${it.mode}") }
    }
}
