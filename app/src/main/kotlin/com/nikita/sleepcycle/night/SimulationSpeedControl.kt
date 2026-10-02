package com.nikita.sleepcycle.night

// File purpose: the Android side of the simulation speed chips and Auto (AutoSimulationSpeed.kt holds the
// rules) - writing a new speed to the live clock and the debug store, and re-arming what was armed under the
// old one. Every path that changes the speed from outside the Debug controller goes through here: the night
// tick applying Auto, and the wake-up moments (Asleep switch, I'm up, an alarm ringing) dropping to 1x.

import android.content.Context
import com.nikita.sleepcycle.BuildConfig
import kotlinx.coroutines.flow.first
import java.time.Instant

/** The clock's speed as it is right now: the live warp, and whether Auto is driving it. */
suspend fun readSimulationSpeed(context: Context): SimulationSpeed =
    SimulationSpeed(AppClock.warp(), readDebugOptions(context).first().autoSpeed)

/**
 * Makes [next] the live speed: clock first, then the store (W4's order - see [writeClockWarp]), then the
 * pending nudge or own nap re-armed under the new mapping. Runs no tick: a caller outside a tick follows up
 * with one, so the wake alarm and the next tick move too.
 */
suspend fun applySimulationSpeed(context: Context, next: SimulationSpeed) {
    writeClockWarp(context, next.warp)
    updateDebugOptions(context, Instant.now()) { it.copy(autoSpeed = next.auto) }
    rearmPendingFollowUp(context)
}

/**
 * Owner spec, 2026-10-02: puts a fast clock back to 1x and leaves Auto (see [dropToRealSpeed]). A no-op on a
 * clock already at 1x - every real night, and every release build, where the clock is never warped. Returns
 * whether it changed anything, so a caller can skip a tick it would only need for the speed change.
 */
suspend fun dropSimulationToRealSpeed(context: Context): Boolean {
    if (!BuildConfig.DEBUG) return false
    val current = readSimulationSpeed(context)
    if (!current.auto && (current.warp?.speed ?: 1) == 1) return false
    applySimulationSpeed(context, dropToRealSpeed(current, Instant.now()))
    return true
}

/**
 * Called by the night tick with the alarms it just planned: while Auto is on, moves the clock to the speed
 * [autoClockSpeed] wants for [now] (600x far from the next alarm, 60x on the approach). Returns whether Auto
 * is on, so the tick knows to pull its next tick in to the slow-down ([autoSpeedTickAt]).
 */
suspend fun applyAutoSpeed(context: Context, now: Instant, plannedAlarmAt: Instant?, followUpAt: Instant?): Boolean {
    val current = readSimulationSpeed(context)
    if (!current.auto) return false
    val wanted = autoClockSpeed(now, plannedAlarmAt, followUpAt)
    if (current.warp?.speed != wanted) {
        applySimulationSpeed(context, chooseSimulationSpeed(current, SpeedChoice.AUTO, Instant.now(), wanted))
    }
    return true
}
