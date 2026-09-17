package com.nikita.sleepcycle.ui

// File purpose: owns the Debug screen's persisted state (the three switches, the simulated sleep timeline)
// and its actions, kept out of NightViewModel.kt so that already-large file does not grow by one
// property/method per Debug screen feature. Not a ViewModel itself: constructed and driven by
// NightViewModel, the one place with a CoroutineScope and an Android Context to hand it.

import android.content.Context
import com.nikita.sleepcycle.BuildConfig
import com.nikita.sleepcycle.night.BandCommandMode
import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.SimulatedSleepEvent
import com.nikita.sleepcycle.night.SimulatedSleepEventKind
import com.nikita.sleepcycle.night.appendSimulatedSleepEvent
import com.nikita.sleepcycle.night.clearedSimulatedSleepEvents
import com.nikita.sleepcycle.night.isDebugTestAlarmAllowed
import com.nikita.sleepcycle.night.observedNightState
import com.nikita.sleepcycle.night.readDebugOptions
import com.nikita.sleepcycle.night.readDebugOptionsLastChangedAt
import com.nikita.sleepcycle.night.readSimulatedSleepEvents
import com.nikita.sleepcycle.night.requestImmediateTick
import com.nikita.sleepcycle.night.resolveDebugOptions
import com.nikita.sleepcycle.night.scheduleDebugTestAlarm
import com.nikita.sleepcycle.night.shouldAutoResetDebugOptions
import com.nikita.sleepcycle.night.writeDebugOptions
import com.nikita.sleepcycle.night.writeSimulatedSleepEvents
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

    fun setSimulatedBandData(enabled: Boolean) = persistOptions { it.copy(simulatedBandData = enabled) }
    fun setFastNight(enabled: Boolean) = persistOptions { it.copy(fastNight = enabled) }
    fun setDryRunBandCommands(enabled: Boolean) = persistOptions {
        it.copy(bandCommandMode = if (enabled) BandCommandMode.DRY_RUN else BandCommandMode.SEND_TO_BAND)
    }

    fun fellAsleepNow() = recordEvent(SimulatedSleepEventKind.FELL_ASLEEP)
    fun wokeUpNow() = recordEvent(SimulatedSleepEventKind.WOKE_UP)
    fun fellBackAsleepNow() = recordEvent(SimulatedSleepEventKind.FELL_BACK_ASLEEP)

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

    /** A1: called on every app open/resume (NightViewModel.onResumed) - resets every switch to off once [shouldAutoResetDebugOptions] says the idle window has passed, so a desk test left on in the afternoon cannot leak into bedtime. */
    fun resetIfIdle(now: Instant) {
        if (storedOptions.value.isAnyEnabled && shouldAutoResetDebugOptions(lastChangedAt.value, now)) {
            scope.launch { writeDebugOptions(context, DebugOptions(), now) }
        }
    }

    /** Appends a simulated sleep event and, per the task spec, requests an immediate tick so the effect shows up right away instead of waiting for the next scheduled sync. */
    private fun recordEvent(kind: SimulatedSleepEventKind) {
        scope.launch {
            val updated = appendSimulatedSleepEvent(simulatedSleepEvents.value, kind, Instant.now())
            writeSimulatedSleepEvents(context, updated)
            requestImmediateTick(context)
        }
    }

    private fun persistOptions(transform: (DebugOptions) -> DebugOptions) {
        scope.launch { writeDebugOptions(context, transform(storedOptions.value), Instant.now()) }
    }
}
