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

    /**
     * State D: the morning report, shown after "Stop night" / "I'm up, end night" is confirmed, and again
     * whenever a past night is reopened from the Logs screen.
     * [bandAlarmLeftoverTimeLabel] is the time the band is STILL armed at, if any: no Gadgetbridge intent can
     * disarm a slot, so the last alarm this app set survives the night (see NightController.logLeftoverBandAlarm).
     */
    data class MorningReport(
        val endedAtTimeLabel: String,
        val totalSleepDurationLabel: String,
        val wokeAtTimeLabel: String?,
        val stretches: List<StretchLine>,
        val bandAlarmLeftoverTimeLabel: String? = null,
        /** False only for a past night whose log kept the total but not the per-stretch times (see PastNightLog.kt): the report then shows the real total and leaves the stretch card out, instead of drawing it empty or zeroed. Defaults to true, which is every night ended by this app. */
        val stretchDetailRecorded: Boolean = true,
    ) : NightScreenContent

    data object Loading : NightScreenContent
}

/** The band alarm commitment's status line, formatted for display: [confirmed] picks which sentence to show, [timeLabel] is the clock time to fill into it. */
data class BandAlarmStatusUi(val confirmed: Boolean, val timeLabel: String)

/** The Night screen's smart-wakeup amber line, formatted for display: [displaySlotNumber] is the slot the way the owner counts it in Gadgetbridge's alarm list (position + 1 - see SetupCheck.kt's describeSmartWakeupActionNeeded), [windowMinutes] is the band's own window, or null when the table did not report one. */
data class SmartWakeupWarningUi(val displaySlotNumber: Int, val windowMinutes: Int?)

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
    /** Null unless the slot backing our confirmed or requested band alarm still carries the band's own smart-wakeup flag (BandAlarmDecision.kt cannot fix this, only report it) - shown as an amber status line. Always null while showing the morning report. */
    val smartWakeupWarning: SmartWakeupWarningUi? = null,
    /** True when the last tick could not read the band's own alarm table at all, so nothing it believes about the band was verified and an existing alarm is being held frozen (BandAlarmBlindMode.kt). Shown as an amber status line. Always false while showing the morning report. */
    val bandAlarmBlind: Boolean = false,
    /** True once tonight's [MAX_SINGLE_SLOT_RESENDS_PER_NIGHT] bounded re-sends are all spent: the app has stopped trying to restore the band alarm, which until now only the night log said. Shown as an amber status line. Always false while showing the morning report. */
    val bandAlarmResendLimitReached: Boolean = false,
)
