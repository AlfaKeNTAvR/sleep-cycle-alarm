package com.nikita.sleepcycle.ui

// File purpose: owns the Debug screen's persisted state (the two switches, the clock warp, the simulated
// sleep timeline) and its actions, kept out of NightViewModel.kt so that already-large file does not grow by
// one property/method per Debug screen feature. Not a ViewModel itself: constructed and driven by
// NightViewModel, the one place with a CoroutineScope and an Android Context to hand it.
//
// U1/U2: every action that changes the clock's speed routes through [normalizedWarp] (the one rule for when a
// warp exists at all, via computeSpeedChangeWarp), so U5's own instruction ("the same function, not a
// parallel copy") holds for real, not just by convention. Owner spec, 2026-10-02: the jump to a set time, "Reset
// to real time" and the Clear button are gone - a night's end resets the clock and empties the simulated sleep
// (resetDebugOptionsAndClock) - and the speed chips are 1x, 60x and Auto (AutoSimulationSpeed.kt).
//
// V4: every warp mutation is guarded against a running night - see [isSimulatedBandDataToggleAllowed]'s own
// doc for why turning simulated band data off mid-night is unsafe, and [resetIfIdle]'s own doc for why the
// idle auto-reset is skipped outright instead. V5: [setSpeedChoice] is the one warp mutation that STAYS legal
// mid-night - see its own doc.

import android.content.Context
import com.nikita.sleepcycle.BuildConfig
import com.nikita.sleepcycle.night.AfterAlarmSettings
import com.nikita.sleepcycle.night.AppClock
import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.SIMULATED_AWAKE_RETICK_MARGIN
import com.nikita.sleepcycle.night.SimulatedSleepEvent
import com.nikita.sleepcycle.night.SimulatedSleepEventKind
import com.nikita.sleepcycle.night.SpeedChoice
import com.nikita.sleepcycle.night.applySimulationSpeed
import com.nikita.sleepcycle.night.autoClockSpeed
import com.nikita.sleepcycle.night.chooseSimulationSpeed
import com.nikita.sleepcycle.night.dropSimulationToRealSpeed
import com.nikita.sleepcycle.night.readPendingFollowUp
import com.nikita.sleepcycle.night.readSimulationSpeed
import com.nikita.sleepcycle.night.requestImmediateTick
import com.nikita.sleepcycle.night.appendSimulatedSleepEvent
import com.nikita.sleepcycle.night.isDebugTestAlarmAllowed
import com.nikita.sleepcycle.night.isSimulatedBandDataToggleAllowed
import com.nikita.sleepcycle.night.isSimulatedSleepControlAllowed
import com.nikita.sleepcycle.night.isSpeedSelectorAllowed
import com.nikita.sleepcycle.night.nowInstant
import com.nikita.sleepcycle.night.observedNightState
import com.nikita.sleepcycle.night.readDebugOptions
import com.nikita.sleepcycle.night.readDebugOptionsLastChangedAt
import com.nikita.sleepcycle.night.readSimulatedSleepEvents
import com.nikita.sleepcycle.night.rearmAfterSpeedChange
import com.nikita.sleepcycle.night.runImmediateTick
import com.nikita.sleepcycle.night.resolveEngineConfig
import com.nikita.sleepcycle.night.scheduleTick
import com.nikita.sleepcycle.night.resetDebugOptionsAndClock
import com.nikita.sleepcycle.night.resolveDebugOptions
import com.nikita.sleepcycle.night.scheduleDebugTestAlarm
import com.nikita.sleepcycle.night.shouldAutoResetDebugOptions
import com.nikita.sleepcycle.night.updateDebugOptions
import com.nikita.sleepcycle.night.updateSimulatedSleepEvents
import com.nikita.sleepcycle.night.writeClockWarp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant


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
     * U1: turning simulated band data off clears any active clock warp too - a warp and real band data must
     * never be live at once (see this file's own header). Turning it on is unconditional - the speed chips only
     * unlock afterwards.
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
        //
        // FIX3: appends through [updateSimulatedSleepEvents] rather than reading `simulatedSleepEvents.value`
        // (a StateFlow snapshot that can lag a just-committed write) and writing the result back unconditionally
        // - see [updateSimulatedSleepEvents]'s own doc for the class of bug this avoids.
        scope.launch {
            val at = nowInstant()
            updateSimulatedSleepEvents(context) { stored -> appendSimulatedSleepEvent(stored, SimulatedSleepEventKind.AWAKE, at) }
            writeClockWarp(context, null)
            updateDebugOptions(context, Instant.now()) { it.copy(simulatedBandData = false, autoSpeed = false) }
            requestImmediateTick(context)
        }
    }

    /**
     * T7/U1/U2/V5, owner spec 2026-10-02: the Night screen's speed chips (1x, 60x, Auto). Refused (a no-op)
     * while simulated band data is off - the chips are also disabled then (U1), this is the second guard.
     * Re-anchors at the CURRENT virtual instant ([chooseSimulationSpeed]), so changing speed never itself jumps
     * the clock - only the rate it moves at from here on. Auto starts at the speed it wants for where the night
     * is now (600x, or 60x within 10 simulated minutes of the next alarm); the night tick keeps it there.
     *
     * V5: legal mid-night, so every real instant armed under the OLD mapping is re-armed: the pending nudge or
     * own nap here, and the wake alarm and next tick by the immediate tick [rearmAfterSpeedChange] asks for.
     */
    fun setSpeedChoice(choice: SpeedChoice) {
        if (!isSpeedSelectorAllowed(storedOptions.value.simulatedBandData)) return
        scope.launch {
            val now = nowInstant()
            val autoSpeedNow = autoClockSpeed(now, observedNightState.value?.lastPlan?.wakeAt, readPendingFollowUp(context)?.at)
            applySimulationSpeed(context, chooseSimulationSpeed(readSimulationSpeed(context), choice, Instant.now(), autoSpeedNow))
            rearmAfterSpeedChange(context)
        }
    }

    /**
     * T9: sets the simulated sleep state directly, replacing the old three-button fellAsleepNow/wokeUpNow/
     * fellBackAsleepNow with one toggle. T10: refused (a no-op) while simulated band data is off - the Debug
     * screen also disables the toggle for that case, this is the second guard. Owner spec, 2026-10-02: either
     * direction also drops the clock to 1x (see [dropToRealSpeed]).
     */
    fun setSimulatedAsleep(asleep: Boolean) {
        if (!isSimulatedSleepControlAllowed(storedOptions.value.simulatedBandData)) return
        recordEvent(if (asleep) SimulatedSleepEventKind.ASLEEP else SimulatedSleepEventKind.AWAKE)
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

    /**
     * Appends a simulated sleep event and ticks immediately, so the effect shows up without waiting for the
     * next scheduled sync.
     *
     * W9: an immediate tick alone is not enough for an AWAKE mark, and this is why the owner saw falling
     * asleep register at once while waking up lagged. The engine ignores any awake mark shorter than
     * [EngineConfig.minAwakening] - a real band's brief stirring must not split a sleep stretch - so at the
     * instant of the tap that mark is zero long and is filtered out. Nothing then re-evaluates it until the
     * next scheduled sync, 5 to 15 minutes of the app's own clock later, which is where the wait came from.
     * So a second tick is booked for just past the debounce, when the mark first becomes significant. Falling
     * asleep has no equivalent floor, which is exactly why that direction always felt instant.
     *
     * W10: W9 alone did nothing, because the immediate tick was fired and not awaited. Every tick arms the
     * next one for itself, and tick scheduling is cancel-and-replace, so the tick still in flight landed after
     * the debounce re-tick below and replaced it with the ordinary cadence - which is exactly what the owner
     * saw: the switch only ever took effect on a sync line, and waiting a fixed number of SIMULATED minutes
     * meant the lag shrank with speed (about 10 real seconds at 60x, about 1 at 600x). Awaiting the tick puts
     * the debounce re-tick last, so it is the one that survives.
     *
     * FIX3 (owner-reported, 2026-09-21): appends through [updateSimulatedSleepEvents] rather than reading
     * `simulatedSleepEvents.value` (a StateFlow snapshot that can lag a just-committed write) and writing the
     * result back unconditionally - two quick taps of this same toggle used to be able to compute the second
     * tap's append against a snapshot still missing the first tap's own event, then write that stale snapshot
     * back and permanently erase it. See [updateSimulatedSleepEvents]'s own doc.
     */
    private fun recordEvent(kind: SimulatedSleepEventKind) {
        scope.launch {
            // Before the event is stamped, so the debounce re-tick below is booked under the 1x mapping; the
            // immediate tick right after re-arms the wake alarm and next tick under it too.
            dropSimulationToRealSpeed(context)
            // T4: virtual - this becomes a SleepSegment boundary the engine plans against (BandDataSimulator.kt).
            val at = nowInstant()
            updateSimulatedSleepEvents(context) { stored -> appendSimulatedSleepEvent(stored, kind, at) }
            runImmediateTick(context)
            if (kind == SimulatedSleepEventKind.AWAKE) {
                scheduleTick(context, at + resolveEngineConfig(effectiveOptions(), AfterAlarmSettings()).minAwakening + SIMULATED_AWAKE_RETICK_MARGIN)
            }
        }
    }

    /** V6: routes through [updateDebugOptions], which applies [transform] to the value DataStore's own `edit {}` reads AT WRITE TIME - never the possibly-stale [storedOptions] snapshot this method used to close over - so two calls launched close together can never lose one's change to the other's. */
    private fun persistOptions(transform: (DebugOptions) -> DebugOptions) {
        scope.launch { updateDebugOptions(context, Instant.now(), transform) }
    }
}
