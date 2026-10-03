package com.nikita.sleepcycle.night

// File purpose: the Android side of the simulation speed chips and Auto (AutoSimulationSpeed.kt holds the
// rules) - writing a new speed to the live clock and the debug store, and re-arming what was armed under the
// old one. Every path that changes the speed from outside the Debug controller goes through here: the night
// tick applying Auto, and the wake-up moments (Asleep switch, I'm up, an alarm ringing) dropping to 1x.

import android.content.Context
import com.nikita.sleepcycle.BuildConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Emulator test, 2026-10-03: a "1x" tap was lost while Auto ran at 3600x. The tick re-applying Auto read the
 * speed between the tap's two writes (clock, then store) and put 3600x back. Every speed change now reads and
 * writes under this one lock ([changeSimulationSpeed]). Not the night lock: the tick already holds that.
 */
private val simulationSpeedMutex = Mutex()

/** The clock's speed as it is right now: the live warp, and whether Auto is driving it. */
suspend fun readSimulationSpeed(context: Context): SimulationSpeed =
    SimulationSpeed(AppClock.warp(), readDebugOptions(context).first().autoSpeed)

/**
 * Reads the live speed and makes [change]'s answer the new one, as one step no other speed change can come
 * between ([simulationSpeedMutex]); a null answer changes nothing. Writes the clock first, then the store (W4's
 * order - see [writeClockWarp]), then re-arms the pending nudge or own nap under the new mapping. Runs no tick:
 * a caller outside a tick follows up with one, so the wake alarm and the next tick move too. Returns whether
 * the speed changed.
 */
suspend fun changeSimulationSpeed(context: Context, change: (SimulationSpeed) -> SimulationSpeed?): Boolean = simulationSpeedMutex.withLock {
    val next = change(readSimulationSpeed(context)) ?: return@withLock false
    writeClockWarp(context, next.warp)
    updateDebugOptions(context, Instant.now()) { it.copy(autoSpeed = next.auto) }
    rearmPendingFollowUp(context)
    true
}

/**
 * Owner spec, 2026-10-02: puts a fast clock back to 1x and leaves Auto (see [dropToRealSpeed]). A no-op on a
 * clock already at 1x - every real night, and every release build, where the clock is never warped. Returns
 * whether it changed anything, so a caller can skip a tick it would only need for the speed change.
 */
suspend fun dropSimulationToRealSpeed(context: Context): Boolean {
    if (!BuildConfig.DEBUG) return false
    return changeSimulationSpeed(context) { current ->
        if (!current.auto && (current.warp?.speed ?: 1) == 1) null else dropToRealSpeed(current, Instant.now())
    }
}

/**
 * Called by the night tick with the alarms it just planned: while Auto is on, moves the clock to the speed
 * [autoClockSpeed] wants for [now] (600x far from the next alarm, 60x on the approach). Returns whether Auto
 * is on, so the tick knows to pull its next tick in to the slow-down ([autoSpeedTickAt]). [waitingForSleep]:
 * see [autoClockSpeed].
 */
suspend fun applyAutoSpeed(context: Context, now: Instant, plannedAlarmAt: Instant?, followUpAt: Instant?, waitingForSleep: Boolean): Boolean {
    var autoOn = false
    changeSimulationSpeed(context) { current ->
        autoOn = current.auto
        val wanted = autoClockSpeed(now, plannedAlarmAt, followUpAt, waitingForSleep)
        if (!current.auto || current.warp?.speed == wanted) null else chooseSimulationSpeed(current, SpeedChoice.AUTO, Instant.now(), wanted)
    }
    return autoOn
}
