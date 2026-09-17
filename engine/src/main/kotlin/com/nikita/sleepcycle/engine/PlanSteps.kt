package com.nikita.sleepcycle.engine

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
 * What rule 7's "the planned alarm" means (spec step 2, "Wake boundary"): the deadline when there is one,
 * otherwise the band alarm of the latest [AlarmMode.FULL_CYCLES] plan, carried forward through every other mode.
 * Null before a [AlarmMode.FULL_CYCLES] plan has ever been made without a deadline. This is the carried-forward
 * value going into the round; `computeAlarmPlan` refreshes it to this round's own alarm afterward when the round
 * itself lands on `FULL_CYCLES`.
 */
fun findWakeBoundary(deadline: Instant?, previousPlan: AlarmPlan?): Instant? = deadline ?: previousPlan?.wakeBoundary

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
    referenceOnset: Instant,
    wakeBoundary: Instant?,
    now: Instant,
    previousPlan: AlarmPlan?,
    config: EngineConfig
): PlanRule = when {
    // Rule 1: the deadline has passed, or the band shows AWAKE at or after the previous band alarm.
    isPastDeadline(deadline, now) || isAwokenAtAlarm(state, previousPlan, now) -> PlanRule.FINISHED
    // Rule 7: returning to sleep (or still lying awake) with less than one cycle left before the wake boundary.
    isNapEligible(afterAwakening, referenceOnset, wakeBoundary, config) -> PlanRule.NAP
    // Rule 5: a deadline exists, no whole cycle fits before it, and this is not a return to sleep.
    deadline != null && cycles == 0 && !afterAwakening -> PlanRule.DEADLINE_ONLY
    // Rules 3, 4, 6: the normal case, whole cycles counted from the reference onset.
    else -> PlanRule.FULL_CYCLES
}

private fun isPastDeadline(deadline: Instant?, now: Instant): Boolean = deadline != null && !deadline.isAfter(now)

private fun isAwokenAtAlarm(state: SleepState, previousPlan: AlarmPlan?, now: Instant): Boolean =
    state == SleepState.AWAKE && previousPlan?.bandAlarm?.let { !it.isAfter(now) } == true

private fun isNapEligible(
    afterAwakening: Boolean,
    referenceOnset: Instant,
    wakeBoundary: Instant?,
    config: EngineConfig
): Boolean = afterAwakening && wakeBoundary != null && referenceOnset.plus(config.cycleLength).isAfter(wakeBoundary)
