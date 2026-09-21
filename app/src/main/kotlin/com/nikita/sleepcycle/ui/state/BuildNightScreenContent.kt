package com.nikita.sleepcycle.ui.state

// File purpose: pure derivation of each night-screen content variant (states A-D plus the FINISHED amendment)
// from one AlarmPlan and NightEngineView. Split from BuildNightUiState.kt to keep each file small.

import com.nikita.sleepcycle.alarm.AlarmLabel
import com.nikita.sleepcycle.alarm.alarmLabelFor
import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.AlarmPlan
import com.nikita.sleepcycle.engine.NightSettings
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

/**
 * Shown instead of a real time when the engine has not produced an alarm for the current mode. H8 adds one
 * legitimate case to what used to be "should not normally happen": the morning alarm has already rung while
 * the band still reads ASLEEP, so the plan is FULL_CYCLES with nothing left to arm (see WakeAlarm.kt's
 * `morningAlarmAlreadyRang`). That lasts until the band reports the owner awake, and it is honest - there is
 * no next alarm. Before H8 those same minutes showed a live time that was really the one spent alarm being
 * re-armed two minutes out, over and over.
 */
const val MISSING_TIME_LABEL = "--:--"

/**
 * State A: going to bed, or still/again asleep with no completed awakening yet in this stretch. Sleep length
 * is [AlarmPlan.cycles] - what the plan actually landed on (e.g. capped by a deadline) - not the raw picked
 * length. [debugOptions] is the night's own captured options (see NightState.debugOptions), passed through to
 * [sleepLengthFor]/[napLengthFor]. [morningAlarmAt] is NightState's own latched morning-alarm time (H1),
 * needed for [alarmModeLabel] and for the H8 already-rang case below.
 */
fun buildGoingToBedContent(settings: NightSettings, plan: AlarmPlan, view: NightEngineView, zone: ZoneId, debugOptions: DebugOptions, morningAlarmAt: Instant?): NightScreenContent.GoingToBedOrAsleep {
    val onset = checkNotNull(plan.referenceOnset) { "referenceOnset is only null once the night is FINISHED" }
    val isDeadlineOnly = plan.mode == AlarmMode.DEADLINE_ONLY
    // H8's one legitimate MISSING_TIME_LABEL case: wakeAt is null because the morning alarm already rang
    // while the band still reads ASLEEP. morningAlarmAt is that same alarm's own latched time (H1), so it is
    // the honest thing to show instead of a bare dash - "the alarm rang at 07:00", not "--:--".
    val wakeAt = plan.wakeAt
    val alarmAlreadyRang = wakeAt == null && morningAlarmAt != null
    val alarmTimeLabel = when {
        wakeAt != null -> formatClockTime(wakeAt, zone)
        alarmAlreadyRang -> formatClockTime(checkNotNull(morningAlarmAt), zone)
        else -> MISSING_TIME_LABEL
    }
    return NightScreenContent.GoingToBedOrAsleep(
        onsetPhase = if (plan.onsetIsProjected) OnsetPhase.PROJECTED else OnsetPhase.ACTUAL,
        onsetTimeLabel = formatClockTime(onset, zone),
        alarmTimeLabel = alarmTimeLabel,
        sleepLengthHoursLabel = formatSleepLengthLabel(sleepLengthFor(plan.cycles, debugOptions)),
        isDeadlineOnly = isDeadlineOnly,
        modeLabel = alarmModeLabel(plan, morningAlarmAt),
        reasonText = plan.reason,
        alarmAlreadyRang = alarmAlreadyRang,
    )
}

/** NAP mode while asleep again for the nap: asleep-style wording, the nap alarm as the hero number, no cycle timeline - a nap is one fixed short alarm, not a choice of cycles. */
fun buildNapAsleepContent(plan: AlarmPlan, zone: ZoneId, morningAlarmAt: Instant?): NightScreenContent.NapAsleep {
    val onset = checkNotNull(plan.referenceOnset) { "referenceOnset is only null once the night is FINISHED" }
    return NightScreenContent.NapAsleep(
        onsetTimeLabel = formatClockTime(onset, zone),
        alarmTimeLabel = plan.wakeAt?.let { formatClockTime(it, zone) } ?: MISSING_TIME_LABEL,
        modeLabel = alarmModeLabel(plan, morningAlarmAt),
        reasonText = plan.reason,
    )
}

/**
 * States B (more sleep fits) and C (only a nap fits), selected by [napOnly]. The header time differs by
 * state: C's "No full cycle fits before X" wants [deadline] itself, but B's "Fall back asleep by X"
 * wants a genuine fall-asleep time - the latest instant at which falling back asleep still lets one more
 * cycle land before the deadline, i.e. the deadline minus one cycle length (D4). On a night with no deadline
 * there is no such time at all (the picked total alone decides when the night ends), so the header is dropped.
 * [morningAlarmAt]: see [buildGoingToBedContent]'s own doc - here it matters most for state C (napOnly), where
 * [alarmModeLabel] can resolve to the morning alarm rather than the nap (rule 7's sliding AWAKE nap).
 */
fun buildWokeUpContent(plan: AlarmPlan, deadline: Instant?, view: NightEngineView, zone: ZoneId, napOnly: Boolean, debugOptions: DebugOptions, morningAlarmAt: Instant?): NightScreenContent.WokeUp {
    val latestStretchDuration = view.summary.stretches.lastOrNull()?.duration ?: Duration.ZERO
    val headerTime = if (napOnly) deadline else deadline?.minus(sleepLengthFor(1, debugOptions))
    return NightScreenContent.WokeUp(
        napOnly = napOnly,
        sleptDurationLabel = formatDuration(latestStretchDuration),
        headerTimeLabel = headerTime?.let { formatClockTime(it, zone) },
        napLengthLabel = if (napOnly) formatDuration(napLengthFor(debugOptions)) else null,
        tonightSoFarLabel = if (napOnly) formatDuration(view.summary.totalSleep) else null,
        alarmTimeLabel = if (napOnly) null else plan.wakeAt?.let { formatClockTime(it, zone) },
        alarmIsEstimate = plan.onsetIsProjected,
        modeLabel = alarmModeLabel(plan, morningAlarmAt),
        reasonText = plan.reason,
    )
}

/** Amendment: the night is over but not ended yet; [AlarmPlan.reason] is already one plain-English sentence. F14: FINISHED never carries an alarm (wakeAt is null by construction), so there is nothing else to show. */
fun buildFinishedContent(plan: AlarmPlan): NightScreenContent.NightFinished =
    NightScreenContent.NightFinished(plan.reason)

/** State D: the morning report, built from the view captured just before ending the night. */
fun buildMorningReportContent(
    view: NightEngineView,
    endedAt: Instant,
    zone: ZoneId,
): NightScreenContent.MorningReport {
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

/**
 * Which alarm the owner should expect next, in the same wording the ring screen and notification already use
 * (see AlarmLabel.kt) - so a plan of mode NAP is not just assumed to mean [AlarmLabel.NAP]: rule 7's sliding
 * AWAKE nap can carry the night's own still-pending morning alarm as its own `wakeAt` (H8), and calling that a
 * nap would be exactly the "why is the alarm beeping" confusion this whole feature exists to fix. Delegates to
 * the same [alarmLabelFor] PhoneAlarmReceiver attributes a real firing with, so the screen and the ring can
 * never disagree. When [AlarmPlan.wakeAt] is null there is no `armedFor` instant to delegate with - only NAP
 * (F5, a nap alarm that already fired with nothing left to arm) and FULL_CYCLES/DEADLINE_ONLY (H8, the morning
 * alarm already rang) reach here with a null `wakeAt`, and neither is ambiguous: a null-`wakeAt` NAP plan is
 * always a genuine nap (see [alarmLabelFor]'s own `firedAlarmIsWakeAlarm`, which short-circuits true for every
 * other mode), so it is [AlarmLabel.NAP] or [AlarmLabel.MORNING] by [AlarmPlan.mode] alone.
 */
internal fun alarmModeLabel(plan: AlarmPlan, morningAlarmAt: Instant?): AlarmLabel =
    plan.wakeAt?.let { alarmLabelFor(plan.mode, it, morningAlarmAt) }
        ?: if (plan.mode == AlarmMode.NAP) AlarmLabel.NAP else AlarmLabel.MORNING
