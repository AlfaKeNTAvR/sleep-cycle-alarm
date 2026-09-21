package com.nikita.sleepcycle.ui.state

// File purpose: the one pure top-level function that derives the whole app's UiState. Every Android-only input
// (permission reads, Gadgetbridge presence, the log file listing) is resolved by the caller into plain data
// first, so this function only ever touches JVM types and can be unit tested without an emulator.

import com.nikita.sleepcycle.night.AppSettings
import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.NightEngineView
import com.nikita.sleepcycle.night.NightState
import com.nikita.sleepcycle.night.SimulatedSleepEvent
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Derives the whole app's [UiState] from settings, night state, the engine's view of it, and every UI-only
 * input. [debugOptions] must already have passed through [com.nikita.sleepcycle.night.resolveDebugOptions] -
 * this function does not itself know whether the build is debug or release. [pendingOutOfBedNudgeAt]: see
 * [buildNightUiState]'s own doc (round 2 of the 09/21 review, must-fix 1).
 */
fun buildUiState(
    appSettings: AppSettings,
    nightState: NightState?,
    engineView: NightEngineView?,
    now: Instant,
    zone: ZoneId,
    permissionStatus: PermissionStatus,
    gadgetbridgeInstalled: Boolean,
    screen: Screen,
    connectionTest: ConnectionTestState,
    nightLogFiles: List<File>,
    confirmingEndNight: Boolean,
    showingMorningReport: Boolean,
    morningReportEndedAt: Instant?,
    debugOptions: DebugOptions = DebugOptions(),
    simulatedSleepEvents: List<SimulatedSleepEvent> = emptyList(),
    confirmingDebugNightStart: Boolean = false,
    endingNight: Boolean = false,
    pendingOutOfBedNudgeAt: Instant? = null,
    errorMessage: String?,
): UiState {
    val nightActive = nightState != null
    return UiState(
        screen = screen,
        setup = buildSetupUiState(appSettings, permissionStatus, gadgetbridgeInstalled, connectionTest),
        beforeBed = buildBeforeBedUiState(
            appSettings, now, zone, gadgetbridgeInstalled, permissionStatus, nightActive, debugOptions, confirmingDebugNightStart
        ),
        night = buildNightUiState(nightState, engineView, now, zone, showingMorningReport, morningReportEndedAt, confirmingEndNight, endingNight, pendingOutOfBedNudgeAt),
        logs = buildLogsUiState(nightLogFiles, zone),
        debug = buildDebugUiState(debugOptions, simulatedSleepEvents, now, zone, nightActive),
        errorMessage = errorMessage,
    )
}
