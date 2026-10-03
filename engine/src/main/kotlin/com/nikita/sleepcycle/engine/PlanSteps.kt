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
 * (`pickedCycles * cycleLength`) minus [sleptSoFar], less [EngineConfig.owedCycleForgiveness], ROUNDED UP to
 * whole cycles, never below zero.
 *
 * Owner decision, 2026-10-03 (phone night 2026-10-03: 40 min owed rounded to 0, so a 20 min nap alarm woke him
 * mid-cycle at 08:48): rounding up gives at least the picked sleep and ends on a cycle boundary; a deadline,
 * where one is set, still cuts the count down to what fits ([capCyclesByDeadline]). The forgiveness keeps a few
 * minutes over whole cycles from costing a whole extra cycle. Supersedes the 2026-09-17 nearest-whole-cycle rule.
 */
fun countOwedCycles(sleptSoFar: Duration, settings: NightSettings, config: EngineConfig): Int {
    val budget = config.cycleLength.multipliedBy(settings.pickedCycles.toLong())
    val remaining = budget.minus(sleptSoFar).minus(config.owedCycleForgiveness)
    if (remaining.isNegative || remaining.isZero) return 0
    val cycleNanos = config.cycleLength.toNanos()
    return ((remaining.toNanos() + cycleNanos - 1) / cycleNanos).toInt()
}

/** Which of rules 1, 7, 5, or 3/4/6 [chooseMode] matched, before the pull-forward amendment in `computeWakeAlarm`. */
enum class PlanRule { FINISHED, NAP, DEADLINE_ONLY, FULL_CYCLES }

/**
 * D5/G8: at most this many engine nap alarms may FIRE in a night, pre-wake (rule 7) or after the wake alarm
 * alike; the next new return to sleep is then refused a nap (FINISHED with no deadline ahead, DEADLINE_ONLY
 * with one). Counted at fire time (NightState.napAlarmsUsed). The owner's own Nap button (P3) rings on the
 * out-of-bed slot and is never counted.
 */
const val MAX_NAP_ALARMS = 2

/**
 * Owner decision, 2026-10-03: how long past the deadline a night whose morning alarm never rang still keeps
 * trying to ring it (rule 1, [chooseMode]). Past this the night ends silently: a ring hours after the wake-by
 * time would belong to a morning that is already over.
 */
val DEADLINE_RECOVERY_WINDOW: Duration = Duration.ofHours(1)

/**
 * Picks the matching rule for this round, first match wins (spec step 2, "Modes"). The pull-forward amendment
 * (an alarm landing too soon moving to `now + minAlarmLead`, capped by the deadline) is applied afterward, in
 * `computeWakeAlarm`, since it needs the alarm time this rule produces. It never changes the mode (D8).
 *
 * D3: band-detected wake (state AWAKE at or after the previous alarm) no longer ends the night by itself -
 * only the owner's own confirmation does that, entirely at the app layer (NightController.endNight), before
 * the engine is ever consulted again, so nothing here tests for it. The night now ends only via the deadline
 * (rule 1, unchanged) or the D5/G8 nap cap below.
 */
fun chooseMode(
    state: SleepState,
    afterAwakening: Boolean,
    deadline: Instant?,
    cycles: Int,
    owedCycles: Int,
    referenceOnset: Instant,
    now: Instant,
    config: EngineConfig,
    napAlarmsUsed: Int,
    /** Owner decision, 2026-10-03: whether the night's morning alarm has rung (WakeAlarm.kt's `morningAlarmHasRung`) - rule 1 ends the night only once it has. */
    morningAlarmRang: Boolean
): PlanRule = when {
    // Rule 1: the deadline has passed. Owner decision, 2026-10-03 (deadline.md #1: the phone off or its alarms
    // wiped, the night ended at the first tick after the deadline with nothing ever rung): FINISHED only once the
    // morning alarm has rung, or once the deadline is more than DEADLINE_RECOVERY_WINDOW behind. Until then the
    // deadline alarm still rings - DEADLINE_ONLY, whose past target computeWakeAlarm pulls forward to
    // now + minAlarmLead, uncapped by a deadline already behind now.
    isPastDeadline(deadline, now) ->
        if (morningAlarmRang || isDeadlineLongPast(checkNotNull(deadline), now)) PlanRule.FINISHED else PlanRule.DEADLINE_ONLY
    // D5/F4/G8 SUPERSEDES the original spec: the nap cap is already spent, and this is a genuinely NEW return
    // to sleep - see isPostWakeNapCapSpent. isPastDeadline above already ruled out a deadline at or before now,
    // so a deadline reaching here is still ahead of us, and it outranks the spent cap: the alarm rings at the
    // deadline (DEADLINE_ONLY) instead of the night ending outright. FINISHED by a spent cap happens only when
    // there is no deadline left to fall back on.
    isPostWakeNapCapSpent(state, afterAwakening, napAlarmsUsed) ->
        if (deadline != null) PlanRule.DEADLINE_ONLY else PlanRule.FINISHED
    // Rule 7: returning to sleep (or still lying awake) with less than one cycle still owed of the night's
    // total, or - only when there is a deadline - less than one cycle left before it. Untouched by D5/G8: once
    // the picked total is used up, owedCycles is already 0 by the time a return to sleep can happen at all (a
    // deadline-forced alarm is already FINISHED by rule 1 above before it can ever fire), so this same branch
    // is what actually arms a D5/G8 nap too - the cap above is the only thing added on its behalf, and it never
    // changes which naps this branch itself offers (G8).
    isNapEligible(afterAwakening, owedCycles, referenceOnset, deadline, config) -> PlanRule.NAP
    // Rule 5: a deadline exists, no whole cycle fits before it, and this is not a return to sleep.
    deadline != null && cycles == 0 && !afterAwakening -> PlanRule.DEADLINE_ONLY
    // Rules 3, 4, 6: the normal case, whole cycles counted from the reference onset.
    else -> PlanRule.FULL_CYCLES
}

private fun isPastDeadline(deadline: Instant?, now: Instant): Boolean = deadline != null && !deadline.isAfter(now)

/** Owner decision, 2026-10-03: the deadline is more than [DEADLINE_RECOVERY_WINDOW] behind [now] - see [chooseMode]'s rule 1. */
private fun isDeadlineLongPast(deadline: Instant, now: Instant): Boolean = now.isAfter(deadline.plus(DEADLINE_RECOVERY_WINDOW))

/**
 * G2/G8 SUPERSEDE both the original spec's "incremented when armed" wording and this check's own earlier
 * guards: once [MAX_NAP_ALARMS] nap alarms have actually FIRED this night (NightState.napAlarmsUsed,
 * incremented by the app layer at FIRE time, never at arm time - see NightOrchestrator/PhoneAlarmReceiver), a
 * genuinely new return to sleep (state ASLEEP, afterAwakening) ends the night outright instead of arming
 * another nap.
 *
 * G2: the `referenceOnset != previousPlan.referenceOnset` re-check that used to gate this is gone. It existed
 * only to stop arm-time counting from cancelling the very nap it had just counted - once counting moved to
 * fire time (F2), that reason disappeared: napAlarmsUsed only advances when a nap alarm FIRES, so a nap that
 * is still pending (armed, not yet rung) can never itself be the thing that pushes the count to the cap. By
 * the time the cap is reached, the nap that reached it has already rung, so the very next ASLEEP+afterAwakening
 * tick is unambiguously a new return to sleep - keeping the guard only defeated the cap on a night where the
 * owner never has a further awakening for it to re-check against (the exact case it exists for).
 *
 * G8: no longer reads wakeAlarmFiredAt at all - the cap counts every nap alarm that fires, pre-wake (rule 7)
 * or post-wake alike, whether or not the main wake alarm has ever rung this night. Only whether the owner is
 * asleep again after an awakening decides when the cap can bite: `state == AWAKE` (e.g. the window right after
 * a nap alarm fires, before the owner falls back asleep) never triggers it - see PostWakeNapTest's `thirdAwake`
 * cases, which rely on exactly this to keep arming nothing rather than forcing FINISHED.
 */
private fun isPostWakeNapCapSpent(
    state: SleepState,
    afterAwakening: Boolean,
    napAlarmsUsed: Int
): Boolean {
    if (state != SleepState.ASLEEP || !afterAwakening) return false
    return napAlarmsUsed >= MAX_NAP_ALARMS
}

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
