package com.nikita.sleepcycle.ui

// File purpose: the app's one ViewModel. Exposes a single immutable UiState, forwards every user action to the
// night/settings layer, and owns the small amount of state that is only for the UI (which screen is showing,
// the 30 s ticker, the connection-test spinner, the end-night confirmation). The Debug screen's own state and
// actions live in DebugScreenController.kt, delegated to here, so this already-large file does not grow by
// one property/method per Debug screen feature.

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nikita.sleepcycle.bridge.isGadgetbridgeInstalled
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.night.AppSettings
import com.nikita.sleepcycle.night.BandAlarmCommitment
import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.NightEngineView
import com.nikita.sleepcycle.night.NightState
import com.nikita.sleepcycle.night.SimulatedSleepEvent
import com.nikita.sleepcycle.night.buildNightEngineView
import com.nikita.sleepcycle.night.clearMorningReport
import com.nikita.sleepcycle.night.deleteNightLog as deleteNightLogFile
import com.nikita.sleepcycle.night.endNight as endNightTracking
import com.nikita.sleepcycle.night.listNightLogs
import com.nikita.sleepcycle.night.loadMorningReport
import com.nikita.sleepcycle.night.loadNightState
import com.nikita.sleepcycle.night.observedNightState
import com.nikita.sleepcycle.night.publishNightState
import com.nikita.sleepcycle.night.readAppSettings
import com.nikita.sleepcycle.night.readPastNightLog
import com.nikita.sleepcycle.night.refreshNightStateFromDisk
import com.nikita.sleepcycle.night.requestImmediateTick
import com.nikita.sleepcycle.night.runSetupCheck
import com.nikita.sleepcycle.night.SetupCheckLine
import com.nikita.sleepcycle.night.SetupCheckLineSeverity
import com.nikita.sleepcycle.night.SetupCheckReport
import com.nikita.sleepcycle.night.startNight as startNightTracking
import com.nikita.sleepcycle.night.withDeviceMac
import com.nikita.sleepcycle.night.withExportUri
import com.nikita.sleepcycle.night.writeAppSettings
import com.nikita.sleepcycle.ui.permissions.currentPermissionStatus
import com.nikita.sleepcycle.ui.screens.setup.SetupWizardPage
import com.nikita.sleepcycle.ui.screens.setup.firstUnsatisfiedSetupWizardPage
import com.nikita.sleepcycle.ui.screens.setup.nextSetupWizardPage
import com.nikita.sleepcycle.ui.screens.setup.previousSetupWizardPage
import com.nikita.sleepcycle.ui.state.ConnectionTestState
import com.nikita.sleepcycle.ui.state.EndNightFlowState
import com.nikita.sleepcycle.ui.state.NightLogSummary
import com.nikita.sleepcycle.ui.state.PastNightUiState
import com.nikita.sleepcycle.ui.state.PermissionStatus
import com.nikita.sleepcycle.ui.state.Screen
import com.nikita.sleepcycle.ui.state.UiState
import com.nikita.sleepcycle.ui.state.buildPastNightUiState
import com.nikita.sleepcycle.ui.state.buildSetupUiState
import com.nikita.sleepcycle.ui.state.buildUiState
import com.nikita.sleepcycle.ui.state.canConfirmEndNight
import com.nikita.sleepcycle.ui.state.canRequestEndNight
import com.nikita.sleepcycle.ui.state.deadlineInstantFor
import com.nikita.sleepcycle.ui.state.isSetupComplete
import com.nikita.sleepcycle.ui.state.resolvePickedCycles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

private const val TICKER_INTERVAL_MS = 30_000L

private data class CoreInputs(
    val appSettings: AppSettings?,
    val nightState: NightState?,
    val now: Instant,
    val screen: Screen,
    val gadgetbridgeInstalled: Boolean,
)

private data class ExtraInputs(
    val permissionStatus: PermissionStatus,
    val connectionTest: ConnectionTestState,
    val nightLogFiles: List<File>,
    val confirmingEndNight: Boolean,
    val endingNight: Boolean,
)

/**
 * The morning report is rendered after the night state has been cleared, so everything it needs is captured
 * here first: the engine view, plus the band alarm the band is STILL armed with, which no Gadgetbridge intent
 * can disarm (see NightController.logLeftoverBandAlarm).
 */
private data class CachedMorningReport(
    val engineView: NightEngineView,
    val bandAlarmLeftover: BandAlarmCommitment?,
)

private data class ReportInputs(
    val showingMorningReport: Boolean,
    val morningReportEndedAt: Instant?,
    val cachedMorningReport: CachedMorningReport?,
)

private data class DebugInputs(
    val storedDebugOptions: DebugOptions,
    val simulatedSleepEvents: List<SimulatedSleepEvent>,
    val confirmingDebugNightStart: Boolean,
)

class NightViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    // Read fresh on every use (a function, not a cached property) so a timezone change mid-session takes
    // effect immediately rather than only after the process restarts (D4).
    private fun currentZone(): ZoneId = ZoneId.systemDefault()

    private val screen = MutableStateFlow<Screen>(Screen.Setup)
    // The Setup screen's wizard page: null shows the one-page checklist, non-null shows that page of the
    // page-by-page wizard. Kept separate from `screen` (rather than folded into UiState/Screen) so the wizard
    // stays entirely additive - see resolveInitialScreen, openSetup, runSetupWizardAgain and closeSetupOrLogs
    // for where it changes. Surviving rotation and backgrounding falls out for free: like `screen` itself,
    // this only needs to outlive the ViewModel, not the process.
    private val setupWizardPageState = MutableStateFlow<SetupWizardPage?>(null)
    private val now = MutableStateFlow(Instant.now())
    private val screenVisible = MutableStateFlow(false)
    private val permissionStatus = MutableStateFlow(PermissionStatus.unknown())
    private val gadgetbridgeInstalled = MutableStateFlow(false)
    private val connectionTest = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    private val nightLogFiles = MutableStateFlow<List<File>>(emptyList())
    private val openedPastNight = MutableStateFlow<PastNightUiState?>(null)
    private val confirmingEndNight = MutableStateFlow(false)
    /** Item 1: true from the moment "confirm" is tapped until endNight's result is rendered - see ui/state/EndNightFlowState.kt. */
    private val endingNight = MutableStateFlow(false)
    private val showingMorningReport = MutableStateFlow(false)
    private val morningReportEndedAt = MutableStateFlow<Instant?>(null)
    private val cachedMorningReport = MutableStateFlow<CachedMorningReport?>(null)
    private val errorMessage = MutableStateFlow<String?>(null)
    private val debug = DebugScreenController(context, viewModelScope)

    private val appSettings: StateFlow<AppSettings?> =
        readAppSettings(context).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Which Setup wizard page (if any) is showing; null means the one-page checklist. See [setupWizardPageState]. */
    val setupWizardPage: StateFlow<SetupWizardPage?> = setupWizardPageState.asStateFlow()

    /** The saved night currently reopened from the Logs list, or null when none is. Kept out of UiState for the same reason as [setupWizardPageState]: it is additive, and it only needs to outlive the ViewModel. */
    val pastNight: StateFlow<PastNightUiState?> = openedPastNight.asStateFlow()

    val uiState: StateFlow<UiState> = combine(
        combine(appSettings, observedNightState, now, screen, gadgetbridgeInstalled, ::CoreInputs),
        combine(permissionStatus, connectionTest, nightLogFiles, confirmingEndNight, endingNight, ::ExtraInputs),
        combine(showingMorningReport, morningReportEndedAt, cachedMorningReport, ::ReportInputs),
        combine(debug.storedOptions, debug.simulatedSleepEvents, debug.confirmingNightStart, ::DebugInputs),
        errorMessage,
    ) { core, extra, report, debugInputs, error -> toUiState(core, extra, report, debugInputs, error) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(TICKER_INTERVAL_MS), UiState.initial())

    init {
        viewModelScope.launch { resolveInitialScreen() }
        watchScreenVisibilityTicker()
        watchSleepLengthFallback()
    }

    private fun toUiState(core: CoreInputs, extra: ExtraInputs, report: ReportInputs, debugInputs: DebugInputs, error: String?): UiState {
        val settings = core.appSettings ?: return UiState.initial()
        val engineView = when {
            report.showingMorningReport -> report.cachedMorningReport?.engineView
            core.nightState != null -> buildNightEngineView(core.nightState, core.now)
            else -> null
        }
        return buildUiState(
            appSettings = settings,
            nightState = core.nightState,
            engineView = engineView,
            now = core.now,
            zone = currentZone(),
            permissionStatus = extra.permissionStatus,
            gadgetbridgeInstalled = core.gadgetbridgeInstalled,
            screen = core.screen,
            connectionTest = extra.connectionTest,
            nightLogFiles = extra.nightLogFiles,
            confirmingEndNight = extra.confirmingEndNight,
            endingNight = extra.endingNight,
            showingMorningReport = report.showingMorningReport,
            morningReportEndedAt = report.morningReportEndedAt,
            morningReportBandAlarmLeftover = report.cachedMorningReport?.bandAlarmLeftover,
            debugOptions = debug.effectiveOptions(),
            simulatedSleepEvents = debugInputs.simulatedSleepEvents,
            confirmingDebugNightStart = debugInputs.confirmingDebugNightStart,
            errorMessage = error,
        )
    }

    /**
     * Loads the night state from disk synchronously as part of this same suspend sequence - not via the
     * fire-and-forget [refreshNightStateFromDisk] - so reopening the app mid-night reliably lands on the
     * Night screen instead of racing an async load that might not have completed yet (D2).
     */
    private suspend fun resolveInitialScreen() {
        refreshStatuses()
        val loaded = readAppSettings(context).first()
        val effective = loaded
        val nightStateFromDisk = withContext(Dispatchers.IO) { loadNightState(context) }
        if (nightStateFromDisk != null) publishNightState(nightStateFromDisk)
        restoreMorningReportFromDiskIfAny(nightStateFromDisk)
        screen.value = when {
            nightStateFromDisk != null -> Screen.Night
            showingMorningReport.value -> Screen.Night
            !isSetupComplete(effective, permissionStatus.value, gadgetbridgeInstalled.value) -> Screen.Setup
            else -> Screen.BeforeBed
        }
        // A fresh install (or any launch that still lands on Setup) opens the page-by-page wizard, on
        // whichever page is first unsatisfied - never the one-page checklist (task spec: "the wizard is what
        // a fresh install opens into").
        if (screen.value == Screen.Setup) {
            val setupState = buildSetupUiState(effective, permissionStatus.value, gadgetbridgeInstalled.value, connectionTest.value)
            setupWizardPageState.value = firstUnsatisfiedSetupWizardPage(setupState)
        }
    }

    /**
     * Recovers a morning report saved by `endNight` (see MorningReportStore.kt), so it survives leaving and
     * reopening the app once - including a process death - before "Done" is tapped. Only relevant when there
     * is no active night: `endNight` always clears the live night state before this runs.
     */
    private suspend fun restoreMorningReportFromDiskIfAny(nightStateFromDisk: NightState?) {
        if (nightStateFromDisk != null) return
        val saved = withContext(Dispatchers.IO) { loadMorningReport(context) } ?: return
        cachedMorningReport.value = CachedMorningReport(buildNightEngineView(saved.nightState, saved.endedAt), saved.nightState.lastBandAlarmSet)
        morningReportEndedAt.value = saved.endedAt
        showingMorningReport.value = true
    }

    /** Ticks [now] every 30 s while a screen is visible; pauses entirely while the app is backgrounded. */
    private fun watchScreenVisibilityTicker() {
        viewModelScope.launch {
            screenVisible.collectLatest { visible ->
                if (!visible) return@collectLatest
                while (true) {
                    now.value = Instant.now()
                    delay(TICKER_INTERVAL_MS)
                }
            }
        }
    }

    /** If the picked sleep length stops fitting before the deadline, persists a fall back to the longest one that still does. */
    private fun watchSleepLengthFallback() {
        viewModelScope.launch {
            combine(appSettings.filterNotNull(), now) { settings, currentNow -> settings to currentNow }.collectLatest { (settings, currentNow) ->
                val deadline = deadlineInstantFor(settings, currentNow, currentZone())
                val resolved = resolvePickedCycles(settings.pickedCycles, currentNow, deadline, debug.effectiveOptions())
                if (resolved != settings.pickedCycles) writeAppSettings(context, settings.copy(pickedCycles = resolved))
            }
        }
    }

    private fun refreshStatuses() {
        permissionStatus.value = currentPermissionStatus(context)
        gadgetbridgeInstalled.value = isGadgetbridgeInstalled(context)
    }

    /** Called from the Activity on every resume: re-checks permissions/Gadgetbridge, re-syncs if the night screen is showing, and resets idle debug switches (A1) - this is the app's own definition of "opened". */
    fun onResumed() {
        refreshNightStateFromDisk(context)
        refreshStatuses()
        debug.resetIfIdle(Instant.now())
        if (screen.value == Screen.Night) requestImmediateTick(context)
        if (screen.value == Screen.Logs) nightLogFiles.value = listNightLogs(context)
    }

    fun setScreenVisible(visible: Boolean) {
        screenVisible.value = visible
    }

    /** Dismisses the current plain-English error banner (D3), e.g. after the owner taps it. */
    fun clearErrorMessage() {
        errorMessage.value = null
    }

    // Navigation.
    /** The gear icon: always opens the one-page checklist, never the wizard - re-checks stay quick. */
    fun openSetup() { screen.value = Screen.Setup; setupWizardPageState.value = null }
    fun openLogs() { screen.value = Screen.Logs; nightLogFiles.value = listNightLogs(context) }

    /** Reopens one saved night as its own summary screen. Reading and parsing the log is disk I/O, so it happens off the main thread before the screen switches. */
    fun openNightLog(log: NightLogSummary) {
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.IO) { readPastNightLog(log.file) }
            openedPastNight.value = buildPastNightUiState(log, parsed, currentZone())
            screen.value = Screen.PastNight
        }
    }

    fun closePastNight() { openedPastNight.value = null; screen.value = Screen.Logs }

    /** Deletes one saved night log and re-lists what is left. Called only after the Logs screen's own confirmation. */
    fun deleteNightLog(log: NightLogSummary) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { deleteNightLogFile(log.file) }
            nightLogFiles.value = listNightLogs(context)
        }
    }

    fun closeSetupOrLogs() {
        screen.value = if (observedNightState.value != null) Screen.Night else Screen.BeforeBed
        setupWizardPageState.value = null
    }
    /** Reachable from Setup AND from the Night screen (a debug build shows a Debug icon there too) - the simulator's buttons need to work while a simulated night is actually running, not just before it starts. */
    fun openDebug() { screen.value = Screen.Debug }
    fun closeDebug() { screen.value = if (observedNightState.value != null) Screen.Night else Screen.Setup }

    // Setup wizard navigation. The wizard is entered either by resolveInitialScreen (fresh install) or by
    // runSetupWizardAgain (the checklist's "Run setup again"); openSetup/closeSetupOrLogs above always clear
    // it back to null so a later gear-icon open defaults to the checklist.
    /** The checklist's "Run setup again": re-enters the wizard on the first unsatisfied page. */
    fun runSetupWizardAgain() { setupWizardPageState.value = firstUnsatisfiedSetupWizardPage(uiState.value.setup) }
    fun setupWizardNext() {
        val current = setupWizardPageState.value ?: return
        setupWizardPageState.value = nextSetupWizardPage(current, uiState.value.setup)
    }
    fun setupWizardBack() {
        val current = setupWizardPageState.value ?: return
        setupWizardPageState.value = previousSetupWizardPage(current)
    }

    // Setup screen actions.
    fun setDeviceMac(mac: String) = persistSettings { withDeviceMac(it, mac) }

    /** Off the main thread: this is a binder call into the system content resolver, and it can fail (a revoked/invalid uri) - a failure becomes a plain-English message rather than a crash. */
    fun setExportUri(uri: Uri) {
        viewModelScope.launch {
            val granted = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                true
            } catch (error: Exception) {
                errorMessage.value = "Could not use that file: ${error.message ?: error::class.simpleName}"
                false
            }
            if (granted) persistSettings { withExportUri(it, uri) }
        }
    }

    /** Runs the full setup check and, per its result, sets or clears [AppSettings.lastSetupCheckPassedAt] and the usable-slot count it found (see SetupCompleteness.kt for how the two gate "Start night"). */
    fun runSetupCheckAction() {
        val settings = appSettings.value ?: return
        val mac = settings.deviceMac
        val uri = settings.exportUri
        if (mac.isNullOrBlank() || uri == null) return
        viewModelScope.launch {
            connectionTest.value = ConnectionTestState.Running
            val now = Instant.now()
            val report = try {
                runSetupCheck(context, mac, uri, now)
            } catch (error: Exception) {
                setupCheckFailureReport(error)
            }
            connectionTest.value = ConnectionTestState.Done(report)
            persistSettings {
                it.copy(
                    lastSetupCheckPassedAt = if (report.isReady) now else null,
                    // Only a passing check's slot count means anything; a failed one leaves nothing known
                    // about the band, which the Before-bed gate reads as "do not judge" (see
                    // singleSlotNightNeedsPhoneAlarm) - the stale-check gate blocks that night anyway.
                    lastSetupCheckUsableBandAlarmSlots = if (report.isReady) report.usableBandAlarmSlots else null
                )
            }
        }
    }

    // Before-bed screen actions.
    fun setDeadlineEnabled(enabled: Boolean) = persistSettings { it.copy(deadlineEnabled = enabled) }
    fun setDeadlineTime(time: LocalTime) = persistSettings { it.copy(lastDeadline = time) }
    fun setPickedCycles(cycles: Int) = persistSettings { it.copy(pickedCycles = cycles) }
    fun setPhoneBackupEnabled(enabled: Boolean) = persistSettings { it.copy(phoneBackupEnabled = enabled) }

    /** What "Start night" actually calls: if any debug option is on, this is a simulated night, so it asks for confirmation first rather than starting straight away (task spec: "asks for confirmation so it cannot happen by accident at real bedtime"). */
    fun requestStartNight() {
        if (debug.effectiveOptions().isAnyEnabled) debug.requestNightStartConfirmation() else beginNight()
    }

    fun confirmDebugNightStart() {
        debug.clearNightStartConfirmation()
        beginNight()
    }

    fun cancelDebugNightStart() = debug.clearNightStartConfirmation()

    private fun beginNight() {
        val settings = appSettings.value ?: return
        val debugOptions = debug.effectiveOptions()
        val startedAt = Instant.now()
        val deadline = deadlineInstantFor(settings, startedAt, currentZone())
        val cycles = resolvePickedCycles(settings.pickedCycles, startedAt, deadline, debugOptions)
        val nightSettings = NightSettings(
            deadline = deadline,
            pickedCycles = cycles,
            phoneBackupEnabled = settings.phoneBackupEnabled && !settings.deadlineEnabled,
        )
        // startNightTracking already arms the initial plan and triggers the first real tick as one ordered
        // sequence (see NightController.startNight); requesting a second immediate tick here would race it
        // and could let a stale dismissal from that sequence erase the tick's own work (Opus review 3.2).
        startNightTracking(context, nightSettings, startedAt, debugOptions)
        showingMorningReport.value = false
        cachedMorningReport.value = null
        screen.value = Screen.Night
    }

    // Night screen actions.
    /** Item 1: ignored while the dialog is already open or a previous confirm is still ending the night - the confirmation dialog must never be re-openable mid-end. */
    fun requestEndNight() {
        if (!canRequestEndNight(EndNightFlowState(confirmingEndNight.value, endingNight.value))) return
        confirmingEndNight.value = true
    }
    fun cancelEndNight() { confirmingEndNight.value = false }

    /**
     * Renders the report endNight returns from inside its own lock (D1), not a snapshot taken here beforehand -
     * a tick could commit a newer state between that snapshot and endNight's own read. Item 1: [endingNight]
     * disables the button and shows "Ending night..." for the whole ~3 s endNight takes, and a second confirm
     * while it is still running is ignored - `endNightTracking` (NightController.endNight) is itself idempotent
     * too, so even a call that slipped past this guard would not repeat the work.
     */
    fun confirmEndNight() {
        if (!canConfirmEndNight(EndNightFlowState(confirmingEndNight.value, endingNight.value))) return
        confirmingEndNight.value = false
        endingNight.value = true
        viewModelScope.launch {
            val report = endNightTracking(context, Instant.now())
            if (report != null) {
                cachedMorningReport.value = CachedMorningReport(buildNightEngineView(report.nightState, report.endedAt), report.nightState.lastBandAlarmSet)
                morningReportEndedAt.value = report.endedAt
            }
            showingMorningReport.value = true
            endingNight.value = false
        }
    }

    /** Dismisses the morning report and clears its disk snapshot: once seen, it should not resurface on a later app reopen. */
    fun finishMorningReport() {
        showingMorningReport.value = false
        cachedMorningReport.value = null
        morningReportEndedAt.value = null
        endingNight.value = false
        screen.value = Screen.BeforeBed
        viewModelScope.launch(Dispatchers.IO) { clearMorningReport(context) }
    }

    private fun persistSettings(transform: (AppSettings) -> AppSettings) {
        val current = appSettings.value ?: return
        viewModelScope.launch { writeAppSettings(context, transform(current)) }
    }

    // Debug screen actions: thin delegates to DebugScreenController.kt, which owns the actual state.
    fun setSimulatedBandData(enabled: Boolean) = debug.setSimulatedBandData(enabled)
    fun setFastNight(enabled: Boolean) = debug.setFastNight(enabled)
    fun setDryRunBandCommands(enabled: Boolean) = debug.setDryRunBandCommands(enabled)
    fun fellAsleepNow() = debug.fellAsleepNow()
    fun wokeUpNow() = debug.wokeUpNow()
    fun fellBackAsleepNow() = debug.fellBackAsleepNow()
    fun clearSimulatedSleep() = debug.clearSimulatedSleep()
    fun ringDebugTestAlarm() = debug.ringTestAlarm()
}

/** A setup check that threw instead of returning a result, folded into the same report shape the UI already renders. */
private fun setupCheckFailureReport(error: Exception): SetupCheckReport = SetupCheckReport(
    isReady = false,
    lines = listOf(SetupCheckLine("Setup check failed: ${error.message ?: error::class.simpleName}", SetupCheckLineSeverity.ACTION_NEEDED)),
    freeBandAlarmSlots = 0,
    otherEnabledBandAlarms = emptyList(),
)
