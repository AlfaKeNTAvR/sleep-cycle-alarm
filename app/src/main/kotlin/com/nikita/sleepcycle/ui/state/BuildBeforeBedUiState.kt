package com.nikita.sleepcycle.ui.state

// File purpose: pure derivation of the Before-bed screen's state - deadline, the "sleep up to" picker with its
// hatched/disabled options, and "Start night" gating.

import com.nikita.sleepcycle.night.AppSettings
import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.ActiveDebugSwitch
import com.nikita.sleepcycle.night.activeDebugSwitches
import com.nikita.sleepcycle.night.sleepLengthCycleOptions
import com.nikita.sleepcycle.night.sleepLengthFor
import com.nikita.sleepcycle.night.sleepLengthIsAvailable
import com.nikita.sleepcycle.ui.format.formatSleepLengthLabel
import com.nikita.sleepcycle.ui.format.nextOccurrenceOfDeadline
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Used only when the user has never picked a deadline time yet. */
val DEFAULT_DEADLINE_TIME: LocalTime = LocalTime.of(8, 0)

/** The deadline instant implied by the saved settings, or null when the deadline switch is off. */
fun deadlineInstantFor(appSettings: AppSettings, now: Instant, zone: ZoneId): Instant? {
    if (!appSettings.deadlineEnabled) return null
    val clockTime = appSettings.lastDeadline ?: DEFAULT_DEADLINE_TIME
    return nextOccurrenceOfDeadline(clockTime, now, zone)
}

/** The sleep length to actually use: [current] if it still fits before the deadline, else the longest one that does, else [current] unchanged. [debugOptions] picks the EngineConfig a fast debug night's cycle length is measured against (see [sleepLengthIsAvailable]). */
fun resolvePickedCycles(current: Int, now: Instant, deadline: Instant?, debugOptions: DebugOptions = DebugOptions()): Int {
    val available = sleepLengthCycleOptions.filter { sleepLengthIsAvailable(it, now, deadline, debugOptions) }
    return when {
        current in available -> current
        available.isNotEmpty() -> available.max()
        else -> current
    }
}

/**
 * Builds the Before-bed screen's state. [nightActive] disables "Start night" outright regardless of the
 * checklist - a night must be ended before another can begin (D2). [debugOptions] is the currently effective
 * debug options (already passed through [com.nikita.sleepcycle.night.resolveDebugOptions] by the caller):
 * it relaxes setup gating per [debugOptionsRelaxSetup], picks the fast-night EngineConfig the picker's
 * durations are measured against, and drives the amber fast-night banner. [confirmingDebugNightStart] is
 * whether the "this is a simulated night" confirmation is currently showing.
 */
fun buildBeforeBedUiState(
    appSettings: AppSettings,
    now: Instant,
    zone: ZoneId,
    gadgetbridgeInstalled: Boolean,
    permissionStatus: PermissionStatus,
    nightActive: Boolean,
    debugOptions: DebugOptions = DebugOptions(),
    confirmingDebugNightStart: Boolean = false,
): BeforeBedUiState {
    val deadline = deadlineInstantFor(appSettings, now, zone)
    val resolvedCycles = resolvePickedCycles(appSettings.pickedCycles, now, deadline, debugOptions)
    val bandReady = gadgetbridgeInstalled && !appSettings.deviceMac.isNullOrBlank() && appSettings.exportUri != null
    val sleepLengthOptions = sleepLengthCycleOptions.map { cycles ->
        SleepLengthOption(
            cycles = cycles,
            hoursLabel = formatSleepLengthLabel(sleepLengthFor(cycles, debugOptions)),
            selected = cycles == resolvedCycles,
            available = sleepLengthIsAvailable(cycles, now, deadline, debugOptions),
        )
    }
    val checklistComplete = isSetupComplete(appSettings, permissionStatus, gadgetbridgeInstalled, debugOptions)
    val gate = startNightGate(checklistComplete, appSettings.lastSetupCheckPassedAt, now, debugOptions)
    return BeforeBedUiState(
        bandReady = bandReady,
        deadlineEnabled = appSettings.deadlineEnabled,
        deadlineTime = appSettings.lastDeadline ?: DEFAULT_DEADLINE_TIME,
        sleepLengthOptions = sleepLengthOptions,
        startNightEnabled = gate.enabled && !nightActive,
        startNightBlocker = startNightBlocker(appSettings, permissionStatus, gadgetbridgeInstalled, debugOptions),
        startNightBlockedBySetupCheck = gate.blockedBySetupCheck,
        // W7: this screen's banner says only that the coming night is simulated. A live clock reading belongs
        // next to the controls that move it, which are on the night screen; here, where nothing can act on
        // it, it was noise.
        activeDebugSwitches = activeDebugSwitches(debugOptions).map { ActiveDebugSwitch.SIMULATED_SLEEP_DATA }.distinct(),
        simulatedTimeValue = null,
        confirmingDebugNightStart = confirmingDebugNightStart,
    )
}
