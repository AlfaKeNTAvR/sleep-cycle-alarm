package com.nikita.sleepcycle.ui.state

// File purpose: pure derivation of the Before-bed screen's state - deadline, the "sleep up to" picker with its
// hatched/disabled options, and "Start night" gating.

import com.nikita.sleepcycle.night.AppSettings
import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.SetupCheckLineSeverity
import com.nikita.sleepcycle.night.ActiveDebugSwitch
import com.nikita.sleepcycle.night.activeDebugSwitches
import com.nikita.sleepcycle.night.sleepLengthCycleOptions
import com.nikita.sleepcycle.night.sleepLengthFor
import com.nikita.sleepcycle.night.sleepLengthIsAvailable
import com.nikita.sleepcycle.ui.format.formatClockTime
import com.nikita.sleepcycle.ui.format.formatSleepLengthLabel
import com.nikita.sleepcycle.ui.format.nextOccurrenceOfDeadline
import java.time.Duration
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
    connectionTest: ConnectionTestState,
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
    val bandCheck = bandCheckStatusFor(connectionTest)
    return BeforeBedUiState(
        bandStatus = bandStatusFor(bandReady, bandCheck, appSettings.lastSetupCheckPassedAt, now),
        bandCheckedAtLabel = appSettings.lastSetupCheckPassedAt?.let { formatClockTime(it, zone) },
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
        bandCheck = bandCheck,
    )
}

/** P2: a failed test's reason is its first action-needed line (the band line comes first in every report), falling back to its first line. */
private fun bandCheckStatusFor(connectionTest: ConnectionTestState): BandCheckStatus = when (connectionTest) {
    ConnectionTestState.Idle -> BandCheckStatus.None
    ConnectionTestState.Running -> BandCheckStatus.Checking
    is ConnectionTestState.Done -> if (connectionTest.report.isReady) {
        BandCheckStatus.None
    } else {
        val lines = connectionTest.report.lines
        val reasonLine = lines.firstOrNull { it.severity == SetupCheckLineSeverity.ACTION_NEEDED } ?: lines.firstOrNull()
        BandCheckStatus.Failed(reasonLine?.text.orEmpty())
    }
}

/**
 * P2: the corner status line - see [BandStatus]. [bandSetUp]: Gadgetbridge installed, band address and export file
 * chosen. [lastPassedAt] is REAL time like the setting it comes from; [now] is the screen's clock, re-read only
 * every 30 s, so a pass up to [SETUP_CHECK_FUTURE_TOLERANCE] ahead of it still counts (see that constant).
 */
internal fun bandStatusFor(bandSetUp: Boolean, bandCheck: BandCheckStatus, lastPassedAt: Instant?, now: Instant): BandStatus = when {
    !bandSetUp -> BandStatus.SETUP_INCOMPLETE
    bandCheck is BandCheckStatus.Failed -> BandStatus.NOT_RESPONDING
    bandCheck is BandCheckStatus.Checking -> BandStatus.CHECKING
    passedRecently(lastPassedAt, now) -> BandStatus.CONNECTED
    else -> BandStatus.NOT_CHECKED_RECENTLY
}

private fun passedRecently(lastPassedAt: Instant?, now: Instant): Boolean {
    if (lastPassedAt == null) return false
    val elapsed = Duration.between(lastPassedAt, now)
    return elapsed >= SETUP_CHECK_FUTURE_TOLERANCE.negated() && elapsed <= AUTO_CONNECTION_TEST_RECHECK_AFTER
}
