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

/** Which caption state A is in: still working out the projected onset, or asleep with a real one. */
enum class OnsetPhase { PROJECTED, ACTUAL }

/**
 * Which subtitle line state A shows under the hero time. Precedence is decided once, in
 * [buildGoingToBedContent], not re-derived in the composable: [ALREADY_RANG] and [DEADLINE_ONLY] can both be
 * true for the same plan (a DEADLINE_ONLY plan can also be H8's already-rang case), so a three-way `when` over
 * two raw booleans in the composable both hides that overlap and puts it somewhere nothing can test it.
 * [ALREADY_RANG] wins because it is about what already happened; [DEADLINE_ONLY] is about what is still ahead.
 */
enum class NightSubtitle { ALREADY_RANG, DEADLINE_ONLY, SLEEP_LENGTH }

/** Which button/confirmation wording the end-night action uses, per screen. */
enum class EndNightAction { STOP, IM_UP, END }

/**
 * The night screen's content, one variant per engine mode (spec states A-D plus the FINISHED amendment; D2/D8
 * removed the band alarm status line and the OVERDUE amendment along with it; a later owner request removed
 * the "possible wake-ups" bar timeline states A and B used to carry - see the removed `WakeTimelineEntry` and
 * `WakeTimeline.kt` in git history - the owner found a list of candidate wake times not worth reading at 3am).
 * Every field is either already-formatted data (a time, a duration) or a plain enum; screen-only wording
 * (captions, labels) lives in `strings.xml`, keyed by the enums here. The one deliberate exception is
 * [GoingToBedOrAsleep.reasonText], [NapAsleep.reasonText] and [WokeUp.reasonText]: `AlarmPlan.reason` is a
 * full sentence generated in the engine (see `Plan.kt`'s `describePlan`) as hardcoded English, not looked up
 * from `strings.xml` - flagged by the 09/21 review as a real exception to this file's own rule, left as-is
 * here since fixing it means changing the engine's own file, not this one.
 */
sealed interface NightScreenContent {
    /**
     * State A: going to bed, or still/again asleep with no awakening yet counted against this stretch.
     * [subtitle] picks the line under the hero time - see [NightSubtitle]'s own doc for the precedence between
     * an already-rung alarm and a deadline-only night. [modeLabel] and [reasonText] (owner request,
     * W18-adjacent) are the engine's own mode and one-sentence explanation, surfaced so a 3am glance says which
     * alarm is coming and why - see BuildNightScreenContent.kt's `alarmModeLabel`. [modeLabel] is null when
     * nothing is armed (must-fix 1 of the 09/21 review: [AlarmPlan.wakeAt] null with no genuine label to guess
     * at), in which case [AlarmModeHeader][com.nikita.sleepcycle.ui.components.AlarmModeHeader] shows the
     * "no alarm armed" wording instead of naming an alarm that does not exist. [alarmAlreadyRang] is H8's one
     * legitimate case for [MISSING_TIME_LABEL]: true when the morning alarm has already rung while the band
     * still reads ASLEEP, in which case [alarmTimeLabel] carries the real rung time (from
     * NightState.morningAlarmAt) instead of a blank dash - honest AND readable, rather than the plain "--:--"
     * this used to show with no explanation; it is also why [reasonText] is suppressed in that state (the
     * "Already rang" subtitle already says what happened, and the engine's [reasonText] sentence trails off
     * with "... alarm none" once [AlarmPlan.wakeAt] is null). [deadlineTimeLabel] is a plain caption for the
     * deadline, shown only in the ordinary [NightSubtitle.SLEEP_LENGTH] case - the owner uses it to decide
     * whether to go back to sleep, and it would otherwise only be visible buried mid-sentence in [reasonText]
     * (see AUTONOMOUS_DECISIONS_09_21_2026_UI.md for why this was restored instead of left dropped).
     */
    data class GoingToBedOrAsleep(
        val onsetPhase: OnsetPhase,
        val onsetTimeLabel: String,
        val alarmTimeLabel: String,
        val sleepLengthHoursLabel: String,
        val subtitle: NightSubtitle,
        val deadlineTimeLabel: String?,
        val modeLabel: AlarmLabel?,
        val reasonText: String?,
        val alarmAlreadyRang: Boolean,
    ) : NightScreenContent

    /**
     * NAP mode while asleep again for the nap: asleep-style wording (onset caption, the nap alarm as the hero
     * number), not the "you slept" wording of [WokeUp]. [reasonText]: see [GoingToBedOrAsleep]'s own doc.
     * [modeLabel] should in practice always be [AlarmLabel.NAP] here, since this state is only reached with a
     * genuine fresh nap onset (buildNightUiState's own NAP/ASLEEP branch) with an armed `wakeAt`, never the H8
     * sliding-nap case that can mislabel a still-pending morning alarm as a nap (that case surfaces AWAKE, in
     * [WokeUp] instead); it is nullable only because it shares `alarmModeLabel`'s uniform null-when-unarmed
     * derivation with the other two states (must-fix 1 of the 09/21 review), not because a null value is
     * expected to reach here.
     */
    data class NapAsleep(
        val onsetTimeLabel: String,
        val alarmTimeLabel: String,
        val modeLabel: AlarmLabel?,
        val reasonText: String,
    ) : NightScreenContent

    /**
     * States B and C: awake after a stretch. [napOnly] selects C's nap card over B's single "fall back asleep
     * by" header. [reasonText]: see [GoingToBedOrAsleep]'s own doc. [modeLabel]: unlike [NapAsleep], the NAP
     * case here (state C) genuinely can be rule 7's sliding AWAKE nap carrying the still-pending morning alarm
     * (see AlarmLabel.kt's own H8 note on `alarmLabelFor`), so it is derived the same careful way, not assumed
     * constant - and it is null (not [AlarmLabel.NAP]) for F5's real case, a nap alarm that already fired with
     * nothing left to arm: the reproduction traced by the 09/21 review (no deadline, 5 cycles, the morning
     * alarm rings at 07:00, the band reports AWAKE at 07:05, rule 7's sliding nap has nothing left to slide to
     * because the wake alarm already fired) is exactly this case, and it must read "no alarm armed", not a
     * bold, wrong "Nap alarm" moments after the owner was woken by the real one.
     */
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
        val napLengthLabel: String?,
        val tonightSoFarLabel: String?,
        val alarmTimeLabel: String?,
        val alarmIsEstimate: Boolean,
        val modeLabel: AlarmLabel?,
        val reasonText: String,
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
