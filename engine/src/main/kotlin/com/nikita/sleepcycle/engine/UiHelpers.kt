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

/** Every cycle-end wake time from [referenceOnset] up to the picker, dropping anything after the deadline (rule 4). */
fun listWakeOptions(referenceOnset: Instant, settings: NightSettings, config: EngineConfig): List<WakeOption> {
    validateConfig(config)
    validateSettings(settings, config)
    return (1..settings.pickedCycles).mapNotNull { cycles ->
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
 * cadence applies in [AlarmMode.NAP] and [AlarmMode.OVERDUE], or whenever the band alarm is within
 * [EngineConfig.nearAlarmSyncWindow] of `now`; the normal cadence applies otherwise. Never zero, never negative.
 */
fun nextSyncDelay(plan: AlarmPlan, now: Instant, config: EngineConfig): Duration? {
    validateConfig(config)
    if (plan.mode == AlarmMode.FINISHED) return null
    val nearAlarm = plan.bandAlarm?.let { Duration.between(now, it).abs() <= config.nearAlarmSyncWindow } ?: false
    return if (plan.mode == AlarmMode.NAP || plan.mode == AlarmMode.OVERDUE || nearAlarm) {
        config.frequentSyncDelay
    } else {
        config.normalSyncDelay
    }
}
