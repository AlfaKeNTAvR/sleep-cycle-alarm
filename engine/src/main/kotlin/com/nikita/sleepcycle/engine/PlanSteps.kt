package com.nikita.sleepcycle.engine

import java.time.Duration
import java.time.Instant

/** The onset [computeAlarmPlan] measures cycles from, and whether it is an actual mark or a projection from `now`. */
data class ReferenceOnset(val onset: Instant, val projected: Boolean)

/**
 * The onset cycles are measured from (spec step 2, "Reference onset"). `ASLEEP`: the latest stretch's actual
 * onset, not projected. Otherwise a projection, `now + fallAsleepEstimate`.
 */
fun findReferenceOnset(
    state: SleepState,
    stretches: List<SleepStretch>,
    now: Instant,
    config: EngineConfig
): ReferenceOnset {
    val latest = stretches.lastOrNull()
    return if (state == SleepState.ASLEEP && latest != null) {
        ReferenceOnset(latest.onset, projected = false)
    } else {
        ReferenceOnset(now.plus(config.fallAsleepEstimate), projected = true)
    }
}

/**
 * The sleep already had tonight that rule 2's night-total budget is measured against (spec step 2, "Cycles
 * still owed"): the summed length of every [SleepStretch], EXCLUDING the one currently in progress while
 * [state] is [SleepState.ASLEEP]. The current stretch is the one the new alarm is measured from, so counting
 * it here would subtract it twice. Zero before any sleep at all, and zero on the first stretch of the night,
 * which is what keeps that first stretch behaving exactly as it did before the total rule existed.
 */
fun sumSleepAlreadyHad(stretches: List<SleepStretch>, state: SleepState): Duration {
    val completed = if (state == SleepState.ASLEEP) stretches.dropLast(1) else stretches
    return completed.fold(Duration.ZERO) { total, stretch -> total.plus(Duration.between(stretch.onset, stretch.end)) }
}

/**
 * Whole cycles still owed of the night's total (spec step 2, "Cycles still owed"): the picked budget
 * (`pickedCycles * cycleLength`) minus [sleptSoFar], never below zero, divided by one cycle and rounded to the
 * NEAREST whole number - so a remainder that does not divide evenly lands as close to the picked total as
 * whole cycles allow, rather than always cutting one short. Exactly half a cycle rounds up.
 */
fun countOwedCycles(sleptSoFar: Duration, settings: NightSettings, config: EngineConfig): Int {
    val budget = config.cycleLength.multipliedBy(settings.pickedCycles.toLong())
    val remaining = budget.minus(sleptSoFar)
    if (remaining.isNegative || remaining.isZero) return 0
    return Math.round(remaining.toNanos().toDouble() / config.cycleLength.toNanos().toDouble()).toInt()
}

/** Which of rules 1, 7, 5, or 3/4/6 [chooseMode] matched, before the overdue amendment in `computeBandAlarm`. */
enum class PlanRule { FINISHED, NAP, DEADLINE_ONLY, FULL_CYCLES }

/**
 * Picks the matching rule for this round, first match wins (spec step 2, "Modes"). The overdue amendment (a rule
 * becoming [AlarmMode.OVERDUE] or [AlarmMode.FINISHED] because the alarm would land too soon) is applied
 * afterward, in `computeBandAlarm`, since it needs the alarm time this rule produces.
 */
fun chooseMode(
    state: SleepState,
    afterAwakening: Boolean,
    deadline: Instant?,
    cycles: Int,
    owedCycles: Int,
    referenceOnset: Instant,
    now: Instant,
    previousPlan: AlarmPlan?,
    config: EngineConfig
): PlanRule = when {
    // Rule 1: the deadline has passed, or the band shows AWAKE at or after the previous band alarm.
    isPastDeadline(deadline, now) || isAwokenAtAlarm(state, previousPlan, now) -> PlanRule.FINISHED
    // Rule 7: returning to sleep (or still lying awake) with less than one cycle still owed of the night's
    // total, or - only when there is a deadline - less than one cycle left before it.
    isNapEligible(afterAwakening, owedCycles, referenceOnset, deadline, config) -> PlanRule.NAP
    // Rule 5: a deadline exists, no whole cycle fits before it, and this is not a return to sleep.
    deadline != null && cycles == 0 && !afterAwakening -> PlanRule.DEADLINE_ONLY
    // Rules 3, 4, 6: the normal case, whole cycles counted from the reference onset.
    else -> PlanRule.FULL_CYCLES
}

private fun isPastDeadline(deadline: Instant?, now: Instant): Boolean = deadline != null && !deadline.isAfter(now)

private fun isAwokenAtAlarm(state: SleepState, previousPlan: AlarmPlan?, now: Instant): Boolean =
    state == SleepState.AWAKE && previousPlan?.bandAlarm?.let { !it.isAfter(now) } == true

/**
 * Rule 7, both of its triggers. The owed test needs no deadline at all: with the night-total rule a nap
 * applies on a night with no deadline too, once the picked total is all but used up. The second trigger, a
 * cycle no longer fitting, is a DEADLINE test and nothing else (decided 2026-09-17 with the owner): on a night
 * with no deadline the picked total is the only thing allowed to end the night, so an earlier plan's own alarm
 * must never cut it short.
 */
private fun isNapEligible(
    afterAwakening: Boolean,
    owedCycles: Int,
    referenceOnset: Instant,
    deadline: Instant?,
    config: EngineConfig
): Boolean {
    if (!afterAwakening) return false
    if (owedCycles == 0) return true
    return deadline != null && referenceOnset.plus(config.cycleLength).isAfter(deadline)
}
