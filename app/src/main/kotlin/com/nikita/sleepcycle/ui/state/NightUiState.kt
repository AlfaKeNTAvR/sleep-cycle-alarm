package com.nikita.sleepcycle.ui.state

import com.nikita.sleepcycle.night.ActiveDebugSwitch

/** One tick of the "possible wake-ups" timeline: a cycle count, its clock time and hour label, and whether it is the planned one. */
data class WakeTimelineEntry(
    val cycles: Int,
    val timeLabel: String,
    val hoursLabel: String,
    val isPlanned: Boolean,
)

/** One stretch line in the morning report: its from-to span, its duration, and its cycle count to one decimal. */
data class StretchLine(
    val startTimeLabel: String,
    val endTimeLabel: String,
    val durationLabel: String,
    val cyclesLabel: String,
)

/** Which caption state A is in: still working out the projected onset, or asleep with a real one. */
enum class OnsetPhase { PROJECTED, ACTUAL }

/** Which button/confirmation wording the end-night action uses, per screen. */
enum class EndNightAction { STOP, IM_UP, END }

/**
 * The night screen's content, one variant per engine mode (spec states A-D plus the FINISHED amendment; D2/D8
 * removed the band alarm status line and the OVERDUE amendment along with it). Every field is either
 * already-formatted data (a time, a duration) or a plain enum; screen-only wording (captions, labels) lives in
 * `strings.xml`, keyed by the enums here.
 */
sealed interface NightScreenContent {
    /** State A: going to bed, or still/again asleep with no awakening yet counted against this stretch. [isDeadlineOnly] shows "alarm rings at the deadline" instead of a sleep-length subtitle, since no whole cycle fits before it. */
    data class GoingToBedOrAsleep(
        val onsetPhase: OnsetPhase,
        val onsetTimeLabel: String,
        val alarmTimeLabel: String,
        val sleepLengthHoursLabel: String,
        val isDeadlineOnly: Boolean,
        val timeline: List<WakeTimelineEntry>,
        val deadlineTimeLabel: String?,
    ) : NightScreenContent

    /** NAP mode while asleep again for the nap: asleep-style wording (onset caption, the nap alarm as the hero number), not the "you slept" wording of [WokeUp]. */
    data class NapAsleep(
        val onsetTimeLabel: String,
        val alarmTimeLabel: String,
    ) : NightScreenContent

    /** States B and C: awake after a stretch. [napOnly] selects C's nap card over B's timeline. */
    data class WokeUp(
        val napOnly: Boolean,
        val sleptDurationLabel: String,
        /**
         * The time in this card's header sentence, already formatted: C's "No full cycle fits before X" (the
         * deadline) or B's "Fall back asleep by X" (the deadline minus one cycle). Null on a night with no
         * deadline, where neither sentence has a time to name. Named for what it is - the header's time -
         * since the "wake boundary" it used to be named after no longer exists anywhere in the engine.
         */
        val headerTimeLabel: String?,
        val timeline: List<WakeTimelineEntry>,
        val napLengthLabel: String?,
        val tonightSoFarLabel: String?,
        val alarmTimeLabel: String?,
        val alarmIsEstimate: Boolean,
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
