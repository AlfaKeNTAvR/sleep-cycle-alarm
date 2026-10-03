package com.nikita.sleepcycle.night

// File purpose: the Auto simulation speed (owner spec, 2026-10-02). A simulated night is mostly waiting for
// the alarm, and the part worth watching is the alarm itself and what follows it - so Auto runs the clock as
// fast as it can until shortly before the next alarm, then slows down in steps for the approach. Pure: the night tick
// (NightOrchestrator.kt) applies it, the Night screen's Auto chip selects it.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.AlarmPlan
import com.nikita.sleepcycle.engine.sameAlarmInstant
import java.time.Duration
import java.time.Instant

/**
 * Auto's speed while the next alarm is more than [AUTO_STAGES]' longest lead away: 3600x, a simulated hour per
 * real second (owner request, 2026-10-02), so an 8 h night passes in about 8 real seconds.
 */
const val AUTO_FAR_SPEED = 3600

/**
 * Auto's speed while no sleep has been seen yet (or while awake mid-night) and no fixed alarm is ahead (emulator
 * test, 2026-10-03): the planned alarm is then projected from "now" and never comes closer, so running fast only
 * skipped the night.
 */
const val AUTO_WAITING_FOR_SLEEP_SPEED = 60

/** One step of Auto's slow-down: from [lead] (simulated time) before the next alarm, run at [speed]. */
data class AutoStage(val lead: Duration, val speed: Int)

/**
 * Auto's slow-down before an alarm, nearest first (owner spec, 2026-10-02): 600x from 30 simulated minutes
 * out, 60x from 10 (no 10x anywhere). The 600x stage is also what makes the 60x one land on time: at 3600x
 * the tick floor (TickScheduling.kt's 250 ms) is 15 simulated minutes, longer than the 10 minute lead, so a
 * slow-down straight from 3600x could be skipped; at 600x the floor is 2.5 simulated minutes.
 */
val AUTO_STAGES: List<AutoStage> = listOf(
    AutoStage(Duration.ofMinutes(10), 60),
    AutoStage(Duration.ofMinutes(30), 600),
)

/** The Night screen's speed chips, in order: 1x, 60x, and Auto (which replaced the manual 600x). */
enum class SpeedChoice(val fixedSpeed: Int?) {
    REAL(1),
    FAST(60),
    AUTO(null),
}

/** Which chip [speed] shows as selected. Any speed other than 60x outside Auto reads as 1x - the only other speed a clock is left at by hand. */
fun speedChoiceOf(speed: SimulationSpeed): SpeedChoice = when {
    speed.auto -> SpeedChoice.AUTO
    speed.warp?.speed == SpeedChoice.FAST.fixedSpeed -> SpeedChoice.FAST
    else -> SpeedChoice.REAL
}

/** [current] after tapping [choice]: a fixed chip leaves Auto; Auto starts at [autoSpeedNow], the speed [autoClockSpeed] wants for where the night is. Re-anchored at the current simulated instant, so the clock never jumps. */
fun chooseSimulationSpeed(current: SimulationSpeed, choice: SpeedChoice, realNow: Instant, autoSpeedNow: Int): SimulationSpeed =
    SimulationSpeed(computeSpeedChangeWarp(current.warp, choice.fixedSpeed ?: autoSpeedNow, realNow), auto = choice == SpeedChoice.AUTO)

/** The simulated clock as the speed chips see it: the live [warp] (null at real time) and whether Auto is driving it. */
data class SimulationSpeed(val warp: ClockWarp?, val auto: Boolean)

/**
 * Owner spec, 2026-10-02: the Asleep switch (either way), "I'm up" and an alarm starting to ring all put the
 * clock back to 1x and leave Auto, so whatever happens next is watched at real speed. Re-anchored at the
 * current simulated instant, so the clock slows down without jumping.
 */
fun dropToRealSpeed(current: SimulationSpeed, realNow: Instant): SimulationSpeed =
    SimulationSpeed(computeSpeedChangeWarp(current.warp, 1, realNow), auto = false)

/**
 * The speed Auto runs at for [now], given the plan's next alarm [plannedAlarmAt] (see [autoSpeedAlarmAt]) and the
 * out-of-bed slot's pending alarm [followUpAt] (nudge or own nap). [waitingForSleep]: no sleep seen yet, so with
 * no alarm ahead Auto waits at [AUTO_WAITING_FOR_SLEEP_SPEED].
 */
fun autoClockSpeed(now: Instant, plannedAlarmAt: Instant?, followUpAt: Instant?, waitingForSleep: Boolean = false): Int {
    val nextAlarmAt = nextAlarmAhead(now, plannedAlarmAt, followUpAt)
        ?: return if (waitingForSleep) AUTO_WAITING_FOR_SLEEP_SPEED else AUTO_FAR_SPEED
    return AUTO_STAGES.firstOrNull { stage -> !nextAlarmAt.isAfter(now.plus(stage.lead)) }?.speed ?: AUTO_FAR_SPEED
}

/**
 * The next tick under Auto: [ordinaryTickAt] (null when the night needs no more ticks), pulled in to the next
 * slow-down instant when that comes first - the tick is what applies the speed, and the ordinary 5 to 15
 * simulated minutes between syncs could otherwise jump right over a stage.
 */
fun autoSpeedTickAt(ordinaryTickAt: Instant?, now: Instant, plannedAlarmAt: Instant?, followUpAt: Instant?): Instant? {
    if (ordinaryTickAt == null) return null
    val nextAlarmAt = nextAlarmAhead(now, plannedAlarmAt, followUpAt) ?: return ordinaryTickAt
    val nextSlowDownAt = AUTO_STAGES.map { stage -> nextAlarmAt.minus(stage.lead) }.filter { it.isAfter(now) }.minOrNull()
    return if (nextSlowDownAt != null && nextSlowDownAt.isBefore(ordinaryTickAt)) nextSlowDownAt else ordinaryTickAt
}

/**
 * The alarm Auto runs towards for [plan]: its own alarm once sleep has been seen, and only the fixed alarms
 * while the alarm is projected from "now" (a projected one would stay the same distance ahead for ever): the
 * night's [deadline], and the latched [morningAlarmAt] when the plan keeps it (H8, awake mid-night before it).
 *
 * Owner decision, 2026-10-03 (spec-audit.md #5): awake mid-night Auto slows down before the planned morning
 * alarm or the deadline, whichever comes first, like any other alarm - it used to run at 3600x towards the
 * deadline straight past H8's morning alarm.
 */
fun autoSpeedAlarmAt(plan: AlarmPlan?, deadline: Instant?, morningAlarmAt: Instant?): Instant? {
    if (plan?.onsetIsProjected != true) return plan?.wakeAt
    val keptMorningAlarm = plan.wakeAt?.takeIf { plan.mode == AlarmMode.NAP && sameAlarmInstant(it, morningAlarmAt) }
    return listOfNotNull(deadline, keptMorningAlarm).minOrNull()
}

/** The earliest of the two alarms still ahead of [now], or null when neither is. */
private fun nextAlarmAhead(now: Instant, plannedAlarmAt: Instant?, followUpAt: Instant?): Instant? =
    listOfNotNull(plannedAlarmAt, followUpAt).filter { it.isAfter(now) }.minOrNull()
