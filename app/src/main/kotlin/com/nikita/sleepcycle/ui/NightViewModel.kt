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
import com.nikita.sleepcycle.night.AppClock
import com.nikita.sleepcycle.night.UI_TICKER_INTERVAL_MS
import com.nikita.sleepcycle.night.nowInstant
import com.nikita.sleepcycle.night.uiTickerIntervalMillis
import com.nikita.sleepcycle.night.observedNightState
import com.nikita.sleepcycle.night.publishNightState
import com.nikita.sleepcycle.night.readAppSettings
import com.nikita.sleepcycle.night.readOutOfBedNudgePendingAt
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
import com.nikita.sleepcycle.ui.state.closeToNightOrBeforeBed
import com.nikita.sleepcycle.ui.state.closeToNightOrSettings
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

/** W1: the flow-sharing timeout keeps the app's ordinary half-minute cadence - it is about how long to keep collecting after the last subscriber leaves, not about how often the clock is read. */
private const val TICKER_INTERVAL_MS = UI_TICKER_INTERVAL_MS

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

/** The morning report is rendered after the night state has been cleared, so everything it needs is captured here first: the engine view. */
private data class CachedMorningReport(
    val engineView: NightEngineView,
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
    // stays entirely additive - see resolveInitialScreen, openSetup, runSetupWizardAgain, closeSetup and
    // exitSetupWizard for where it changes. Surviving rotation and backgrounding falls out for free: like
    // `screen` itself, this only needs to outlive the ViewModel, not the process.
    private val setupWizardPageState = MutableStateFlow<SetupWizardPage?>(null)
    // T4: virtual - drives every timer/countdown the whole UI shows.
    private val now = MutableStateFlow(nowInstant())
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
    /**
     * Round 3 of the 09/21 review, should-fix 2: the out-of-bed nudge's own pending fire instant
     * (`OutOfBedNudgeStore.kt`), re-read alongside [now] on the same ticker cadence (see
     * [watchScreenVisibilityTicker]) and once more in [refreshStatuses] on resume - it is armed by
     * `PhoneAlarmReceiver` in the background, outside any Flow this ViewModel already observes, so this is the
     * only way the Night screen finds out. Both reads go through [refreshPendingOutOfBedNudge], off the main
     * thread: unlike [currentPermissionStatus]/[isGadgetbridgeInstalled] (a PackageManager/AlarmManager query,
     * read only once per resume), this is a disk read (`File.exists()` + `readText()`,
     * `readOutOfBedNudgePendingAt`) on the 500 ms-floored ticker - at the debug screen's 600x speed that is two
     * blocking main-thread file reads a second, on every screen, not just the Night screen.
     */
    private val pendingOutOfBedNudgeAt = MutableStateFlow<Instant?>(null)
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
        // Bundled with errorMessage rather than added as a 6th top-level flow: kotlinx.coroutines' combine has
        // fixed-arity overloads only up to 5 flows.
        combine(errorMessage, pendingOutOfBedNudgeAt, ::Pair),
    ) { core, extra, report, debugInputs, errorAndNudge ->
        toUiState(core, extra, report, debugInputs, errorAndNudge.first, errorAndNudge.second)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(TICKER_INTERVAL_MS), UiState.initial())

    init {
        viewModelScope.launch { resolveInitialScreen() }
        watchScreenVisibilityTicker()
        watchSleepLengthFallback()
        watchNightStateClearedWhileViewing()
    }

    private fun toUiState(
        core: CoreInputs,
        extra: ExtraInputs,
        report: ReportInputs,
        debugInputs: DebugInputs,
        error: String?,
        pendingOutOfBedNudgeAt: Instant?,
    ): UiState {
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
            debugOptions = debug.effectiveOptions(),
            simulatedSleepEvents = debugInputs.simulatedSleepEvents,
            confirmingDebugNightStart = debugInputs.confirmingDebugNightStart,
            pendingOutOfBedNudgeAt = pendingOutOfBedNudgeAt,
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
        cachedMorningReport.value = CachedMorningReport(buildNightEngineView(saved.nightState, saved.endedAt))
        morningReportEndedAt.value = saved.endedAt
        showingMorningReport.value = true
    }

    /**
     * H4: mirrors [confirmEndNight]'s own disk fallback for the case where the owner never tapped anything -
     * a FINISHED tick's own bookkeeping ([finishNightIfNeeded][com.nikita.sleepcycle.night.finishNightIfNeeded])
     * clears the live night state on its own, which used to leave an already-open Night screen showing a
     * blank engineView (nightState null, no cached report) until the app was reopened. `!endingNight.value`
     * skips the OWNER-initiated end path (Stop/"I'm up, end night" from this same screen): [confirmEndNight]
     * already handles that case itself, from the authoritative report `endNight` returns, not a disk re-read.
     */
    private fun watchNightStateClearedWhileViewing() {
        viewModelScope.launch {
            observedNightState.collectLatest { state ->
                if (state == null && screen.value == Screen.Night && !showingMorningReport.value && !endingNight.value) {
                    restoreMorningReportFromDiskIfAny(nightStateFromDisk = null)
                }
            }
        }
    }

    /**
     * Ticks [now] while a screen is visible; pauses entirely while the app is backgrounded.
     *
     * W1: the cadence follows the simulated clock's speed ([uiTickerIntervalMillis]) rather than being a flat
     * 30 s, and the whole loop restarts whenever the warp changes - so it re-samples immediately. Both halves
     * matter and both were wrong: tapping "Reset to real time" updated the speed chip at once (that comes
     * straight from DataStore) while the time readout kept showing the old simulated time for up to 30 real
     * seconds, which reads as the control not working; and while sped up, the readout stood still and then
     * jumped by however much simulated time those 30 seconds covered.
     *
     * W4: it watches [AppClock.currentWarp], the live clock, NOT the DataStore mirror of it. The mirror
     * changes only once the write reaches disk, so a restart driven by it could re-sample a clock that had
     * not been switched over yet, and then hold that stale reading until the next tick. Watching the holder
     * that [nowInstant] itself reads makes the restart and the value it samples the same event.
     */
    private fun watchScreenVisibilityTicker() {
        viewModelScope.launch {
            combine(screenVisible, AppClock.currentWarp) { visible, warp -> visible to warp }
                .collectLatest { (visible, warp) ->
                    if (!visible) return@collectLatest
                    val interval = uiTickerIntervalMillis(warp?.speed ?: 1)
                    while (true) {
                        now.value = nowInstant()
                        // Round 2 of the 09/21 review, must-fix 1: re-read alongside now so the nudge shows up
                        // (and disappears once cancelled/fired) on the same cadence as everything else on screen.
                        // Round 3, should-fix 2: off the main thread - see refreshPendingOutOfBedNudge's own doc.
                        refreshPendingOutOfBedNudge()
                        delay(interval)
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
        // Not read inline: unlike the two reads above, this one is disk I/O - see refreshPendingOutOfBedNudge's
        // own doc. resolveInitialScreen (the other caller of refreshStatuses) does not need to wait for it: the
        // screen it resolves does not depend on this value.
        viewModelScope.launch { refreshPendingOutOfBedNudge() }
    }

    /**
     * Round 3 of the 09/21 review, should-fix 2: [readOutOfBedNudgePendingAt] is a synchronous `File.exists()`
     * plus `readText()` (`OutOfBedNudgeStore.kt`), so it must never run on `viewModelScope`'s own
     * `Dispatchers.Main.immediate` - shared by both call sites ([watchScreenVisibilityTicker]'s ticker and
     * [refreshStatuses]'s resume) so there is exactly one place that reads this file off the main thread.
     */
    private suspend fun refreshPendingOutOfBedNudge() {
        pendingOutOfBedNudgeAt.value = withContext(Dispatchers.IO) { readOutOfBedNudgePendingAt(context) }
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
    /** X1: the gear icon on Before bed - opens the short Settings menu, not Setup directly. */
    fun openSettings() { screen.value = Screen.Settings }

    /** Settings' "Setup" row: always opens the one-page checklist, never the wizard - re-checks stay quick. */
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

    /** X5: Settings' own back arrow and its "Done" button both land here - Night if one is running behind Settings, otherwise Before bed. */
    fun closeSettings() { screen.value = closeToNightOrBeforeBed(observedNightState.value != null) }

    /** X2/X5: Setup's checklist-mode back arrow (and its own "Done", once everything is complete) - always Settings, since checklist mode is only ever reached from there. */
    fun closeSetup() { screen.value = Screen.Settings; setupWizardPageState.value = null }

    /**
     * X2: the wizard's own exit, unaffected by this rework - "goes where it goes today" per the task spec, NOT
     * through Settings. Distinct from [closeSetup] because the wizard is entered from resolveInitialScreen
     * (fresh install, before Settings is reachable at all) as well as from the checklist's "Run setup again".
     */
    fun exitSetupWizard() {
        screen.value = closeToNightOrBeforeBed(observedNightState.value != null)
        setupWizardPageState.value = null
    }

    fun closeLogs() { screen.value = closeToNightOrBeforeBed(observedNightState.value != null) }

    /** Reachable from Settings' Debug row AND from the Night screen (a debug build shows a Debug icon there too) - the simulator's buttons need to work while a simulated night is actually running, not just before it starts. */
    fun openDebug() { screen.value = Screen.Debug }

    /** X4/X5: back from Debug. See [closeToNightOrSettings] for why this is not simply "always Settings" - the night screen's own shortcut needs its way back too. */
    fun closeDebug() { screen.value = closeToNightOrSettings(observedNightState.value != null) }

    // Setup wizard navigation. The wizard is entered either by resolveInitialScreen (fresh install) or by
    // runSetupWizardAgain (the checklist's "Run setup again"); openSetup/closeSetup/exitSetupWizard above
    // always clear it back to null so a later Settings-menu open defaults to the checklist.
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
            // U4 SUPERSEDES the part 1 sweep: this feeds checkDataFreshness against a REAL band sample
            // timestamp (Gadgetbridge's own export), so it must stay real - added to T4's exception list
            // alongside BandDataSync/DebugSettingsStore's own idle guard/NightState's quarantine name/
            // DebugTestAlarm. Also feeds lastSetupCheckPassedAt, but that is safe too: startNightGate skips its
            // own recency comparison entirely whenever simulatedBandData is on (SetupCompleteness.kt), and U1
            // guarantees the clock can only be warped when simulatedBandData is on - so this real timestamp is
            // never compared against a virtual `now` anywhere it would matter.
            val now = Instant.now()
            val report = try {
                runSetupCheck(context, mac, uri, now)
            } catch (error: Exception) {
                setupCheckFailureReport(error)
            }
            connectionTest.value = ConnectionTestState.Done(report)
            persistSettings { it.copy(lastSetupCheckPassedAt = if (report.isReady) now else null) }
        }
    }

    // Before-bed screen actions.
    fun setDeadlineEnabled(enabled: Boolean) = persistSettings { it.copy(deadlineEnabled = enabled) }
    fun setDeadlineTime(time: LocalTime) = persistSettings { it.copy(lastDeadline = time) }
    fun setPickedCycles(cycles: Int) = persistSettings { it.copy(pickedCycles = cycles) }

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
        // T4: virtual - becomes NightState.startedAt.
        val startedAt = nowInstant()
        val deadline = deadlineInstantFor(settings, startedAt, currentZone())
        val cycles = resolvePickedCycles(settings.pickedCycles, startedAt, deadline, debugOptions)
        val nightSettings = NightSettings(
            deadline = deadline,
            pickedCycles = cycles,
        )
        // startNightTracking already arms the initial plan and triggers the first real tick as one ordered
        // sequence (see NightController.startNight); requesting a second immediate tick here would race it
        // and could let out-of-order work overwrite the tick's own (Opus review 3.2).
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
     *
     * H4: a null report means there was no live night left to end - a FINISHED tick's own bookkeeping
     * (finishNightIfNeeded) can beat this tap to it, clearing the state before the owner even taps the button.
     * That bookkeeping already saved a morning report to disk, so fall back to it instead of showing an empty
     * one - the same fallback [restoreMorningReportFromDiskIfAny] uses at app start.
     */
    fun confirmEndNight() {
        if (!canConfirmEndNight(EndNightFlowState(confirmingEndNight.value, endingNight.value))) return
        confirmingEndNight.value = false
        endingNight.value = true
        viewModelScope.launch {
            // T4: virtual - endNight records this into night_end/the morning report, both virtual-time.
            val report = endNightTracking(context, nowInstant())
            if (report != null) {
                cachedMorningReport.value = CachedMorningReport(buildNightEngineView(report.nightState, report.endedAt))
                morningReportEndedAt.value = report.endedAt
            } else {
                restoreMorningReportFromDiskIfAny(nightStateFromDisk = null)
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
    fun setSpeed(speed: Int) = debug.setSpeed(speed)
    fun setSimulatedAsleep(asleep: Boolean) = debug.setSimulatedAsleep(asleep)
    fun clearSimulatedSleep() = debug.clearSimulatedSleep()
    fun applyClockJump(time: LocalTime) = debug.applyClockJump(time)
    fun resetClockToRealTime() = debug.resetToRealTime()
    fun ringDebugTestAlarm() = debug.ringTestAlarm()
}

/** A setup check that threw instead of returning a result, folded into the same report shape the UI already renders. */
private fun setupCheckFailureReport(error: Exception): SetupCheckReport = SetupCheckReport(
    isReady = false,
    lines = listOf(SetupCheckLine("Setup check failed: ${error.message ?: error::class.simpleName}", SetupCheckLineSeverity.ACTION_NEEDED)),
)
