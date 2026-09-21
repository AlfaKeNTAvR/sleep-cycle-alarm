package com.nikita.sleepcycle.ui.state

// File purpose: pure derivation of each night-screen content variant (states A-D plus the FINISHED amendment)
// from one AlarmPlan and NightEngineView. Split from BuildNightUiState.kt to keep each file small.

import com.nikita.sleepcycle.alarm.AlarmLabel
import com.nikita.sleepcycle.alarm.alarmLabelFor
import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.AlarmPlan
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
 * Shown instead of a real time only in the defensive fallback branches that remain after the 09/21 review's
 * must-fix 1: [buildGoingToBedContent]'s own `else` (wakeAt null with no [morningAlarmAt] latched to fall back
 * on either - should not normally happen) and [buildNapAsleepContent]'s equivalent `?:` (a NapAsleep state
 * reached with no armed wakeAt at all, likewise not expected). H8's own already-rang case - the morning alarm
 * has already rung while the band still reads ASLEEP - no longer reaches this dash: [buildGoingToBedContent]
 * shows the real rung time instead (from NightState.morningAlarmAt). Nor does the plain "nothing is armed"
 * case from must-fix 1 (F5, a nap alarm that already fired with nothing left to arm): that is a null
 * [NightScreenContent.WokeUp.modeLabel] rendered by
 * [AlarmModeHeader][com.nikita.sleepcycle.ui.components.AlarmModeHeader] as "no alarm armed", a deliberate
 * state of its own, not this dash standing in for a missing time.
 */
const val MISSING_TIME_LABEL = "--:--"

/**
 * State A: going to bed, or still/again asleep with no completed awakening yet in this stretch. Sleep length
 * is [AlarmPlan.cycles] - what the plan actually landed on (e.g. capped by a deadline) - not the raw picked
 * length. [debugOptions] is the night's own captured options (see NightState.debugOptions), passed through to
 * [sleepLengthFor]/[napLengthFor]. [morningAlarmAt] is NightState's own latched morning-alarm time (H1),
 * needed for [alarmModeLabel] and for the H8 already-rang case below. [deadline] is the night's own deadline
 * setting (`NightSettings.deadline`), taken directly rather than the whole settings object - see the 09/21
 * review's must-fix 3, which dropped this function's former unused `settings`/`view` parameters; [deadline]
 * is the one piece of `settings` this function still needs, for [NightSubtitle.SLEEP_LENGTH]'s own caption.
 */
fun buildGoingToBedContent(plan: AlarmPlan, zone: ZoneId, debugOptions: DebugOptions, morningAlarmAt: Instant?, deadline: Instant?): NightScreenContent.GoingToBedOrAsleep {
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
    // NightSubtitle's own precedence: already-rang beats deadline-only, since a DEADLINE_ONLY plan can also be
    // H8's already-rang case (both booleans can be true at once - see NightSubtitle's own doc). Round 2 of the
    // 09/21 review, should-fix 9: wakeAt == null with alarmAlreadyRang false means morningAlarmAt is null too
    // (the only other way to reach a null wakeAt here) - the defensive fallback with nothing latched to
    // explain it, which must not fall through to SLEEP_LENGTH's "X h of sleep" promise.
    val subtitle = when {
        alarmAlreadyRang -> NightSubtitle.ALREADY_RANG
        wakeAt == null -> NightSubtitle.UNKNOWN
        isDeadlineOnly -> NightSubtitle.DEADLINE_ONLY
        else -> NightSubtitle.SLEEP_LENGTH
    }
    // The deadline caption (09/21 review round 1's must-fix 3 decision) is suppressed only for DEADLINE_ONLY:
    // that subtitle already says the alarm rings at the deadline, and the hero number above it already IS the
    // deadline, so naming it again would be redundant. Round 2's must-fix 2 corrected the original reasoning
    // here, which also suppressed it for ALREADY_RANG on the theory that "the deadline no longer matters once
    // the night has moved past it into the already-rang state" - wrong, because ALREADY_RANG is only reachable
    // while the deadline is still ahead (a passed deadline sends the night to FINISHED first, per the engine's
    // own tick order), and that already-rang state is exactly when the owner is deciding whether to go back to
    // sleep - the one moment this caption matters most. See AUTONOMOUS_DECISIONS_09_21_2026_UI.md.
    val deadlineTimeLabel = if (subtitle == NightSubtitle.DEADLINE_ONLY) null else deadline?.let { formatClockTime(it, zone) }
    return NightScreenContent.GoingToBedOrAsleep(
        onsetPhase = if (plan.onsetIsProjected) OnsetPhase.PROJECTED else OnsetPhase.ACTUAL,
        onsetTimeLabel = formatClockTime(onset, zone),
        alarmTimeLabel = alarmTimeLabel,
        sleepLengthHoursLabel = formatSleepLengthLabel(sleepLengthFor(plan.cycles, debugOptions)),
        subtitle = subtitle,
        deadlineTimeLabel = deadlineTimeLabel,
        modeLabel = alarmModeLabel(plan, morningAlarmAt),
        // Suppressed once the alarm has already rung (09/21 review's should-fix 5): the "Already rang" subtitle
        // already carries the explanation, and the engine's own reason sentence trails off with "... alarm
        // none" once wakeAt is null, which reads as confusing two lines under a hero number showing a real time.
        reasonText = if (alarmAlreadyRang) null else plan.reason,
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
 * never disagree.
 *
 * Returns null - "nothing is armed" - when [AlarmPlan.wakeAt] is null, instead of guessing a label from
 * [AlarmPlan.mode] alone. That guess was must-fix 1 of the 09/21 review: NAP (F5, a nap alarm that already
 * fired with nothing left to arm) and FULL_CYCLES/DEADLINE_ONLY (H8, the morning alarm already rang) both
 * reach here with a null `wakeAt`, and in both cases there genuinely is no armed alarm to name - the old
 * fallback named one anyway, which on an ordinary morning (F5's case, reached every time the band confirms
 * AWAKE after the morning alarm has already fired) read as a bold, wrong "Nap alarm" seconds after the owner
 * was woken by the real one. Callers render the null case with
 * [AlarmModeHeader][com.nikita.sleepcycle.ui.components.AlarmModeHeader]'s "no alarm armed" wording instead.
 */
internal fun alarmModeLabel(plan: AlarmPlan, morningAlarmAt: Instant?): AlarmLabel? =
    plan.wakeAt?.let { alarmLabelFor(plan.mode, it, morningAlarmAt) }

/**
 * Round 2 of the 09/21 review, must-fix 1: every "no alarm armed" reading (`modeLabel == null`) on states A and
 * C co-occurs with a real armed alarm the screen was not naming - the out-of-bed nudge. `PhoneAlarmReceiver`
 * arms it unconditionally on every real (morning or nap) alarm firing, 15 minutes out, and nothing on this
 * screen's own data path knew about it: the nap-supersede path needs a non-null `wakeAt`, the ring screen's
 * Stop does not cancel it, and the pre-nudge check only cancels on a confirmed ASLEEP reading. So "No alarm
 * armed" was a guaranteed lie for the nudge's own first 15 minutes, every single time.
 *
 * Applied once, after content is built, rather than threading `pendingOutOfBedNudgeAt` into every
 * content-builder: only [NightScreenContent.GoingToBedOrAsleep] and [NightScreenContent.WokeUp] can ever reach
 * a null [modeLabel][NightScreenContent.GoingToBedOrAsleep.modeLabel] in the first place (see
 * [alarmModeLabel]'s own doc) - [NightScreenContent.NapAsleep] cannot (should-fix 5 of round 2 pins this), so
 * it is left untouched here on purpose. [pendingOutOfBedNudgeAt] must still be ahead of [now]: a stale/past
 * instant (the nudge already fired, or the read raced a cancel) must not resurrect a dead alarm on screen.
 */
internal fun applyPendingOutOfBedNudge(content: NightScreenContent, pendingOutOfBedNudgeAt: Instant?, now: Instant, zone: ZoneId): NightScreenContent {
    if (pendingOutOfBedNudgeAt == null || !pendingOutOfBedNudgeAt.isAfter(now)) return content
    val timeLabel = formatClockTime(pendingOutOfBedNudgeAt, zone)
    return when {
        content is NightScreenContent.GoingToBedOrAsleep && content.modeLabel == null ->
            content.copy(modeLabel = AlarmLabel.OUT_OF_BED, modeLabelTimeLabel = timeLabel)
        content is NightScreenContent.WokeUp && content.modeLabel == null ->
            content.copy(modeLabel = AlarmLabel.OUT_OF_BED, modeLabelTimeLabel = timeLabel)
        else -> content
    }
}
