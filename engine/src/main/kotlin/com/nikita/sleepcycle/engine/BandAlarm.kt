package com.nikita.sleepcycle.engine

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The band alarm for this round: a concrete [Scheduled] time and mode, or [Finished] (no more alarm tonight).
 * `overdueSince` is the missed band alarm that started the current overdue period (spec step 2, "Overdue rule");
 * present on [Scheduled] only when `mode` is [AlarmMode.OVERDUE], and on [Finished] only when the overdue cap
 * (`EngineConfig.maxOverdueDuration`) is what ended the night, so `describePlan` can explain why.
 */
sealed class BandAlarmResult {
    data class Scheduled(val mode: AlarmMode, val alarm: Instant, val overdueSince: Instant? = null) : BandAlarmResult()
    data class Finished(val overdueSince: Instant? = null) : BandAlarmResult()
}

/** The mode and raw alarm time [chooseMode]'s rule produces, before the overdue amendment is applied. */
private data class RawAlarm(val mode: AlarmMode, val alarm: Instant)

/**
 * Turns the [PlanRule] chosen by `chooseMode` into a concrete band alarm, then applies the overdue amendment
 * (spec step 2, "Overdue rule"): an alarm that would land before `now + minAlarmLead` becomes [AlarmMode.OVERDUE],
 * keeping the previous overdue alarm while it is still far enough ahead so a snooze does not drift; or
 * [AlarmMode.FINISHED] if even the overdue alarm would land past the deadline, or if `now` has reached
 * `EngineConfig.maxOverdueDuration` past the missed band alarm that started the overdue period.
 */
fun computeBandAlarm(
    rule: PlanRule,
    state: SleepState,
    referenceOnset: Instant,
    deadline: Instant?,
    cycles: Int,
    now: Instant,
    previousPlan: AlarmPlan?,
    config: EngineConfig
): BandAlarmResult {
    val raw = when (rule) {
        // Rule 1: the night is already over, there is no alarm to schedule.
        PlanRule.FINISHED -> return BandAlarmResult.Finished()
        // Rule 7: 20 min after falling back asleep (or after now, while still awake), never past the deadline.
        PlanRule.NAP -> RawAlarm(AlarmMode.NAP, napAlarm(state, referenceOnset, deadline, now, config))
        // Rule 5: the band vibrates exactly at the deadline.
        PlanRule.DEADLINE_ONLY -> RawAlarm(AlarmMode.DEADLINE_ONLY, deadline ?: now.plus(config.minAlarmLead))
        // Rules 3, 4, 6: the reference onset plus the whole cycles that fit.
        PlanRule.FULL_CYCLES -> RawAlarm(
            AlarmMode.FULL_CYCLES,
            referenceOnset.plus(config.cycleLength.multipliedBy(cycles.toLong()))
        )
    }
    return applyOverdueRule(raw, deadline, now, previousPlan, config)
}

/**
 * Rule 7's nap alarm: slides forward while still awake, fixed [EngineConfig.napLength] after sleep once it
 * resumes, and never later than the deadline. On a night with no deadline nothing caps the nap - the nap
 * length itself is the whole of it, and no earlier plan's alarm is allowed to shorten it.
 */
private fun napAlarm(
    state: SleepState,
    referenceOnset: Instant,
    deadline: Instant?,
    now: Instant,
    config: EngineConfig
): Instant {
    val napEnd = when (state) {
        SleepState.AWAKE -> now.plus(config.napLength)
        else -> referenceOnset.plus(config.napLength)
    }
    return if (deadline == null) napEnd else minOf(napEnd, deadline)
}

private fun applyOverdueRule(
    raw: RawAlarm,
    deadline: Instant?,
    now: Instant,
    previousPlan: AlarmPlan?,
    config: EngineConfig
): BandAlarmResult {
    val lead = now.plus(config.minAlarmLead)
    if (!raw.alarm.isBefore(lead)) return BandAlarmResult.Scheduled(raw.mode, raw.alarm)
    val overdueSince = findOverdueSince(raw, previousPlan)
    // The spec's prose says "kept while still at least minAlarmLead ahead", but its own worked example (a prior
    // OVERDUE alarm of 08:07, checked again at 08:06, stays 08:07 even though that is only 1 min ahead, then
    // moves to 08:12 once checked at 08:10) only holds if the real condition is "still in the future". Implemented
    // to match the worked example; see the engine rework report for the two readings and the numbers that disagree.
    val keptAlarm = previousPlan
        ?.takeIf { it.mode == AlarmMode.OVERDUE }
        ?.bandAlarm
        ?.takeIf { it.isAfter(now) }
    // C1: a FRESH overdue alarm (no kept one) is now + minAlarmLead rounded UP to the next whole minute - the
    // band only takes hour:minute, so a raw value with seconds left the app truncating it DOWN, which could
    // drop up to 59 s and put the target less than minAlarmLead away by the time it actually sent the command.
    val overdueAlarm = keptAlarm ?: ceilToWholeMinute(lead)
    val capReached = !now.isBefore(overdueSince.plus(config.maxOverdueDuration))
    val pastDeadline = deadline != null && overdueAlarm.isAfter(deadline)
    return if (capReached || pastDeadline) {
        BandAlarmResult.Finished(overdueSince)
    } else {
        BandAlarmResult.Scheduled(AlarmMode.OVERDUE, overdueAlarm, overdueSince)
    }
}

/** C1: rounds [instant] up to the next whole minute; an instant already exactly on a minute boundary is returned unchanged. */
private fun ceilToWholeMinute(instant: Instant): Instant {
    val truncated = instant.truncatedTo(ChronoUnit.MINUTES)
    return if (truncated == instant) instant else truncated.plus(Duration.ofMinutes(1))
}

/**
 * The instant the current overdue period started (spec step 2, "Overdue rule"): carried forward from
 * [AlarmPlan.overdueSince] while the previous plan was already [AlarmMode.OVERDUE], otherwise the last
 * non-overdue plan's band alarm (the alarm that was missed). On the very first plan ever computed, with no
 * history to carry, this round's own raw alarm stands in for "the missed band alarm".
 */
private fun findOverdueSince(raw: RawAlarm, previousPlan: AlarmPlan?): Instant = when {
    previousPlan?.mode == AlarmMode.OVERDUE -> previousPlan.overdueSince ?: previousPlan.bandAlarm ?: raw.alarm
    else -> previousPlan?.bandAlarm ?: raw.alarm
}

/**
 * The phone backup alarm. With a deadline it always equals the deadline, in every mode. Without one: the band
 * alarm plus the offset in [AlarmMode.FULL_CYCLES] and [AlarmMode.NAP] when enabled; the previous plan's phone
 * alarm is kept in [AlarmMode.OVERDUE] and [AlarmMode.FINISHED] so the snooze cannot drag it along; otherwise null.
 */
fun computePhoneAlarm(
    deadline: Instant?,
    phoneBackupEnabled: Boolean,
    mode: AlarmMode,
    bandAlarm: Instant?,
    previousPlan: AlarmPlan?,
    config: EngineConfig
): Instant? = when {
    deadline != null -> deadline
    mode == AlarmMode.OVERDUE || mode == AlarmMode.FINISHED -> previousPlan?.phoneAlarm
    phoneBackupEnabled && bandAlarm != null -> bandAlarm.plus(config.phoneBackupOffset)
    else -> null
}
