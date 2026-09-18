package com.nikita.sleepcycle.ui.state

// File purpose: pure derivation of each night-screen content variant (states A-D plus the OVERDUE and FINISHED
// amendments) from one AlarmPlan and NightEngineView. Split from BuildNightUiState.kt to keep each file small.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.AlarmPlan
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.WakeOption
import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.NightEngineView
import com.nikita.sleepcycle.night.napLengthFor
import com.nikita.sleepcycle.night.sleepLengthFor
import com.nikita.sleepcycle.ui.format.formatClockTime
import com.nikita.sleepcycle.ui.format.formatCycles
import com.nikita.sleepcycle.ui.format.formatDuration
import com.nikita.sleepcycle.ui.format.formatSleepLengthLabel
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Shown instead of a real time when the engine has not produced an alarm for the current mode (should not normally happen). */
const val MISSING_TIME_LABEL = "--:--"

/**
 * State A: going to bed, or still/again asleep with no completed awakening yet in this stretch. Sleep length
 * is [AlarmPlan.cycles] - what the plan actually landed on (e.g. capped by a deadline) - not the raw picked
 * length. [debugOptions] is the night's own captured options (see NightState.debugOptions): a fast debug
 * night's durations are labelled honestly in minutes rather than as a decimal-hour label (see
 * [formatSleepLengthLabel]).
 */
fun buildGoingToBedContent(settings: NightSettings, plan: AlarmPlan, view: NightEngineView, zone: ZoneId, debugOptions: DebugOptions): NightScreenContent.GoingToBedOrAsleep {
    val onset = checkNotNull(plan.referenceOnset) { "referenceOnset is only null once the night is FINISHED" }
    val isDeadlineOnly = plan.mode == AlarmMode.DEADLINE_ONLY
    return NightScreenContent.GoingToBedOrAsleep(
        onsetPhase = if (plan.onsetIsProjected) OnsetPhase.PROJECTED else OnsetPhase.ACTUAL,
        onsetTimeLabel = formatClockTime(onset, zone),
        alarmTimeLabel = plan.bandAlarm?.let { formatClockTime(it, zone) } ?: MISSING_TIME_LABEL,
        sleepLengthHoursLabel = formatSleepLengthLabel(sleepLengthFor(plan.cycles, debugOptions), debugOptions.fastNight),
        isDeadlineOnly = isDeadlineOnly,
        timeline = view.wakeOptions.map { toTimelineEntry(it, plan.cycles, zone, debugOptions) },
        deadlineTimeLabel = settings.deadline?.let { formatClockTime(it, zone) },
        phoneSafetyAlarmTimeLabel = plan.phoneAlarm?.let { formatClockTime(it, zone) },
    )
}

/** NAP mode while asleep again for the nap (D4): asleep-style wording, the nap alarm as the hero number, no cycle timeline - a nap is one fixed short alarm, not a choice of cycles. */
fun buildNapAsleepContent(plan: AlarmPlan, zone: ZoneId): NightScreenContent.NapAsleep {
    val onset = checkNotNull(plan.referenceOnset) { "referenceOnset is only null once the night is FINISHED" }
    return NightScreenContent.NapAsleep(
        onsetTimeLabel = formatClockTime(onset, zone),
        alarmTimeLabel = plan.bandAlarm?.let { formatClockTime(it, zone) } ?: MISSING_TIME_LABEL,
        phoneSafetyAlarmTimeLabel = plan.phoneAlarm?.let { formatClockTime(it, zone) },
    )
}

/**
 * States B (more sleep fits) and C (only a nap fits), selected by [napOnly]. The header time differs by
 * state: C's "No full cycle fits before X" wants [deadline] itself, but B's "Fall back asleep by X"
 * wants a genuine fall-asleep time - the latest instant at which falling back asleep still lets one more
 * cycle land before the deadline, i.e. the deadline minus one cycle length (D4). On a night with no deadline
 * there is no such time at all (the picked total alone decides when the night ends), so the header is dropped.
 */
fun buildWokeUpContent(plan: AlarmPlan, deadline: Instant?, view: NightEngineView, zone: ZoneId, napOnly: Boolean, debugOptions: DebugOptions): NightScreenContent.WokeUp {
    val latestStretchDuration = view.summary.stretches.lastOrNull()?.duration ?: Duration.ZERO
    val headerTime = if (napOnly) deadline else deadline?.minus(sleepLengthFor(1, debugOptions))
    return NightScreenContent.WokeUp(
        napOnly = napOnly,
        sleptDurationLabel = formatDuration(latestStretchDuration),
        headerTimeLabel = headerTime?.let { formatClockTime(it, zone) },
        timeline = if (napOnly) emptyList() else view.wakeOptions.map { toTimelineEntry(it, plan.cycles, zone, debugOptions) },
        napLengthLabel = if (napOnly) formatDuration(napLengthFor(debugOptions)) else null,
        tonightSoFarLabel = if (napOnly) formatDuration(view.summary.totalSleep) else null,
        bandAlarmTimeLabel = if (napOnly) null else plan.bandAlarm?.let { formatClockTime(it, zone) },
        bandAlarmIsEstimate = plan.onsetIsProjected,
        phoneSafetyAlarmTimeLabel = plan.phoneAlarm?.let { formatClockTime(it, zone) },
    )
}

/** Amendment: still asleep past the planned alarm; the band keeps buzzing every few minutes until an awake mark. */
fun buildOverdueContent(plan: AlarmPlan, zone: ZoneId): NightScreenContent.Overdue = NightScreenContent.Overdue(
    nextBuzzTimeLabel = plan.bandAlarm?.let { formatClockTime(it, zone) } ?: MISSING_TIME_LABEL,
    phoneSafetyAlarmTimeLabel = plan.phoneAlarm?.let { formatClockTime(it, zone) },
)

/** Amendment: the night is over but not ended yet; [AlarmPlan.reason] is already one plain-English sentence. The phone alarm is still armed at this point (D4), so it is shown here too. */
fun buildFinishedContent(plan: AlarmPlan, zone: ZoneId): NightScreenContent.NightFinished =
    NightScreenContent.NightFinished(plan.reason, plan.phoneAlarm?.let { formatClockTime(it, zone) })

/** State D: the morning report, built from the view captured just before ending the night. */
fun buildMorningReportContent(view: NightEngineView, endedAt: Instant, zone: ZoneId): NightScreenContent.MorningReport {
    val stretches = view.summary.stretches.map { stretch ->
        StretchLine(
            startTimeLabel = formatClockTime(stretch.onset, zone),
            endTimeLabel = formatClockTime(stretch.end, zone),
            durationLabel = formatDuration(stretch.duration),
            cyclesLabel = formatCycles(stretch.cycles),
        )
    }
    return NightScreenContent.MorningReport(
        endedAtTimeLabel = formatClockTime(endedAt, zone),
        totalSleepDurationLabel = formatDuration(view.summary.totalSleep),
        wokeAtTimeLabel = view.summary.stretches.lastOrNull()?.end?.let { formatClockTime(it, zone) },
        stretches = stretches,
    )
}

private fun toTimelineEntry(option: WakeOption, plannedCycles: Int, zone: ZoneId, debugOptions: DebugOptions): WakeTimelineEntry = WakeTimelineEntry(
    cycles = option.cycles,
    timeLabel = formatClockTime(option.wakeTime, zone),
    hoursLabel = formatSleepLengthLabel(option.sleepDuration, debugOptions.fastNight),
    isPlanned = option.cycles == plannedCycles,
)
