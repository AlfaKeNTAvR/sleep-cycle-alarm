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
    previousPlan: AlarmPlan?,
    zone: ZoneId,
    config: EngineConfig
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
        state, afterAwakening, settings.deadline, cycles, owedCycles, reference.onset, now, previousPlan, config
    )
    val outcome = computeBandAlarm(
        rule, state, reference.onset, settings.deadline, cycles, now, previousPlan, config
    )
    val mode = modeOf(outcome)
    val bandAlarm = alarmOf(outcome)
    val outcomeOverdueSince = overdueSinceOf(outcome)
    val overdueSince = if (mode == AlarmMode.OVERDUE) outcomeOverdueSince else null
    val phoneAlarm = computePhoneAlarm(
        settings.deadline, settings.phoneBackupEnabled, mode, bandAlarm, previousPlan, config
    )

    val finished = mode == AlarmMode.FINISHED
    val referenceOnset = if (finished) null else reference.onset
    val onsetIsProjected = if (finished) false else reference.projected
    // The overdue cap (EngineConfig.maxOverdueDuration), not the deadline or an awake mark, is what ended the
    // night: `describePlan` needs this to explain the FINISHED verdict correctly.
    val overdueCapReached = finished && outcomeOverdueSince != null &&
        !now.isBefore(outcomeOverdueSince.plus(config.maxOverdueDuration))

    val reason = describePlan(
        mode, reference, settings, cycles, bandAlarm, zone, outcomeOverdueSince, overdueCapReached, sleptSoFar
    )
    return AlarmPlan(
        mode, bandAlarm, phoneAlarm, cycles, referenceOnset, onsetIsProjected, reason, overdueSince,
        sleptSoFar, owedCycles
    )
}

private fun modeOf(outcome: BandAlarmResult): AlarmMode = when (outcome) {
    is BandAlarmResult.Finished -> AlarmMode.FINISHED
    is BandAlarmResult.Scheduled -> outcome.mode
}

private fun alarmOf(outcome: BandAlarmResult): Instant? = when (outcome) {
    is BandAlarmResult.Finished -> null
    is BandAlarmResult.Scheduled -> outcome.alarm
}

/** The missed band alarm that started the overdue period, if [outcome] carries one (spec step 2, "Overdue rule"). */
private fun overdueSinceOf(outcome: BandAlarmResult): Instant? = when (outcome) {
    is BandAlarmResult.Finished -> outcome.overdueSince
    is BandAlarmResult.Scheduled -> outcome.overdueSince
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
    bandAlarm: Instant?,
    zone: ZoneId,
    overdueSince: Instant? = null,
    overdueCapReached: Boolean = false,
    sleptSoFar: Duration = Duration.ZERO
): String {
    val alarmText = bandAlarm?.let { formatTime(it, zone) } ?: "none"
    val onsetLabel = if (reference.projected) "Estimated asleep at" else "Asleep since"
    return when (mode) {
        // Rule 1 (deadline passed / woken at alarm) or the overdue cap amendment (still asleep too long past
        // the missed alarm): both end the night, so the sentence names whichever one actually happened.
        AlarmMode.FINISHED -> {
            val deadlineText = settings.deadline?.let { ", deadline was ${formatTime(it, zone)}" } ?: ""
            if (overdueCapReached && overdueSince != null) {
                "Still asleep long after the missed alarm at ${formatTime(overdueSince, zone)}, giving up for the night$deadlineText."
            } else {
                "Night finished$deadlineText."
            }
        }
        // Rules 3, 4, 6: onset plus the whole cycles still owed of the night's total that also fit.
        AlarmMode.FULL_CYCLES -> {
            val onsetText = "$onsetLabel ${formatTime(reference.onset, zone)}"
            val deadlineText = settings.deadline?.let { " before ${formatTime(it, zone)}" } ?: ""
            if (sleptSoFar.isZero) {
                "$onsetText, $cycles of ${settings.pickedCycles} picked cycles fit$deadlineText, band alarm $alarmText."
            } else {
                val fittingText = settings.deadline?.let { ", fitting before ${formatTime(it, zone)}" } ?: ""
                "$onsetText, $cycles of ${settings.pickedCycles} picked cycles still owed after " +
                    "${formatSleepDuration(sleptSoFar)} slept tonight$fittingText, band alarm $alarmText."
            }
        }
        // Rule 5: no whole cycle fits before the deadline, so the band vibrates at the deadline.
        AlarmMode.DEADLINE_ONLY -> "No full cycle fits before the deadline, band alarm $alarmText."
        // Rule 7: a short nap after an awakening, capped by the deadline when there is one.
        AlarmMode.NAP -> {
            val boundaryText = settings.deadline?.let { ", capped at ${formatTime(it, zone)}" } ?: ""
            val sleptText = if (sleptSoFar.isZero) "" else ", ${formatSleepDuration(sleptSoFar)} slept tonight"
            "Nap mode, $onsetLabel ${formatTime(reference.onset, zone)}$sleptText, band alarm $alarmText$boundaryText."
        }
        // Overdue amendment: still asleep past the planned alarm, buzzing again shortly after this sync.
        AlarmMode.OVERDUE -> "Still asleep past the planned alarm, band alarm $alarmText."
    }
}

/** Formats a slept-so-far total as `3 h 5 min` for the night log. Whole hours and minutes only, no locale-dependent decimal separator. */
private fun formatSleepDuration(duration: Duration): String = "${duration.toHours()} h ${duration.toMinutes() % 60} min"

/** Formats an instant as `HH:mm` in [zone], for the night log and the UI. */
internal fun formatTime(time: Instant, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(time)
