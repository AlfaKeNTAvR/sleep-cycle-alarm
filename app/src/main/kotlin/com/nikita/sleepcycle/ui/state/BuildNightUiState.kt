package com.nikita.sleepcycle.ui.state

// File purpose: picks which night-screen content variant applies (N1: one for a live night, plus the morning
// report, the FINISHED amendment and Loading) from the engine's mode, and attaches the always-visible sync
// status.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.night.NightEngineView
import com.nikita.sleepcycle.night.NightState
import com.nikita.sleepcycle.night.PendingFollowUp
import com.nikita.sleepcycle.night.FollowUpKind
import com.nikita.sleepcycle.night.resolveEngineConfig
import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.engine.morningAlarmHasRung
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
 * [pendingFollowUp] is the out-of-bed slot's own pending alarm - the nudge, or since P3 the owner's own nap
 * (`OutOfBedNudgeStore.kt`'s `readPendingFollowUp`, resolved by the caller since it is disk I/O) - see [applyPendingOutOfBedNudge]
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
    pendingFollowUp: PendingFollowUp?,
    confirmingImUp: Boolean = false,
): NightUiState? {
    if (showingMorningReport) {
        val view = engineView ?: return null
        return NightUiState(
            lastSyncTimeLabel = null,
            lastSyncOk = null,
            syncFailureCause = null,
            content = buildMorningReportContent(view, morningReportEndedAt ?: now, zone),
            showEndNightButton = false,
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
    // P1: see NightUiState.bandNotSyncingWarning. Null (no sync finished yet) is not a failure.
    val bandNotSyncingWarning = state.lastSyncOk == false
    val plan = state.lastPlan
    if (plan == null || engineView == null) {
        return NightUiState(
            syncLabel, state.lastSyncOk, state.lastSyncFailureCause,
            NightScreenContent.Loading, showEndNightButton = false, confirmingEndNight = confirmingEndNight, endingNight = endingNight,
            activeDebugSwitches = debugSwitches,
            simulatedTimeValue = simulatedTimeValue,
            bandNotSyncingWarning = bandNotSyncingWarning,
        )
    }

    // N1: every live night renders the same way now (see NightScreenContent.NextAlarm), so the band's own sleep
    // state no longer picks a content variant at all.
    val rawContent = when (plan.mode) {
        AlarmMode.FINISHED -> buildFinishedContent(plan)
        AlarmMode.NAP, AlarmMode.FULL_CYCLES, AlarmMode.DEADLINE_ONLY -> buildNextAlarmContent(plan, zone, state.morningAlarmAt, now)
    }
    // Round 2 of the 09/21 review, must-fix 1, extended by round 3's should-fix 1: see
    // applyPendingOutOfBedNudge's own doc for why the plan's own wakeAt (plan.wakeAt, not the FINISHED case's
    // null) is passed alongside the nudge - it can also override a NON-null modeLabel now, not just a null one.
    val content = applyPendingOutOfBedNudge(rawContent, pendingFollowUp, plan.wakeAt, now, zone)
    val showImUp = imUpOffered(state, plan.mode, pendingFollowUp, now)
    return NightUiState(
        syncLabel, state.lastSyncOk, state.lastSyncFailureCause, content,
        // Owner spec, 2026-10-02: one choice at a time - End night only once he is up (see imUpOffered).
        showEndNightButton = !showImUp, confirmingEndNight = confirmingEndNight, endingNight = endingNight,
        activeDebugSwitches = debugSwitches,
        simulatedTimeValue = simulatedTimeValue,
        bandNotSyncingWarning = bandNotSyncingWarning,
        napButtonMinutes = napButtonMinutes(pendingFollowUp, now, resolveEngineConfig(state.debugOptions)),
        showImUpButton = showImUp,
        confirmingImUp = confirmingImUp,
    )
}

/**
 * Owner spec, 2026-10-02: one choice at a time. "I'm up" is offered while he is asleep as far as the app knows -
 * the morning alarm still ahead on a live night, or his own nap still ahead (even past a FINISHED deadline,
 * which L2 keeps running). Otherwise he is up: the nudge is ahead or the night is over, and the screen offers
 * Nap and End night instead.
 */
private fun imUpOffered(state: NightState, mode: AlarmMode, pendingFollowUp: PendingFollowUp?, now: Instant): Boolean {
    val ownNapAhead = pendingFollowUp?.kind == FollowUpKind.NAP && pendingFollowUp.at.isAfter(now)
    val morningAlarmAhead = mode != AlarmMode.FINISHED &&
        !morningAlarmHasRung(state.wakeAlarmFiredAt, state.morningAlarmAt, state.phoneAlarmFiredFor)
    return ownNapAhead || morningAlarmAhead
}

/**
 * P3 (owner spec, 2026-09-30): the "Nap N min" button is offered exactly while the out-of-bed NUDGE is pending
 * and still ahead of [now] - that is the one thing it can be traded for (PostAlarmCycle.kt's nextFollowUp
 * ignores a press with anything else pending). Hidden while the owner's own nap is already armed: a second press
 * would change nothing, and the screen already names that nap. Hidden for a stale record whose instant has
 * passed, by the same `isAfter(now)` rule the label override uses.
 */
private fun napButtonMinutes(pendingFollowUp: PendingFollowUp?, now: Instant, config: EngineConfig): Int? {
    val nudgePending = pendingFollowUp?.kind == FollowUpKind.NUDGE && pendingFollowUp.at.isAfter(now)
    return if (nudgePending) config.napLength.toMinutes().toInt() else null
}
