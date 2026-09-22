package com.nikita.sleepcycle.ui.state

// File purpose: picks which night-screen content variant applies (N1: one for a live night, plus the morning
// report, the FINISHED amendment and Loading) from the engine's mode, and attaches the always-visible sync
// status.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.night.NightEngineView
import com.nikita.sleepcycle.night.NightState
import com.nikita.sleepcycle.night.ActiveDebugSwitch
import com.nikita.sleepcycle.night.activeDebugSwitches
import com.nikita.sleepcycle.night.formatSimulatedTimeValue
import com.nikita.sleepcycle.ui.format.formatClockTime
import java.time.Instant
import java.time.ZoneId

/**
 * Builds the night screen's state. While [showingMorningReport] is true, [engineView] must be the view captured
 * just before `endNight` was called (the night state is cleared by then, so it cannot be recomputed); otherwise
 * [engineView] is `buildNightEngineView(nightState, now)`. Returns null when there is nothing to show at all.
 * [pendingOutOfBedNudgeAt] is the out-of-bed nudge's own pending fire instant (`OutOfBedNudgeStore.kt`'s
 * `readOutOfBedNudgePendingAt`, resolved by the caller since it is disk I/O) - see [applyPendingOutOfBedNudge]
 * for what it corrects (round 2 of the 09/21 review, must-fix 1). Round 3, should-fix 3: no default value -
 * the whole feature was one deletable argument away from silently reverting to "no alarm armed" forever
 * (dropping it at a call site used to compile clean and leave all tests green), so a dropped argument is now a
 * compile error instead of something only a test can catch. See `BuildUiStateTest.kt`'s
 * `OutOfBedNudgeWiringTest` for the one test that pins the argument actually reaching the screen.
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
    pendingOutOfBedNudgeAt: Instant?,
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
    // W8: on a simulated night the banner always carries the app's own clock reading, warped or not. It used
    // to appear only when a warp existed, so a simulation running at real speed with no jump showed the bare
    // word SIMULATED and gave no way to tell what time the app thought it was - the one thing the reader
    // needs while watching a night unfold.
    val simulatedTimeValue = if (state.debugOptions.isAnyEnabled) formatSimulatedTimeValue(now, zone) else null
    val debugSwitches = activeDebugSwitches(state.debugOptions).let { switches ->
        if (simulatedTimeValue != null && ActiveDebugSwitch.SIMULATED_TIME !in switches) {
            switches + ActiveDebugSwitch.SIMULATED_TIME
        } else {
            switches
        }
    }
    val plan = state.lastPlan
    if (plan == null || engineView == null) {
        return NightUiState(
            syncLabel, state.lastSyncOk, state.lastSyncFailureCause,
            NightScreenContent.Loading, EndNightAction.STOP, confirmingEndNight, endingNight,
            activeDebugSwitches = debugSwitches,
            simulatedTimeValue = simulatedTimeValue,
        )
    }

    val (rawContent, rawEndAction) = when (plan.mode) {
        AlarmMode.FINISHED -> buildFinishedContent(plan) to EndNightAction.END
        // N1: every live night renders the same way now (see NightScreenContent.NextAlarm), so the band's own
        // sleep state no longer picks a content variant at all - it picked between wordings that no longer
        // differ. The mode still picks the end-night button's wording: NAP is the one mode reached only after
        // the owner has already been woken once, which is what "I'm up, end night" says and "Stop night"
        // does not.
        AlarmMode.NAP -> buildNextAlarmContent(plan, zone, state.morningAlarmAt, now) to EndNightAction.IM_UP
        AlarmMode.FULL_CYCLES, AlarmMode.DEADLINE_ONLY -> buildNextAlarmContent(plan, zone, state.morningAlarmAt, now) to EndNightAction.STOP
    }
    // Round 2 of the 09/21 review, must-fix 1, extended by round 3's should-fix 1: see
    // applyPendingOutOfBedNudge's own doc for why the plan's own wakeAt (plan.wakeAt, not the FINISHED case's
    // null) is passed alongside the nudge - it can also override a NON-null modeLabel now, not just a null one.
    val content = applyPendingOutOfBedNudge(rawContent, pendingOutOfBedNudgeAt, plan.wakeAt, now, zone)
    // N2: when that override turns a finished night back into a live one, the button follows the screen. The
    // owner's own words on what the finished screen offered him while a nudge was still armed: "it didn't say
    // that I'm awake and the night. It's just like end night." With an alarm still coming this is an ordinary
    // live night to him, so it gets the live night's own wording.
    val endAction = if (rawContent is NightScreenContent.NightFinished && content is NightScreenContent.NextAlarm) {
        EndNightAction.IM_UP
    } else {
        rawEndAction
    }
    return NightUiState(
        syncLabel, state.lastSyncOk, state.lastSyncFailureCause, content, endAction, confirmingEndNight, endingNight,
        activeDebugSwitches = debugSwitches,
        simulatedTimeValue = simulatedTimeValue,
    )
}
