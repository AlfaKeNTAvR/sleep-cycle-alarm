package com.nikita.sleepcycle.night

// File purpose: the Auto simulation speed (owner spec, 2026-10-02). A simulated night is mostly waiting for
// the alarm, and the part worth watching is the alarm itself and what follows it - so Auto runs the clock as
// fast as it can until shortly before the next alarm, then slows down for the approach. Pure: the night tick
// (NightOrchestrator.kt) applies it, the Night screen's Auto chip selects it.

import java.time.Duration
import java.time.Instant

/** Auto's speed while the next alarm is still far off - the practical ceiling, see [SIMULATION_SPEEDS]. */
const val AUTO_FAR_SPEED = 600

/** Auto's speed for the approach to an alarm: a simulated minute every 6 real seconds. */
const val AUTO_NEAR_SPEED = 10

/** How long before the next alarm, in simulated time, Auto slows to [AUTO_NEAR_SPEED]. */
val AUTO_SLOW_DOWN_LEAD: Duration = Duration.ofMinutes(10)

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

/** The speed Auto runs at for [now], given the plan's next alarm [plannedAlarmAt] and the out-of-bed slot's pending alarm [followUpAt] (nudge or own nap). */
fun autoClockSpeed(now: Instant, plannedAlarmAt: Instant?, followUpAt: Instant?): Int {
    val nextAlarmAt = nextAlarmAhead(now, plannedAlarmAt, followUpAt) ?: return AUTO_FAR_SPEED
    return if (nextAlarmAt.isAfter(now.plus(AUTO_SLOW_DOWN_LEAD))) AUTO_FAR_SPEED else AUTO_NEAR_SPEED
}

/**
 * The next tick under Auto: [ordinaryTickAt] (null when the night needs no more ticks), pulled in to the
 * slow-down instant when that comes first - the tick is what applies the speed, and the ordinary 5 to 15
 * simulated minutes between syncs could otherwise jump right over the 10 minute approach.
 */
fun autoSpeedTickAt(ordinaryTickAt: Instant?, now: Instant, plannedAlarmAt: Instant?, followUpAt: Instant?): Instant? {
    if (ordinaryTickAt == null) return null
    val slowDownAt = nextAlarmAhead(now, plannedAlarmAt, followUpAt)?.minus(AUTO_SLOW_DOWN_LEAD) ?: return ordinaryTickAt
    return if (slowDownAt.isAfter(now) && slowDownAt.isBefore(ordinaryTickAt)) slowDownAt else ordinaryTickAt
}

/** The earliest of the two alarms still ahead of [now], or null when neither is. */
private fun nextAlarmAhead(now: Instant, plannedAlarmAt: Instant?, followUpAt: Instant?): Instant? =
    listOfNotNull(plannedAlarmAt, followUpAt).filter { it.isAfter(now) }.minOrNull()
