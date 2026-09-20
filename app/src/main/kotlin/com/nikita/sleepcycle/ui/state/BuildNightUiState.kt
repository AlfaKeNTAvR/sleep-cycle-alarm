package com.nikita.sleepcycle.ui.state

// File purpose: picks which night-screen content variant applies (spec states A-D plus the FINISHED
// amendment; D2/D8 removed the band alarm status line and the OVERDUE amendment) from the engine's mode and
// sleep state, and attaches the always-visible sync status.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.SleepState
import com.nikita.sleepcycle.night.NightEngineView
import com.nikita.sleepcycle.night.NightState
import com.nikita.sleepcycle.night.activeDebugSwitches
import com.nikita.sleepcycle.ui.format.formatClockTime
import java.time.Instant
import java.time.ZoneId

/**
 * Builds the night screen's state. While [showingMorningReport] is true, [engineView] must be the view captured
 * just before `endNight` was called (the night state is cleared by then, so it cannot be recomputed); otherwise
 * [engineView] is `buildNightEngineView(nightState, now)`. Returns null when there is nothing to show at all.
 */
fun buildNightUiState(
    nightState: NightState?,
    engineView: NightEngineView?,
    now: Instant,
    zone: ZoneId,
    showingMorningReport: Boolean,
    morningReportEndedAt: Instant?,
    confirmingEndNight: Boolean,
    endingNight: Boolean = false,
): NightUiState? {
    if (showingMorningReport) {
        val view = engineView ?: return null
        return NightUiState(
            lastSyncTimeLabel = null,
            lastSyncOk = null,
            syncFailureCause = null,
            content = buildMorningReportContent(view, morningReportEndedAt ?: now, zone),
            endAction = EndNightAction.END,
            confirmingEndNight = false,
        )
    }

    val state = nightState ?: return null
    val syncLabel = state.lastSyncAt?.let { formatClockTime(it, zone) }
    val plan = state.lastPlan
    if (plan == null || engineView == null) {
        return NightUiState(
            syncLabel, state.lastSyncOk, state.lastSyncFailureCause,
            NightScreenContent.Loading, EndNightAction.STOP, confirmingEndNight, endingNight,
            activeDebugSwitches = activeDebugSwitches(state.debugOptions),
        )
    }

    val (content, endAction) = when (plan.mode) {
        AlarmMode.FINISHED -> buildFinishedContent(plan) to EndNightAction.END
        // Asleep again for the nap: asleep-style wording, not "you slept" - there is no completed
        // stretch to report yet, just the short nap alarm ahead.
        AlarmMode.NAP -> when (engineView.sleepState) {
            SleepState.ASLEEP -> buildNapAsleepContent(plan, zone) to EndNightAction.IM_UP
            SleepState.AWAKE, SleepState.NOT_YET_ASLEEP -> buildWokeUpContent(plan, state.settings.deadline, engineView, zone, napOnly = true, state.debugOptions) to EndNightAction.IM_UP
        }
        AlarmMode.FULL_CYCLES, AlarmMode.DEADLINE_ONLY -> when (engineView.sleepState) {
            SleepState.AWAKE -> buildWokeUpContent(plan, state.settings.deadline, engineView, zone, napOnly = false, state.debugOptions) to EndNightAction.STOP
            SleepState.NOT_YET_ASLEEP, SleepState.ASLEEP -> buildGoingToBedContent(state.settings, plan, engineView, zone, state.debugOptions) to EndNightAction.STOP
        }
    }
    return NightUiState(
        syncLabel, state.lastSyncOk, state.lastSyncFailureCause, content, endAction, confirmingEndNight, endingNight,
        activeDebugSwitches = activeDebugSwitches(state.debugOptions),
    )
}
