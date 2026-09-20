package com.nikita.sleepcycle.engine

import java.time.Duration
import java.time.Instant
import kotlin.math.round

/** True when picking [cycles] can still finish by [deadline] from `now`, given the fall-asleep estimate (rule 2). */
fun isSleepLengthAvailable(cycles: Int, now: Instant, deadline: Instant?, config: EngineConfig): Boolean {
    validateConfig(config)
    require(cycles > 0) { "cycles must be positive" }
    val wakeIfPicked = now.plus(config.fallAsleepEstimate).plus(config.cycleLength.multipliedBy(cycles.toLong()))
    return deadline == null || !wakeIfPicked.isAfter(deadline)
}

/**
 * Every cycle-end wake time from [referenceOnset] up to [upToCycles], dropping anything after the deadline
 * (rule 4). [upToCycles] defaults to the whole picked length, which is what a night that has not slept
 * anything yet is still owed; once sleep has been had, the caller passes what the plan actually still owes
 * (`plan.cycles`), so the timeline can never offer a night the engine is no longer planning. It is a plain
 * count, not a picker value, so - unlike [NightSettings.pickedCycles] - it may be any number from 0 up.
 */
fun listWakeOptions(
    referenceOnset: Instant,
    settings: NightSettings,
    config: EngineConfig,
    upToCycles: Int = settings.pickedCycles
): List<WakeOption> {
    validateConfig(config)
    validateSettings(settings, config)
    require(upToCycles >= 0) { "upToCycles must not be negative" }
    return (1..upToCycles).mapNotNull { cycles ->
        val duration = config.cycleLength.multipliedBy(cycles.toLong())
        val wake = referenceOnset.plus(duration)
        if (settings.deadline == null || !wake.isAfter(settings.deadline)) WakeOption(cycles, wake, duration) else null
    }
}

/**
 * Total retained sleep, plus each stretch's span and cycle-equivalent rounded to one decimal, for the
 * morning report.
 */
fun summarizeNight(stretches: List<SleepStretch>, config: EngineConfig): NightSummary {
    validateConfig(config)
    val summaries = stretches.filter { it.end.isAfter(it.onset) }.map { stretch ->
        val duration = Duration.between(stretch.onset, stretch.end)
        val cycles = round(duration.toNanos().toDouble() / config.cycleLength.toNanos().toDouble() * 10.0) / 10.0
        StretchSummary(stretch.onset, stretch.end, duration, cycles)
    }
    val total = summaries.fold(Duration.ZERO) { sum, summary -> sum.plus(summary.duration) }
    return NightSummary(total, summaries)
}

/**
 * How long to wait before the next sync. Null (stop syncing) once the night is [AlarmMode.FINISHED]. The frequent
 * cadence applies in [AlarmMode.NAP], or whenever the phone alarm is within [EngineConfig.nearAlarmSyncWindow] of
 * `now`; the normal cadence applies otherwise. Never zero, never negative.
 */
fun nextSyncDelay(plan: AlarmPlan, now: Instant, config: EngineConfig): Duration? {
    validateConfig(config)
    if (plan.mode == AlarmMode.FINISHED) return null
    val nearAlarm = plan.wakeAt?.let { Duration.between(now, it).abs() <= config.nearAlarmSyncWindow } ?: false
    return if (plan.mode == AlarmMode.NAP || nearAlarm) {
        config.frequentSyncDelay
    } else {
        config.normalSyncDelay
    }
}
