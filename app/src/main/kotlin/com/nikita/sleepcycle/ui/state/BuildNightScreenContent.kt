package com.nikita.sleepcycle.ui.state

// File purpose: pure derivation of each night-screen content variant from one AlarmPlan and NightEngineView.
// Split from BuildNightUiState.kt to keep each file small. N1 collapsed the three live-night variants into
// one - see NightScreenContent.NextAlarm's own doc - so what remains here is that one builder plus the two
// dead-end states (FINISHED, the morning report).

import com.nikita.sleepcycle.alarm.AlarmLabel
import com.nikita.sleepcycle.alarm.alarmLabelFor
import com.nikita.sleepcycle.engine.AlarmPlan
import com.nikita.sleepcycle.night.NightEngineView
import com.nikita.sleepcycle.ui.format.formatClockTime
import com.nikita.sleepcycle.ui.format.formatCycles
import com.nikita.sleepcycle.ui.format.formatDuration
import com.nikita.sleepcycle.ui.format.formatTimeUntil
import java.time.Instant
import java.time.ZoneId

/**
 * Shown instead of a real time when no alarm is armed at all: [AlarmPlan.wakeAt] is null and no out-of-bed
 * nudge is pending either. Two cases reach it. H8's already-rang case - the morning alarm has already rung
 * while the band still reads ASLEEP - fills the line underneath with the time it rang
 * ([NightScreenContent.NextAlarm.rangAtTimeLabel]), so the dash is never the only thing on the screen there.
 * The other is the defensive fallback that remains once that is ruled out: `wakeAt` null with no
 * [com.nikita.sleepcycle.night.NightState.morningAlarmAt] latched to explain it either, a partially restored
 * state file rather than a state a normal night reaches.
 *
 * N1 changed what this stands for. It used to mean "the hero number is missing", with the already-rang case
 * showing the rung time in its place; now the hero number always answers one question - when does the next
 * alarm ring - so a past ring belongs on the line underneath, not in the hero's own slot where it reads as an
 * alarm that is still coming.
 */
const val MISSING_TIME_LABEL = "--:--"

/**
 * N1: the whole of a live night, in the one shape every state of it now uses - which alarm rings next, when,
 * and how long until then. [morningAlarmAt] is NightState's own latched morning-alarm time (H1), needed both
 * for [alarmModeLabel]'s own H8 disambiguation and for the already-rang line. [now] is what the countdown
 * counts from, so the caller's clock (virtual on a simulated night - see AppClock.kt) is the one that decides
 * it, not a second reading taken here.
 *
 * The plan's [AlarmPlan.referenceOnset], its cycle count, the night's deadline and the sleep state all used to
 * feed this screen and no longer do: see [NightScreenContent.NextAlarm]'s own doc for what was dropped and why
 * none of it moved elsewhere.
 */
fun buildNextAlarmContent(plan: AlarmPlan, zone: ZoneId, morningAlarmAt: Instant?, now: Instant): NightScreenContent.NextAlarm {
    val wakeAt = plan.wakeAt
    // H8: wakeAt is null because the morning alarm already rang while the band still reads ASLEEP, and
    // morningAlarmAt is that same alarm's own latched time (H1) - the one thing worth saying on a screen that
    // otherwise has no alarm to count down to.
    val alarmAlreadyRang = wakeAt == null && morningAlarmAt != null
    return NightScreenContent.NextAlarm(
        modeLabel = alarmModeLabel(plan, morningAlarmAt),
        alarmTimeLabel = wakeAt?.let { formatClockTime(it, zone) } ?: MISSING_TIME_LABEL,
        countdownLabel = wakeAt?.let { formatTimeUntil(it, now) },
        rangAtTimeLabel = if (alarmAlreadyRang) formatClockTime(checkNotNull(morningAlarmAt), zone) else null,
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
 * was woken by the real one. Callers render the null case as the "no alarm armed" wording instead.
 */
internal fun alarmModeLabel(plan: AlarmPlan, morningAlarmAt: Instant?): AlarmLabel? =
    plan.wakeAt?.let { alarmLabelFor(plan.mode, it, morningAlarmAt) }

/**
 * Round 2 of the 09/21 review, must-fix 1: every "no alarm armed" reading (`modeLabel == null`) on a live
 * night co-occurs with a real armed alarm the screen was not naming - the out-of-bed nudge. `PhoneAlarmReceiver`
 * arms it unconditionally on every real (morning or nap) alarm firing, 15 minutes out, and nothing on this
 * screen's own data path knew about it: the nap-supersede path needs a non-null `wakeAt`, the ring screen's
 * Stop does not cancel it, and the pre-nudge check only cancels when another alarm is genuinely armed and
 * still ahead (M1). So "No alarm armed" was a guaranteed lie for the nudge's own first 15 minutes, every
 * single time.
 *
 * Round 3 of the 09/21 review, should-fix 1: a null `modeLabel` was the ONLY trigger, so the header stayed
 * wrong whenever a plan alarm was ALSO armed and happened to ring later than the nudge - e.g. the morning
 * alarm rings at 03:30, the owner stays in bed, the plan becomes DEADLINE_ONLY at 07:30 while the nudge armed
 * by that same ring is due at 03:45: the header kept showing "Morning alarm / 07:30" for those 15 minutes even
 * though the nudge, not the 07:30 alarm, is what actually rings next. [planWakeAt] (the plan's own armed alarm
 * instant, `AlarmPlan.wakeAt`) overrides whenever it is null (the old condition, unchanged) OR later than
 * [pendingOutOfBedNudgeAt] - i.e. whenever the nudge is the one that actually rings first.
 *
 * N1 makes that override total rather than cosmetic. The nudge used to replace only the name above the hero
 * number, leaving the hero itself showing the plan's own later alarm; now the hero number IS the next alarm,
 * so winning the "rings first" test means the nudge owns the time and the countdown too. `rangAtTimeLabel` is
 * cleared for the same reason: with a live countdown to show, a past ring is no longer what that line is for.
 *
 * Applied once, after content is built, rather than threading `pendingOutOfBedNudgeAt` into the builder, so
 * the nudge (which lives in its own store, read by the caller as disk I/O - see `OutOfBedNudgeStore.kt`) stays
 * out of the plan-shaped path. [pendingOutOfBedNudgeAt] must still be ahead of [now]: a stale/past instant
 * (the nudge already fired, or the read raced a cancel) must not resurrect a dead alarm on screen.
 */
internal fun applyPendingOutOfBedNudge(content: NightScreenContent, pendingOutOfBedNudgeAt: Instant?, planWakeAt: Instant?, now: Instant, zone: ZoneId): NightScreenContent {
    if (content !is NightScreenContent.NextAlarm) return content
    if (pendingOutOfBedNudgeAt == null || !pendingOutOfBedNudgeAt.isAfter(now)) return content
    val nudgeRingsFirst = planWakeAt == null || planWakeAt.isAfter(pendingOutOfBedNudgeAt)
    if (!nudgeRingsFirst) return content
    return content.copy(
        modeLabel = AlarmLabel.OUT_OF_BED,
        alarmTimeLabel = formatClockTime(pendingOutOfBedNudgeAt, zone),
        countdownLabel = formatTimeUntil(pendingOutOfBedNudgeAt, now),
        rangAtTimeLabel = null,
    )
}
