package com.nikita.sleepcycle.night

// File purpose: the UI-facing entry points - start/end a night, request a tick, and observe night state.
// endNight runs under the same withNightTransactionLock as every tick, so a tick that finishes after
// endNight can never resurrect state (runNightTick re-checks that a night is still active once it gets the lock).
// startNight computes the initial plan, arms the phone alarm from it, saves state and dismisses any stale
// band alarm - all inside ONE lock acquisition - before the first real tick is even started, so there is no
// window where the tick's own work could be erased by a dismissal issued out of order (Opus review 3.2).

import android.content.Context
import com.nikita.sleepcycle.alarm.cancelPhoneAlarm
import com.nikita.sleepcycle.alarm.readAlarmNotificationReadiness
import com.nikita.sleepcycle.alarm.schedulePhoneAlarm
import com.nikita.sleepcycle.alarm.stopAlarmRinging
import com.nikita.sleepcycle.bridge.BandDataResult
import com.nikita.sleepcycle.bridge.syncAndReadBandData
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.computeAlarmPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private val nightStateFlow = MutableStateFlow<NightState?>(null)

/** The current night state for the UI to observe; updated by every call in this file. */
val observedNightState: StateFlow<NightState?> = nightStateFlow.asStateFlow()

/** Loads the persisted night state into [observedNightState] off the main thread; call once when the UI (re)attaches, e.g. after process death. */
fun refreshNightStateFromDisk(context: Context) {
    controllerScope.launch {
        nightStateFlow.value = withContext(Dispatchers.IO) { loadNightState(context) }
    }
}

/** Publishes a freshly committed state to [observedNightState] (D1) - the one seam NightService uses so a tick that ran from the service, not from an immediate UI-requested tick, still reaches the ViewModel. */
fun publishNightState(state: NightState) {
    nightStateFlow.value = state
}

/**
 * Begins a night as one ordered sequence, all under a single lock acquisition: computes the initial plan
 * from empty segments (decision 6 - a band alarm must exist even if every sync later fails), arms the phone
 * alarm from it, saves state, logs readiness, and dismisses any leftover band alarm from a crashed night -
 * only once all of that has committed does it start the tracking service, which runs the first real tick
 * (with actual band data) on its own. [debugOptions] (already passed through [resolveDebugOptions] by the
 * caller) is captured into [NightState.debugOptions] for the whole night and drives [resolveEngineConfig],
 * so a fast debug night uses the same EngineConfig from its very first plan onward.
 */
fun startNight(context: Context, settings: NightSettings, now: Instant, debugOptions: DebugOptions = DebugOptions()) {
    clearMorningReport(context)
    controllerScope.launch {
        withNightTransactionLock {
            val zone = ZoneId.systemDefault()
            val initialPlan = computeAlarmPlan(emptyList(), settings, now, previousPlan = null, zone, resolveEngineConfig(debugOptions))
            val state = NightState(
                startedAt = now,
                settings = settings,
                lastPlan = initialPlan,
                requestedBandAlarm = null,
                confirmedBandAlarm = null,
                lastSyncAt = null,
                lastSyncOk = null,
                lastSegments = emptyList(),
                lastExportFileModifiedAt = null,
                lastSyncFailureCause = null,
                debugOptions = debugOptions
            )
            // D2: the same guard used every tick and after a reboot - degenerate but possible with a deadline
            // already at or before "now" (a deadline always drives the phone alarm, in every mode).
            if (shouldArmPhoneAlarm(initialPlan.phoneAlarm, now, phoneAlarmFiredFor = null)) {
                schedulePhoneAlarm(context, requireNotNull(initialPlan.phoneAlarm))
            }
            if (!saveNightState(context, state)) {
                appendNightLog(context, now, NightLogEvent(now, "error", mapOf("step" to "save_night_state", "cause" to "failed to save the initial night state")), debugOptions.isAnyEnabled)
            }
            nightStateFlow.value = state
            appendNightLog(
                context, now,
                NightLogEvent(
                    now, "night_start",
                    mapOf(
                        "pickedCycles" to settings.pickedCycles.toString(),
                        "deadline" to (settings.deadline?.toString() ?: "none"),
                        "simulatedBandData" to debugOptions.simulatedBandData.toString(),
                        "fastNight" to debugOptions.fastNight.toString(),
                        "bandCommandMode" to debugOptions.bandCommandMode.name
                    )
                ),
                debugOptions.isAnyEnabled
            )
            logAlarmReadiness(context, now, debugOptions.isAnyEnabled)
            dismissBothBandAlarmTitles(context, debugOptions)
        }
        startNightServiceForTick(context)
    }
}

/** The night state ending produced, built inside the same lock that committed it - what the caller should render, not whatever snapshot it took before calling [endNight] (D1). Null when there was no night in progress to end. */
data class EndNightReport(val nightState: NightState, val endedAt: Instant)

/**
 * Ends the night: stops the alarm if it is ringing, dismisses both band alarm titles, cancels the phone
 * alarm and future ticks, writes the summary, persists a morning-report snapshot of the state (so it
 * survives leaving and reopening the app once, even after process death), then clears the live state.
 * Suspends until all of that has committed and returns the definitive [EndNightReport] to render - a tick
 * that finished a moment before this call could have produced a state newer than whatever the caller last
 * observed, so the caller must render this return value, not its own earlier snapshot.
 */
suspend fun endNight(context: Context, now: Instant): EndNightReport? = withNightTransactionLock {
    stopAlarmRinging(context)
    val state = withContext(Dispatchers.IO) { loadNightState(context) }
    if (state != null) {
        dismissBothBandAlarmTitles(context, state.debugOptions)
        withContext(Dispatchers.IO) { verifyBandAlarmCleanup(context, state) }
        appendNightLog(context, state.startedAt, NightLogEvent(now, "night_end", mapOf("summary" to nightEndSummaryText(state, now))), state.debugOptions.isAnyEnabled)
        withContext(Dispatchers.IO) { saveMorningReport(context, MorningReportSnapshot(state, now)) }
    }
    cancelTick(context)
    cancelPhoneAlarm(context)
    clearNightState(context)
    stopNightService(context)
    nightStateFlow.value = null
    // A1: every debug switch resets to off when a night ends, so a simulated night can never leak into the
    // next one just because the Debug screen was left as it was.
    writeDebugOptions(context, DebugOptions(), now)
    state?.let { EndNightReport(it, now) }
}

/** Asks the tracking service to run one sync-plan cycle right away, e.g. when the user opens the Night screen. Goes through the same transaction lock as every other tick. */
fun requestImmediateTick(context: Context) {
    controllerScope.launch {
        val newState = runNightTick(context, Instant.now())
        if (newState != null) nightStateFlow.value = newState
    }
}

/** A3: goes through [sendBandAlarmCommand], the one seam that honors dry run - never [sendDismissBandAlarm] directly, which would reach the real band even with "band commands not sent" switched on. */
private suspend fun dismissBothBandAlarmTitles(context: Context, debugOptions: DebugOptions) {
    val deviceMac = readAppSettings(context).first().deviceMac ?: return
    sendBandAlarmCommand(context, debugOptions, deviceMac, BandAlarmCommand.Dismiss(BAND_ALARM_TITLE_A))
    sendBandAlarmCommand(context, debugOptions, deviceMac, BandAlarmCommand.Dismiss(BAND_ALARM_TITLE_B))
}

/**
 * C4: [dismissBothBandAlarmTitles] just sent both dismissals blind, with no read-back tracking of its own
 * (unlike a normal tick's decideBandAlarmCommands). Runs ONE sync, if a sync is even possible, and logs
 * whether the table now really shows both our titles gone - so ending the night is not "fire and forget" for
 * the very dismissals that matter most (nothing must keep buzzing after the owner said they are up).
 */
private suspend fun verifyBandAlarmCleanup(context: Context, state: NightState) {
    val appSettings = readAppSettings(context).first()
    val deviceMac = appSettings.deviceMac
    val exportUri = appSettings.exportUri
    val debugNight = state.debugOptions.isAnyEnabled
    if (deviceMac == null || exportUri == null || state.debugOptions.bandCommandMode == BandCommandMode.DRY_RUN) {
        appendNightLog(
            context, state.startedAt,
            NightLogEvent(Instant.now(), "band_alarm_cleanup_unverified", mapOf("cause" to "no sync possible: device MAC/export file missing, or dry run")),
            debugNight
        )
        return
    }
    when (val result = syncAndReadBandData(context, exportUri, deviceMac, state.startedAt) { event -> appendNightLog(context, state.startedAt, event, debugNight) }) {
        is BandDataResult.Failure -> appendNightLog(
            context, state.startedAt,
            NightLogEvent(Instant.now(), "band_alarm_cleanup_unverified", mapOf("cause" to "sync failed: ${result.cause}")),
            debugNight
        )
        is BandDataResult.Success -> {
            val stillPresent = result.bandAlarms.filter { it.title == BAND_ALARM_TITLE_A || it.title == BAND_ALARM_TITLE_B }
            val event = if (stillPresent.isEmpty()) {
                NightLogEvent(Instant.now(), "band_alarm_cleanup_confirmed", emptyMap())
            } else {
                NightLogEvent(Instant.now(), "band_alarm_cleanup_still_present", mapOf("titles" to stillPresent.joinToString(",") { it.title ?: "" }))
            }
            appendNightLog(context, state.startedAt, event, debugNight)
        }
    }
}

private fun logAlarmReadiness(context: Context, now: Instant, debugNight: Boolean) {
    val readiness = readAlarmNotificationReadiness(context)
    appendNightLog(
        context, now,
        NightLogEvent(
            now, "alarm_readiness",
            mapOf(
                "canUseFullScreenIntent" to readiness.canUseFullScreenIntent.toString(),
                "notificationsEnabled" to readiness.notificationsEnabled.toString(),
                "alarmChannelBlocked" to readiness.alarmChannelBlocked.toString(),
                "alarmChannelDowngraded" to readiness.alarmChannelDowngraded.toString()
            )
        ),
        debugNight
    )
}

private fun nightEndSummaryText(state: NightState, now: Instant): String {
    val summary = buildNightEngineView(state, now).summary
    return "total=${summary.totalSleep} stretches=${summary.stretches.size}"
}
