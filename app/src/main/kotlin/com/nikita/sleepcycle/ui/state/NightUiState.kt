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
 * The night screen's content, one variant per engine mode (spec states A-D plus the OVERDUE and FINISHED
 * amendments). Every field is either already-formatted data (a time, a duration) or a plain enum; screen-only
 * wording (captions, labels) lives in `strings.xml`, keyed by the enums here.
 */
sealed interface NightScreenContent {
    /** State A: going to bed, or still/again asleep with no awakening yet counted against this stretch. [isDeadlineOnly] shows "band vibrates at the deadline" instead of a sleep-length subtitle, since no whole cycle fits before it. */
    data class GoingToBedOrAsleep(
        val onsetPhase: OnsetPhase,
        val onsetTimeLabel: String,
        val alarmTimeLabel: String,
        val sleepLengthHoursLabel: String,
        val isDeadlineOnly: Boolean,
        val timeline: List<WakeTimelineEntry>,
        val deadlineTimeLabel: String?,
        val phoneSafetyAlarmTimeLabel: String?,
    ) : NightScreenContent

    /** NAP mode while asleep again for the nap: asleep-style wording (onset caption, the nap alarm as the hero number), not the "you slept" wording of [WokeUp]. */
    data class NapAsleep(
        val onsetTimeLabel: String,
        val alarmTimeLabel: String,
        val phoneSafetyAlarmTimeLabel: String?,
    ) : NightScreenContent

    /** States B and C: awake after a stretch. [napOnly] selects C's nap card over B's timeline. */
    data class WokeUp(
        val napOnly: Boolean,
        val sleptDurationLabel: String,
        val wakeBoundaryTimeLabel: String?,
        val timeline: List<WakeTimelineEntry>,
        val napLengthLabel: String?,
        val tonightSoFarLabel: String?,
        val bandAlarmTimeLabel: String?,
        val bandAlarmIsEstimate: Boolean,
        val phoneSafetyAlarmTimeLabel: String?,
    ) : NightScreenContent

    /** Amendment: still asleep past the planned alarm; the band keeps buzzing every few minutes. */
    data class Overdue(
        val nextBuzzTimeLabel: String,
        val phoneSafetyAlarmTimeLabel: String?,
    ) : NightScreenContent

    /** Amendment: the night is over (deadline passed, or woken at the alarm) but not ended yet. [reasonText] is the engine's own plain-English reason. [phoneSafetyAlarmTimeLabel] is the phone alarm that is still armed (D4). */
    data class NightFinished(val reasonText: String, val phoneSafetyAlarmTimeLabel: String?) : NightScreenContent

    /** State D: the morning report, shown after "Stop night" / "I'm up, end night" is confirmed. */
    data class MorningReport(
        val endedAtTimeLabel: String,
        val totalSleepDurationLabel: String,
        val wokeAtTimeLabel: String?,
        val stretches: List<StretchLine>,
    ) : NightScreenContent

    data object Loading : NightScreenContent
}

/** The band alarm commitment's status line, formatted for display: [confirmed] picks which sentence to show, [timeLabel] is the clock time to fill into it. */
data class BandAlarmStatusUi(val confirmed: Boolean, val timeLabel: String)

/** Everything the night screen shows: the always-visible sync status, the content, and the end-night flow. */
data class NightUiState(
    val lastSyncTimeLabel: String?,
    val lastSyncOk: Boolean?,
    /** Only set when the last sync itself failed (as opposed to succeeding with stale data); the plain-English cause to show alongside [lastSyncTimeLabel]. */
    val syncFailureCause: String?,
    /** Null once there is nothing pending or confirmed to report (no band alarm wanted right now, or before the first tick). */
    val bandAlarmStatus: BandAlarmStatusUi?,
    val content: NightScreenContent,
    val endAction: EndNightAction,
    val confirmingEndNight: Boolean,
    /** True from the moment "confirm" is tapped in the end-night dialog until endNight's result is rendered - the button disables and shows an in-progress label, and the dialog cannot reopen, for the whole ~3 s endNight takes (item 1). */
    val endingNight: Boolean = false,
    /** A1: every debug switch this night was started with that is still live - the screen shows the amber warning banner naming all of them when non-empty. Empty while showing the morning report - the banner's job is done once the night is over. */
    val activeDebugSwitches: List<ActiveDebugSwitch> = emptyList(),
    /** Item 4: true when tonight has no deadline AND no phone backup, so nothing on the phone will ring if the band fails - shown as an amber status line. Always false while showing the morning report. */
    val noPhoneAlarmWarning: Boolean = false,
)
