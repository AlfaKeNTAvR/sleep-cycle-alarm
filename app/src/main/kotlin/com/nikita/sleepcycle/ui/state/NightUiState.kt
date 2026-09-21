package com.nikita.sleepcycle.ui.state

import com.nikita.sleepcycle.alarm.AlarmLabel
import com.nikita.sleepcycle.engine.AlarmPlan
import com.nikita.sleepcycle.night.ActiveDebugSwitch

/** One stretch line in the morning report: its from-to span, its duration, and its cycle count to one decimal. */
data class StretchLine(
    val startTimeLabel: String,
    val endTimeLabel: String,
    val durationLabel: String,
    val cyclesLabel: String,
)

/** Which button/confirmation wording the end-night action uses, per screen. */
enum class EndNightAction { STOP, IM_UP, END }

/**
 * The night screen's content: one variant for every live night ([NextAlarm], N1), plus the two dead ends a
 * night reaches ([NightFinished], [MorningReport]) and [Loading]. It used to be one variant per engine mode
 * (spec states A-D plus the FINISHED amendment; D2/D8 removed the band alarm status line and the OVERDUE
 * amendment along with it; a later owner request removed the "possible wake-ups" bar timeline states A and B
 * used to carry - see the removed `WakeTimelineEntry` and `WakeTimeline.kt` in git history - the owner found a
 * list of candidate wake times not worth reading at 3am). N1 finished that direction: see [NextAlarm].
 * Every field is either already-formatted data (a time, a duration) or a plain enum; screen-only wording
 * (captions, labels) lives in `strings.xml`, keyed by the enums here. N1 removed the one standing exception
 * to that rule along with the lines that carried it: the three active states used to surface
 * `AlarmPlan.reason`, a full sentence generated in the engine (see `Plan.kt`'s `describePlan`) as hardcoded
 * English rather than looked up from `strings.xml`, which the 09/21 review flagged and left as-is because
 * fixing it meant changing the engine's own file. [NightFinished] still carries it, but that state is a dead
 * end the owner reads once, not a line on every glance of a live night.
 */
sealed interface NightScreenContent {
    /**
     * N1 (owner decision, 2026-09-21): every state of a live night - going to bed, asleep, awake again, napping
     * - now answers exactly one question, in one shape: which alarm rings next, when, and how long until then.
     * The three separate variants this replaced (`GoingToBedOrAsleep`, `NapAsleep`, `WokeUp`, all in git
     * history) differed only in the extra lines stacked around that answer - onset captions, a sleep-length
     * subtitle, the deadline, a nap explainer card, a "tonight so far" row, the engine's own reason sentence -
     * and the owner found the result unreadable at 3am ("way too much information"). They are not moved
     * anywhere: the completed-sleep figure already belongs to the morning report at the end of the night, and
     * the rest was context the owner does not act on while half awake. With those gone the three variants had
     * identical fields and identical rendering, so they are one type.
     *
     * [modeLabel] is null when nothing is armed (must-fix 1 of the 09/21 review: [AlarmPlan.wakeAt] null with
     * no genuine label to guess at), rendered as the "no alarm armed" wording rather than naming an alarm that
     * does not exist. [alarmTimeLabel] is [MISSING_TIME_LABEL] in exactly that case, since there is no next
     * alarm to name a time for. [countdownLabel] is how long until [alarmTimeLabel], null whenever nothing is
     * armed. [rangAtTimeLabel] is H8's case - the morning alarm already rang while the band still reads ASLEEP
     * - and is the only thing that fills the line under the hero when no alarm is armed to count down to; it is
     * null whenever [countdownLabel] is set, so the line has one source, never two competing ones.
     */
    data class NextAlarm(
        val modeLabel: AlarmLabel?,
        val alarmTimeLabel: String,
        val countdownLabel: String?,
        val rangAtTimeLabel: String?,
    ) : NightScreenContent

    /**
     * Amendment: the night is over (deadline passed, or D5's nap cap spent with none left) but not ended yet
     * - band-detected wake alone no longer reaches this state (D3). [reasonText] is the engine's own
     * plain-English reason. F14: FINISHED's `wakeAt` is null by construction (see AlarmPlan's own doc), so
     * there is never an armed alarm to show here - the phone alarm and the tick alarm are both cancelled on
     * the same tick FINISHED is reached.
     */
    data class NightFinished(val reasonText: String) : NightScreenContent

    /**
     * State D: the morning report, shown after "Stop night" / "I'm up, end night" is confirmed, and again
     * whenever a past night is reopened from the Logs screen.
     */
    data class MorningReport(
        val endedAtTimeLabel: String,
        val totalSleepDurationLabel: String,
        val wokeAtTimeLabel: String?,
        val stretches: List<StretchLine>,
        /** False only for a past night whose log kept the total but not the per-stretch times (see PastNightLog.kt): the report then shows the real total and leaves the stretch card out, instead of drawing it empty or zeroed. Defaults to true, which is every night ended by this app. */
        val stretchDetailRecorded: Boolean = true,
    ) : NightScreenContent

    data object Loading : NightScreenContent
}

/** Everything the night screen shows: the always-visible sync status, the content, and the end-night flow. */
data class NightUiState(
    val lastSyncTimeLabel: String?,
    val lastSyncOk: Boolean?,
    /** Only set when the last sync itself failed (as opposed to succeeding with stale data); the plain-English cause to show alongside [lastSyncTimeLabel]. */
    val syncFailureCause: String?,
    val content: NightScreenContent,
    val endAction: EndNightAction,
    val confirmingEndNight: Boolean,
    /** True from the moment "confirm" is tapped in the end-night dialog until endNight's result is rendered - the button disables and shows an in-progress label, and the dialog cannot reopen, for the whole ~3 s endNight takes (item 1). */
    val endingNight: Boolean = false,
    /** A1: every debug switch this night was started with that is still live - the screen shows the amber warning banner naming all of them when non-empty. Empty while showing the morning report - the banner's job is done once the night is over. */
    val activeDebugSwitches: List<ActiveDebugSwitch> = emptyList(),
    /** T12 (amended): the live simulated-clock reading, e.g. "03:15" or "03:15, 60x" - null when not warped or while showing the morning report. See [com.nikita.sleepcycle.night.formatSimulatedTimeValue]. */
    val simulatedTimeValue: String? = null,
)
