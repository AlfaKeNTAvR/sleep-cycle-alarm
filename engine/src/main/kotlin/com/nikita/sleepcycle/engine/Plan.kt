package com.nikita.sleepcycle.engine

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The whole night's decision for one sync (spec step 2). Pure and total: never throws for any `segments`,
 * `settings`, or `now` (only a nonsensical [EngineConfig] or an out-of-picker `pickedCycles` do, via
 * [validateConfig] / [validateSettings]). Safe to call every 5-15 min all night, including after the alarm and
 * after the deadline: reads like the spec, one named step at a time.
 */
fun computeAlarmPlan(
    segments: List<SleepSegment>,
    settings: NightSettings,
    now: Instant,
    /**
     * H1 SUPERSEDES the earlier `previousPlan?.wakeAt` reading: the night's own morning alarm time, LATCHED
     * by the app layer (NightState.morningAlarmAt) from whichever tick last produced a [AlarmMode.FULL_CYCLES]
     * or [AlarmMode.DEADLINE_ONLY] plan, and never overwritten by a [AlarmMode.NAP] plan's own slid `wakeAt`.
     * Null before the first such plan exists. `previousPlan?.wakeAt` looked like the same fact but was not: in
     * the sliding-AWAKE-nap case it IS the slid nap's own value (`now + napLength` from the tick before), which
     * is always ahead of `now` by construction, so a guard built on it could never fire - see WakeAlarm.kt's
     * `napAlarm` for where this is actually used.
     */
    morningAlarmAt: Instant?,
    zone: ZoneId,
    config: EngineConfig,
    /**
     * D5/F6: the MAIN wake alarm's fired instant (NightState.wakeAlarmFiredAt), null until it fires this
     * night. Never a mid-night rule 7 nap's own firing - only the wake alarm itself sets this, so rule 7's
     * mid-night nap can never switch on the D5 cap machinery by itself.
     */
    wakeAlarmFiredAt: Instant?,
    /**
     * F2 SUPERSEDES the original spec: how many post-wake nap ALARMS have actually FIRED this night
     * (NightState.napAlarmsUsed), capped at [MAX_NAP_ALARMS] - not how many have been armed. Armed-but-not-yet-
     * fired naps do not count, so retargeting or pull-forward while one is still pending can never inflate it.
     */
    napAlarmsUsed: Int,
    /**
     * H2: the most recent nap alarm's own fired instant, mid-night (rule 7) or post-wake (D5) alike - null
     * until the first nap alarm ever fires this night. Lets [computeWakeAlarm]'s ASLEEP branch tell "a nap
     * alarm already rang for THIS stretch, the owner slept through it" (compare against `referenceOnset`) apart
     * from "this target just happens to be overdue" (a nap detected late, or an alarm computed in the past
     * after a reboot) - only the first case must skip the ordinary 2-minute pull-forward for a genuinely fresh
     * napLength instead. See WakeAlarm.kt's own doc.
     */
    lastNapAlarmFiredAt: Instant?,
    /**
     * J1.3 (owner-reported, 2026-09-21): NightState.phoneAlarmFiredFor, the last phone alarm instant that
     * fired at all (wake, nap, or a rule 7 mid-night nap alike) - null until the first firing this night.
     * Written unconditionally by PhoneAlarmReceiver.markPhoneAlarmFired, even on the one race where the SAME
     * firing's attribution to [wakeAlarmFiredAt] gets skipped (a stale `state.lastPlan` read beating the
     * in-flight tick's own save to disk) - see WakeAlarm.kt's own `morningAlarmAlreadyRang` for why a spent
     * target must check both markers, not [wakeAlarmFiredAt] alone.
     */
    phoneAlarmFiredFor: Instant?
): AlarmPlan {
    validateConfig(config)
    validateSettings(settings, config)

    // Step 1: one clean timeline.
    val timeline = normalizeSegments(segments, now, config)
    val stretches = buildSleepStretches(timeline)
    val state = detectSleepState(timeline)
    val afterAwakening = isAfterAwakening(state, stretches)

    // Step 2: the plan.
    val reference = findReferenceOnset(state, stretches, now, config)
    // Rule 2 as a night total: what is still owed once the sleep already had is subtracted, then capped by
    // what still fits before the deadline. `sleptSoFar` deliberately excludes the stretch the new alarm is
    // measured from, so that stretch is never subtracted twice.
    val sleptSoFar = sumSleepAlreadyHad(stretches, state)
    val owedCycles = countOwedCycles(sleptSoFar, settings, config)
    val cycles = capCyclesByDeadline(owedCycles, reference.onset, settings, config)

    val rule = chooseMode(
        state, afterAwakening, settings.deadline, cycles, owedCycles, reference.onset, now, config, napAlarmsUsed
    )
    // H1: the latched morningAlarmAt, alongside wakeAlarmFiredAt, is what lets computeWakeAlarm's AWAKE branch
    // (rule 7's sliding nap) tell that the morning's alarm time has already passed even when no firing was
    // ever recorded for it, and tell it PERMANENTLY rather than for one tick - see WakeAlarm.kt's own doc.
    val wakeAt = computeWakeAlarm(
        rule, state, reference.onset, settings.deadline, cycles, now, config, wakeAlarmFiredAt, morningAlarmAt, lastNapAlarmFiredAt,
        phoneAlarmFiredFor
    )
    val mode = modeOf(rule)

    val finished = mode == AlarmMode.FINISHED
    val referenceOnset = if (finished) null else reference.onset
    val onsetIsProjected = if (finished) false else reference.projected

    val reason = describePlan(mode, reference, settings, cycles, wakeAt, zone, sleptSoFar)
    return AlarmPlan(mode, wakeAt, cycles, referenceOnset, onsetIsProjected, reason, sleptSoFar, owedCycles)
}

private fun modeOf(rule: PlanRule): AlarmMode = when (rule) {
    PlanRule.FINISHED -> AlarmMode.FINISHED
    PlanRule.NAP -> AlarmMode.NAP
    PlanRule.DEADLINE_ONLY -> AlarmMode.DEADLINE_ONLY
    PlanRule.FULL_CYCLES -> AlarmMode.FULL_CYCLES
}

/** Rule 6/7's "is this a return to sleep after an awakening": true in AWAKE with history, or ASLEEP after one. */
private fun isAfterAwakening(state: SleepState, stretches: List<SleepStretch>): Boolean = when (state) {
    SleepState.AWAKE -> stretches.isNotEmpty()
    SleepState.ASLEEP -> stretches.lastOrNull()?.followsAwakening ?: false
    SleepState.NOT_YET_ASLEEP -> false
}

/**
 * [owedCycles] capped by what still fits before the deadline (spec step 2): `min(owedCycles, fit)`, where
 * `fit` is the whole cycles between [referenceOnset] and the deadline. A cycle ending exactly at the deadline
 * fits. Without a deadline nothing caps the owed count, so it is returned unchanged.
 */
private fun capCyclesByDeadline(
    owedCycles: Int,
    referenceOnset: Instant,
    settings: NightSettings,
    config: EngineConfig
): Int {
    val deadline = settings.deadline ?: return owedCycles
    val available = Duration.between(referenceOnset, deadline)
    val fit = if (available.isNegative) 0 else (available.toNanos() / config.cycleLength.toNanos()).toInt()
    return minOf(owedCycles, fit)
}

/** One plain-English sentence for the night log, naming the numbers that produced [mode] (spec step 2). */
internal fun describePlan(
    mode: AlarmMode,
    reference: ReferenceOnset,
    settings: NightSettings,
    cycles: Int,
    wakeAt: Instant?,
    zone: ZoneId,
    sleptSoFar: Duration = Duration.ZERO
): String {
    val alarmText = wakeAt?.let { formatTime(it, zone) } ?: "none"
    val onsetLabel = if (reference.projected) "Estimated asleep at" else "Asleep since"
    return when (mode) {
        // Rule 1, or D5's nap cap with no deadline left to fall back on (F4: a cap spent with a deadline still
        // ahead is DEADLINE_ONLY, not FINISHED - so FINISHED with a deadline present can only mean the
        // deadline itself is what ended the night, never the cap). D3: band-detected wake no longer ends the
        // night by itself. F9: the two triggers get their own wording rather than one text silently blaming
        // the deadline for a cap-spent finish, or saying nothing at all about the cause.
        AlarmMode.FINISHED -> if (settings.deadline != null) {
            "Night finished, deadline was ${formatTime(settings.deadline, zone)}."
        } else {
            "Night finished, the $MAX_NAP_ALARMS nap alarms are used up."
        }
        // Rules 3, 4, 6: onset plus the whole cycles still owed of the night's total that also fit.
        AlarmMode.FULL_CYCLES -> {
            val onsetText = "$onsetLabel ${formatTime(reference.onset, zone)}"
            val deadlineText = settings.deadline?.let { " before ${formatTime(it, zone)}" } ?: ""
            if (sleptSoFar.isZero) {
                "$onsetText, $cycles of ${settings.pickedCycles} picked cycles fit$deadlineText, alarm $alarmText."
            } else {
                val fittingText = settings.deadline?.let { ", fitting before ${formatTime(it, zone)}" } ?: ""
                "$onsetText, $cycles of ${settings.pickedCycles} picked cycles still owed after " +
                    "${formatSleepDuration(sleptSoFar)} slept tonight$fittingText, alarm $alarmText."
            }
        }
        // Rule 5: no whole cycle fits before the deadline, so the alarm rings at the deadline.
        AlarmMode.DEADLINE_ONLY -> "No full cycle fits before the deadline, alarm $alarmText."
        // Rule 7: a short nap after an awakening, capped by the deadline when there is one.
        AlarmMode.NAP -> {
            val boundaryText = settings.deadline?.let { ", capped at ${formatTime(it, zone)}" } ?: ""
            val sleptText = if (sleptSoFar.isZero) "" else ", ${formatSleepDuration(sleptSoFar)} slept tonight"
            "Nap mode, $onsetLabel ${formatTime(reference.onset, zone)}$sleptText, alarm $alarmText$boundaryText."
        }
    }
}

/** Formats a slept-so-far total as `3 h 5 min` for the night log. Whole hours and minutes only, no locale-dependent decimal separator. */
private fun formatSleepDuration(duration: Duration): String = "${duration.toHours()} h ${duration.toMinutes() % 60} min"

/** Formats an instant as `HH:mm` in [zone], for the night log and the UI. */
internal fun formatTime(time: Instant, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(time)
