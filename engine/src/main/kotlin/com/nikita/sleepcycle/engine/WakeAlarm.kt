package com.nikita.sleepcycle.engine

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Turns the [PlanRule] chosen by `chooseMode` into a concrete wake time (spec step 2), then pulls it forward
 * when it would land in the past or closer than [EngineConfig.minAlarmLead] (D8): to `now + minAlarmLead`,
 * rounded up to the next whole minute, capped by the deadline when there is one. The pull-forward never changes
 * [rule]'s mode - there is no more OVERDUE amendment, since the phone alarm fires exactly on time and D4/D5
 * cover a missed wake-up from here. Null exactly when [rule] is [PlanRule.FINISHED].
 */
fun computeWakeAlarm(
    rule: PlanRule,
    state: SleepState,
    referenceOnset: Instant,
    deadline: Instant?,
    cycles: Int,
    now: Instant,
    config: EngineConfig,
    /** F5: the wake (or nap) alarm's fired instant (NightState.wakeAlarmFiredAt), null until the main wake alarm has fired this night. Only [napAlarm]'s AWAKE branch reads this - see its own doc. G8: this is the ONE job wakeAlarmFiredAt still has - it plays no part in the D5/G8 nap cap any more (see PlanSteps.kt's isPostWakeNapCapSpent). */
    wakeAlarmFiredAt: Instant?,
    /** H1 SUPERSEDES G3's `previousWakeAt`: the night's own morning alarm time, LATCHED by the app layer (NightState.morningAlarmAt) and never overwritten by a NAP plan's own slid `wakeAt` - see computeAlarmPlan's own doc for why the earlier "previous tick's own wakeAt" reading was unreachable. Only [napAlarm]'s AWAKE branch reads this. */
    morningAlarmAt: Instant?,
    /** H2: the most recent nap alarm's own fired instant, mid-night or post-wake alike - null until the first nap alarm ever fires this night. Only [napAlarm]'s ASLEEP branch reads this - see its own doc. */
    lastNapAlarmFiredAt: Instant?
): Instant? {
    val raw = when (rule) {
        // Rule 1: the night is already over, there is no alarm to schedule.
        PlanRule.FINISHED -> return null
        // Rule 7: 20 min after falling back asleep (or after now, while still awake), never past the deadline.
        // F5/H1: the AWAKE branch can itself decide there is nothing left to arm - see napAlarm's own doc.
        PlanRule.NAP -> napAlarm(state, referenceOnset, deadline, now, config, wakeAlarmFiredAt, morningAlarmAt, lastNapAlarmFiredAt) ?: return null
        // Rule 5: the alarm rings exactly at the deadline.
        PlanRule.DEADLINE_ONLY -> deadline ?: now.plus(config.minAlarmLead)
        // Rules 3, 4, 6: the reference onset plus the whole cycles that fit.
        PlanRule.FULL_CYCLES -> referenceOnset.plus(config.cycleLength.multipliedBy(cycles.toLong()))
    }
    return pullForwardIfTooSoon(raw, deadline, now, config)
}

/**
 * Rule 7's nap alarm: slides forward while still awake, fixed [EngineConfig.napLength] after sleep once it
 * resumes, and never later than the deadline. On a night with no deadline nothing caps the nap - the nap
 * length itself is the whole of it, and no earlier plan's alarm is allowed to shorten it.
 *
 * F5/H1: the AWAKE branch returns null instead of sliding once EITHER [wakeAlarmFiredAt] is non-null (the
 * main wake alarm has actually rung) OR [morningAlarmAt] is at or before [now] (the morning's own latched
 * alarm time has passed regardless of whether a firing was ever recorded for it - a state save that failed
 * after arming, a quarantined state file falling back to an older backup, or a tick racing a delivery window
 * can all leave [wakeAlarmFiredAt] null even though the alarm time is long gone). Either condition alone ends
 * the sliding nap: D4's own nudge covers the follow-up, and D5/G8's ASLEEP branch below covers a genuine
 * return to sleep.
 *
 * H1 SUPERSEDES G3: G3 tried to close this gap with `previousWakeAt`, the PREVIOUS TICK's own `plan.wakeAt` -
 * but in the exact sliding-AWAKE-nap case this guard exists for, that previous plan IS the slid nap itself
 * (`now + napLength` from one tick ago), which by construction always sits ~15 min ahead of `now` (ticks run
 * every 5 min, the nap is 20). `previousWakeAt.isAfter(now)` could therefore never be true on the path it was
 * written for, and the guard was dead code - see WakeAlarm.kt's H1 failure trace in phone-only-fixes-3.md.
 * [morningAlarmAt] fixes this by being a fact the plan never carried before: the morning's real alarm time,
 * LATCHED once (from whichever tick last produced a FULL_CYCLES or DEADLINE_ONLY plan) and never rewritten by
 * a NAP plan's own slid value - so once `now` reaches it, the stop is permanent, not good for one tick.
 *
 * Before F5 the AWAKE branch stayed reachable indefinitely after the wake alarm (D3 removed the old guard that
 * used to stop it), re-arming an alarm at now + napLength on every tick with nothing to ever cancel it - not
 * reliably self-cancelling either, since ticks are throttled by Doze to roughly 9 min under
 * setExactAndAllowWhileIdle, letting two deferred ticks clear the 20 min margin and ring at full volume long
 * after the owner left for the day. The ASLEEP branch is untouched by any of this: a genuine D5/G8 nap must
 * still arm - see [asleepNapTarget] for H2's own fix to that branch.
 */
private fun napAlarm(
    state: SleepState,
    referenceOnset: Instant,
    deadline: Instant?,
    now: Instant,
    config: EngineConfig,
    wakeAlarmFiredAt: Instant?,
    morningAlarmAt: Instant?,
    lastNapAlarmFiredAt: Instant?
): Instant? {
    val napEnd = when (state) {
        SleepState.AWAKE -> if (awakeSlidingNapMustStop(wakeAlarmFiredAt, morningAlarmAt, now)) return null else now.plus(config.napLength)
        else -> asleepNapTarget(referenceOnset, lastNapAlarmFiredAt, config)
    }
    return if (deadline == null) napEnd else minOf(napEnd, deadline)
}

/** H1: either condition alone ends rule 7's sliding AWAKE nap, permanently - see [napAlarm]'s own doc. */
private fun awakeSlidingNapMustStop(wakeAlarmFiredAt: Instant?, morningAlarmAt: Instant?, now: Instant): Boolean =
    wakeAlarmFiredAt != null || (morningAlarmAt != null && !morningAlarmAt.isAfter(now))

/**
 * H2: the ASLEEP branch's own target - ordinarily `referenceOnset + napLength`, the fixed 20 minutes the
 * owner was promised for THIS stretch. But once a nap alarm has already fired for this exact onset (the
 * owner slept straight through it, [lastNapAlarmFiredAt] falls at or after [referenceOnset]), that target is
 * already spent: recomputing it from `referenceOnset` lands in the past, and [pullForwardIfTooSoon] would
 * otherwise squeeze it into a bare `now + minAlarmLead` - a two-minute reprise of the alarm that just rang,
 * not the second twenty-minute chance the owner was promised. In that one case the target is instead
 * `lastNapAlarmFiredAt + napLength` - a genuinely fresh twenty minutes measured from the nap that just rang,
 * not from `now`.
 *
 * That distinction matters across a SEQUENCE, not just the one tick that first notices the firing: `now`
 * changes on every tick, so a target computed as `now + napLength` would recompute to a LATER instant on
 * every subsequent tick before the owner is confirmed asleep or awake again - `changed` would stay true
 * forever in NightOrchestrator.armPhoneAlarmIfNeeded, and the alarm would be perpetually re-armed later and
 * later, never actually reached, however long the owner stays asleep. Anchoring on [lastNapAlarmFiredAt]
 * instead (itself untouched by any of those ticks, since it only changes at the NEXT firing) gives a fixed
 * target that holds steady across every tick in between - exactly like `referenceOnset + napLength` already
 * does for the ordinary case - and lets [pullForwardIfTooSoon] still cover a late-detected nap or reboot the
 * normal way if THAT fixed target itself ever falls behind `now`. Every other case - a nap detected late, an
 * alarm computed in the past after a reboot, [lastNapAlarmFiredAt] belonging to an earlier, already-ended
 * stretch - is untouched: those still fall through to the ordinary `referenceOnset + napLength` and the
 * ordinary pull-forward.
 */
private fun asleepNapTarget(referenceOnset: Instant, lastNapAlarmFiredAt: Instant?, config: EngineConfig): Instant {
    val napAlreadyFiredForThisOnset = lastNapAlarmFiredAt != null && !lastNapAlarmFiredAt.isBefore(referenceOnset)
    return if (napAlreadyFiredForThisOnset) lastNapAlarmFiredAt.plus(config.napLength) else referenceOnset.plus(config.napLength)
}

/**
 * D8: an alarm that would land before `now + minAlarmLead` is moved there instead, rounded up to a whole
 * minute (C1: the band used to only take hour:minute, and truncating down could drop the target inside
 * minAlarmLead by the time the write actually landed; the phone alarm has no such rounding need, but the
 * lead itself is still worth a clean whole-minute target). Still capped by the deadline when there is one, even
 * if that means landing closer than minAlarmLead - the deadline is the harder constraint of the two.
 */
private fun pullForwardIfTooSoon(raw: Instant, deadline: Instant?, now: Instant, config: EngineConfig): Instant {
    val lead = now.plus(config.minAlarmLead)
    if (!raw.isBefore(lead)) return raw
    val pulled = ceilToWholeMinute(lead)
    return if (deadline != null) minOf(pulled, deadline) else pulled
}

/** C1: rounds [instant] up to the next whole minute; an instant already exactly on a minute boundary is returned unchanged. */
private fun ceilToWholeMinute(instant: Instant): Instant {
    val truncated = instant.truncatedTo(ChronoUnit.MINUTES)
    return if (truncated == instant) instant else truncated.plus(Duration.ofMinutes(1))
}
