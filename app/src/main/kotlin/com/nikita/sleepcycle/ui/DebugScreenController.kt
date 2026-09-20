package com.nikita.sleepcycle.ui

// File purpose: owns the Debug screen's persisted state (the two switches, the clock warp, the simulated
// sleep timeline) and its actions, kept out of NightViewModel.kt so that already-large file does not grow by
// one property/method per Debug screen feature. Not a ViewModel itself: constructed and driven by
// NightViewModel, the one place with a CoroutineScope and an Android Context to hand it.
//
// U1/U2: every action that touches the clock warp - [setSpeed], [applyClockJump], [resetToRealTime], and
// [setSimulatedBandData] turning the switch off - routes through [normalizedWarp] (the one rule for when a
// warp exists at all, via computeSpeedChangeWarp/computeJumpWarp) and, for clearing it, through the single
// private [clearClockWarp] - so U5's own instruction ("the same function, not a parallel copy") holds for
// real, not just by convention.
//
// V4: every warp mutation is guarded against a running night, not just the jump - see
// [isSimulatedBandDataToggleAllowed]'s own doc for why turning simulated band data off mid-night is just as
// unsafe as jumping mid-night, and [resetIfIdle]'s own doc for why the idle auto-reset is skipped outright
// instead. V5: [setSpeed] is the one warp mutation that STAYS legal mid-night - see its own doc.

import android.content.Context
import com.nikita.sleepcycle.BuildConfig
import com.nikita.sleepcycle.night.AppClock
import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.NightLogEvent
import com.nikita.sleepcycle.night.SimulatedSleepEvent
import com.nikita.sleepcycle.night.SimulatedSleepEventKind
import com.nikita.sleepcycle.night.appendSetupLog
import com.nikita.sleepcycle.night.appendSimulatedSleepEvent
import com.nikita.sleepcycle.night.clearedSimulatedSleepEvents
import com.nikita.sleepcycle.night.computeJumpWarp
import com.nikita.sleepcycle.night.computeSpeedChangeWarp
import com.nikita.sleepcycle.night.isDebugTestAlarmAllowed
import com.nikita.sleepcycle.night.isJumpToTimeAllowed
import com.nikita.sleepcycle.night.isResetToRealTimeAllowed
import com.nikita.sleepcycle.night.isSimulatedBandDataToggleAllowed
import com.nikita.sleepcycle.night.isSimulatedSleepControlAllowed
import com.nikita.sleepcycle.night.isSpeedSelectorAllowed
import com.nikita.sleepcycle.night.nowInstant
import com.nikita.sleepcycle.night.observedNightState
import com.nikita.sleepcycle.night.readDebugOptions
import com.nikita.sleepcycle.night.readDebugOptionsLastChangedAt
import com.nikita.sleepcycle.night.readSimulatedSleepEvents
import com.nikita.sleepcycle.night.rearmAfterSpeedChange
import com.nikita.sleepcycle.night.requestImmediateTick
import com.nikita.sleepcycle.night.resetDebugOptionsAndClock
import com.nikita.sleepcycle.night.resolveDebugOptions
import com.nikita.sleepcycle.night.scheduleDebugTestAlarm
import com.nikita.sleepcycle.night.shouldAutoResetDebugOptions
import com.nikita.sleepcycle.night.updateDebugOptions
import com.nikita.sleepcycle.night.virtualNow
import com.nikita.sleepcycle.night.writeClockWarp
import com.nikita.sleepcycle.night.writeSimulatedSleepEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Everything the Debug screen persists and can act on, plus the one seam ([effectiveOptions]) that forces every switch off outside a debug build. */
class DebugScreenController(private val context: Context, private val scope: CoroutineScope) {
    val storedOptions: StateFlow<DebugOptions> = readDebugOptions(context).stateIn(scope, SharingStarted.Eagerly, DebugOptions())
    val simulatedSleepEvents: StateFlow<List<SimulatedSleepEvent>> = readSimulatedSleepEvents(context).stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** A1: when the switches were last changed, for [resetIfIdle]. */
    private val lastChangedAt: StateFlow<Instant?> = readDebugOptionsLastChangedAt(context).stateIn(scope, SharingStarted.Eagerly, null)

    private val confirmingNightStartFlow = MutableStateFlow(false)
    val confirmingNightStart: StateFlow<Boolean> = confirmingNightStartFlow

    /** The debug options actually allowed to take effect right now - [resolveDebugOptions] forces every switch off outside a debug build, regardless of what got left in DataStore. */
    fun effectiveOptions(): DebugOptions = resolveDebugOptions(BuildConfig.DEBUG, storedOptions.value)

    /** D1: whether "Ring phone alarm in 1 min" may be tapped right now - never while a night is active. */
    fun canRingTestAlarm(): Boolean = isDebugTestAlarmAllowed(nightActive = observedNightState.value != null)

    /**
     * U1: turning simulated band data off clears any active clock warp too, through the same [clearClockWarp]
     * "Reset to real time" (U2) uses - a warp and real band data must never be live at once (see this file's
     * own header). Turning it on is unconditional - the speed selector and jump only unlock afterwards.
     *
     * V4: refused (a no-op) while a night is active, in EITHER direction - see
     * [isSimulatedBandDataToggleAllowed]'s own doc for why turning it off specifically is unsafe mid-night
     * (it would clear the warp out from under a running night's own frozen debugOptions snapshot), and why the
     * whole toggle is simplest disabled rather than half-guarded. The Debug screen also disables the whole
     * toggle for that case; this is the second guard.
     */
    fun setSimulatedBandData(enabled: Boolean) {
        if (!isSimulatedBandDataToggleAllowed(nightActive = observedNightState.value != null)) return
        if (enabled) {
            persistOptions { it.copy(simulatedBandData = enabled) }
            return
        }
        // W6 (owner decision, 2026-09-20): turning the switch off also wakes the simulator up. Leaving the
        // "Asleep" toggle showing asleep with the switch off is a state the owner cannot act on - the toggle
        // is disabled in that case - and the timeline would keep one sleep segment open forever.
        //
        // The three writes are one coroutine, in this order, because each depends on the one before:
        // the AWAKE mark must be stamped while the SIMULATED clock is still live, or it closes a segment that
        // opened at (say) virtual 03:00 with a real afternoon instant and invents hours of sleep; and the
        // clock must be back to real time before the switch itself is persisted, since the switch is what
        // unlocks the controls that would then act on the clock.
        scope.launch {
            val awakened = appendSimulatedSleepEvent(simulatedSleepEvents.value, SimulatedSleepEventKind.AWAKE, nowInstant())
            if (awakened != simulatedSleepEvents.value) writeSimulatedSleepEvents(context, awakened)
            writeClockWarp(context, null)
            updateDebugOptions(context, Instant.now()) { it.copy(simulatedBandData = false) }
            requestImmediateTick(context)
        }
    }

    /**
     * T7/U1/U2/V5: the Debug screen's speed selector. Refused (a no-op) while simulated band data is off - the
     * Debug screen also disables the control for that case (U1), this is the second guard, same pattern as
     * [ringTestAlarm]. Otherwise re-anchors the warp at the CURRENT virtual instant and [speed] in one call
     * ([computeSpeedChangeWarp], the SAME function this action shares with its own test), so changing speed
     * never itself jumps the clock - only the rate at which it moves from here on.
     *
     * V5: the ONE warp mutation that stays legal mid-night (U2's "drop to 1x and watch by hand" is a wanted
     * mode) - but every real instant already armed under the OLD mapping (the out-of-bed nudge, its pre-nudge
     * check, the wake alarm, the next tick) would otherwise keep firing at the STALE real instant. Once the new
     * warp is written and live in [AppClock], [rearmAfterSpeedChange] re-arms every one of them from its own
     * stored virtual instant, in the SAME coroutine, before this call returns - see its own doc for exactly
     * what is re-armed and what self-heals on its own.
     */
    fun setSpeed(speed: Int) {
        if (!isSpeedSelectorAllowed(storedOptions.value.simulatedBandData)) return
        scope.launch {
            writeClockWarp(context, computeSpeedChangeWarp(AppClock.warp(), speed, Instant.now()))
            rearmAfterSpeedChange(context)
        }
    }

    /**
     * T11/U1: jumps the virtual clock to [time] on the current virtual date (system zone), keeping the current
     * speed - moving backwards is allowed. Refused (a no-op) while simulated band data is off or a night is
     * active (see [isJumpToTimeAllowed]); the Debug screen also disables the control for both cases, this is
     * the second guard. Persists the new warp (survives a restart, same as [setSpeed]) and appends a
     * `clock_jumped` event to the setup log with the old and new virtual instants and the speed.
     */
    fun applyClockJump(time: LocalTime) {
        if (!isJumpToTimeAllowed(storedOptions.value.simulatedBandData, nightActive = observedNightState.value != null)) return
        scope.launch {
            val zone = ZoneId.systemDefault()
            val realNow = Instant.now()
            val currentWarp = AppClock.warp()
            val oldVirtual = virtualNow(currentWarp, realNow)
            val newVirtual = oldVirtual.atZone(zone).toLocalDate().atTime(time).atZone(zone).toInstant()
            val newWarp = computeJumpWarp(currentWarp, newVirtual, realNow)
            writeClockWarp(context, newWarp)
            appendSetupLog(
                context,
                NightLogEvent(realNow, "clock_jumped", mapOf("from" to oldVirtual.toString(), "to" to newVirtual.toString(), "speed" to (newWarp?.speed ?: 1).toString()))
            )
        }
    }

    /**
     * U2: the only action that discards an applied jump - sets the warp to null and speed to 1, without
     * touching simulatedBandData. Contrast [resetDebugOptionsAndClock] (U5), which also forces every switch
     * off, for the unrelated night-end/idle-reset case.
     */
    fun resetToRealTime() {
        // W2: available whether or not simulated band data is on - this is how a leftover warp gets cleared.
        // Still refused mid-night (see isResetToRealTimeAllowed); the button is disabled for that case too,
        // this is the second guard.
        if (!isResetToRealTimeAllowed(nightActive = observedNightState.value != null)) return
        clearClockWarp()
    }

    /** U1/U2/U5/V7: the one place that clears the clock warp - [setSimulatedBandData] and [resetToRealTime] both call this rather than each writing null themselves. Speed needs no separate reset: it is derived from the warp (V7), so it reads back as 1 the instant the warp itself is null. */
    private fun clearClockWarp() {
        scope.launch { writeClockWarp(context, null) }
    }

    /**
     * T9: sets the simulated sleep state directly, replacing the old three-button fellAsleepNow/wokeUpNow/
     * fellBackAsleepNow with one toggle. T10: refused (a no-op) while simulated band data is off - the Debug
     * screen also disables the toggle for that case, this is the second guard.
     */
    fun setSimulatedAsleep(asleep: Boolean) {
        if (!isSimulatedSleepControlAllowed(storedOptions.value.simulatedBandData)) return
        recordEvent(if (asleep) SimulatedSleepEventKind.ASLEEP else SimulatedSleepEventKind.AWAKE)
    }

    /**
     * W2 SUPERSEDES T10 for this one action: clearing the timeline is allowed whether or not simulated band
     * data is on. T10's gate was meant to stop presses that look like they do something and do not; but a
     * leftover timeline from an earlier session outlives the switch, and gating the clear behind the switch
     * means the only way to empty it is to turn the switch back on first. Adding the state is gated; removing
     * it is not.
     */
    fun clearSimulatedSleep() {
        scope.launch {
            writeSimulatedSleepEvents(context, clearedSimulatedSleepEvents())
            requestImmediateTick(context)
        }
    }

    /** "Ring phone alarm in 1 min": exercises the real alarm path in daylight, without starting or touching a night (D1). The button is also disabled per [canRingTestAlarm]; this is the second guard. */
    fun ringTestAlarm() {
        if (!canRingTestAlarm()) return
        scope.launch(Dispatchers.IO) { scheduleDebugTestAlarm(context, Instant.now()) }
    }

    fun requestNightStartConfirmation() { confirmingNightStartFlow.value = true }
    fun clearNightStartConfirmation() { confirmingNightStartFlow.value = false }

    /**
     * A1/T7: called on every app open/resume (NightViewModel.onResumed) - resets every switch (and any active
     * clock warp) once [shouldAutoResetDebugOptions] says the idle window has passed, so a desk test left on in
     * the afternoon cannot leak into bedtime.
     *
     * V4: skipped ENTIRELY while a night is active - [resetDebugOptionsAndClock] now also clears the warp
     * (T7/A1), so running it mid-night would move the clock out from under a running night's own frozen
     * debugOptions snapshot, exactly the case [setSimulatedBandData]'s own guard exists to prevent. Unlike that
     * guard, this is not a user-facing control, so there is nothing to disable-with-reason - it simply does
     * nothing while a night is active, and resumes checking again once it ends.
     */
    fun resetIfIdle(now: Instant) {
        if (observedNightState.value != null) return
        if (storedOptions.value.isAnyEnabled && shouldAutoResetDebugOptions(lastChangedAt.value, now)) {
            scope.launch { resetDebugOptionsAndClock(context, now) }
        }
    }

    /** Appends a simulated sleep event and, per the task spec, requests an immediate tick so the effect shows up right away instead of waiting for the next scheduled sync. */
    private fun recordEvent(kind: SimulatedSleepEventKind) {
        scope.launch {
            // T4: virtual - this becomes a SleepSegment boundary the engine plans against (BandDataSimulator.kt).
            val updated = appendSimulatedSleepEvent(simulatedSleepEvents.value, kind, nowInstant())
            writeSimulatedSleepEvents(context, updated)
            requestImmediateTick(context)
        }
    }

    /** V6: routes through [updateDebugOptions], which applies [transform] to the value DataStore's own `edit {}` reads AT WRITE TIME - never the possibly-stale [storedOptions] snapshot this method used to close over - so two calls launched close together can never lose one's change to the other's. */
    private fun persistOptions(transform: (DebugOptions) -> DebugOptions) {
        scope.launch { updateDebugOptions(context, Instant.now(), transform) }
    }
}
