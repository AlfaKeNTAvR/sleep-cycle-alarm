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
    /**
     * F5: the wake (or nap) alarm's fired instant (NightState.wakeAlarmFiredAt), null until the main wake alarm
     * has fired this night. G8 removed its ONE original job here, the D5/G8 nap cap (see PlanSteps.kt's
     * `isPostWakeNapCapSpent`, which counts `napAlarmsUsed` instead).
     *
     * J5 CORRECTION (reviewer-reported, 2026-09-21): this doc claimed, from G8 through J4, that the AWAKE
     * branch of [napAlarm] is the only remaining reader. That has been false since H8 and doubly so since
     * J1.3 - [morningAlarmAlreadyRang] reads it too, twenty-odd lines below this very comment (see the
     * `rule != PlanRule.NAP` line in [computeWakeAlarm]), and since J1.3 it does so as one half of
     * `laterOf(wakeAlarmFiredAt, phoneAlarmFiredFor)`. Two readers, then: [awakeSlidingNapMustStop] (rule 7's
     * sliding AWAKE nap) and [morningAlarmAlreadyRang] (the spent-morning-target check).
     */
    wakeAlarmFiredAt: Instant?,
    /** H1 SUPERSEDES G3's `previousWakeAt`: the night's own morning alarm time, LATCHED by the app layer (NightState.morningAlarmAt) and never overwritten by a NAP plan's own slid `wakeAt` - see computeAlarmPlan's own doc for why the earlier "previous tick's own wakeAt" reading was unreachable. Only [napAlarm]'s AWAKE branch reads this. */
    morningAlarmAt: Instant?,
    /** H2: the most recent nap alarm's own fired instant, pre-wake or post-wake alike - null until the first nap alarm ever fires this night. Only [napAlarm]'s ASLEEP branch reads this - see its own doc. */
    lastNapAlarmFiredAt: Instant?,
    /** J1.3 (owner-reported, 2026-09-21): NightState.phoneAlarmFiredFor, the last phone alarm instant that fired AT ALL, written unconditionally by PhoneAlarmReceiver.markPhoneAlarmFired even when the same firing's attribution to wakeAlarmFiredAt was skipped (a stale state.lastPlan read racing the in-flight tick's own save). See [morningAlarmAlreadyRang]'s own doc for why this closes the hole [wakeAlarmFiredAt] alone left open. */
    phoneAlarmFiredFor: Instant?
): Instant? {
    val raw = when (rule) {
        // Rule 1: the night is already over, there is no alarm to schedule.
        PlanRule.FINISHED -> return null
        // Rule 7: 20 min after falling back asleep (or after now, while still awake), never past the deadline.
        // F5/H1: the AWAKE branch can itself decide there is nothing left to arm - see napAlarm's own doc.
        PlanRule.NAP -> napAlarm(state, referenceOnset, deadline, now, config, wakeAlarmFiredAt, morningAlarmAt, lastNapAlarmFiredAt, phoneAlarmFiredFor) ?: return null
        // Rule 5: the alarm rings exactly at the deadline.
        PlanRule.DEADLINE_ONLY -> deadline ?: now.plus(config.minAlarmLead)
        // Rules 3, 4, 6: the reference onset plus the whole cycles that fit.
        PlanRule.FULL_CYCLES -> referenceOnset.plus(config.cycleLength.multipliedBy(cycles.toLong()))
    }
    // H8: a morning target the wake alarm has already rung for is spent - it must never be pulled forward
    // into a brand new alarm two minutes out. Rule 7's own targets are exempt: a nap is measured from an
    // onset or a firing of its own, and D5/G8's post-wake naps legitimately come after [wakeAlarmFiredAt].
    if (rule != PlanRule.NAP && morningAlarmAlreadyRang(raw, wakeAlarmFiredAt, phoneAlarmFiredFor)) return null
    return pullForwardIfTooSoon(raw, deadline, now, config)
}

/**
 * H8: whether the instant rules 3/4/5/6 just produced is one the MAIN wake alarm has already rung for
 * ([wakeAlarmFiredAt] at or after [raw]). Once it has, [pullForwardIfTooSoon] must not resurrect it: [raw] is
 * a fixed instant for the whole night (the reference onset plus whole cycles, or the deadline), so every
 * later tick recomputes that same spent instant, finds it in the past, and moves it to `now + minAlarmLead` -
 * a brand new alarm two minutes out, armed and rung again on every tick for as long as the owner stays
 * asleep. That is the owner's own report of it ("it shifts the alarm a couple of minutes forward, every
 * single time"), and it is the same mistake F5 already fixed for rule 7's AWAKE branch: once the wake alarm
 * has rung, D4's out-of-bed nudge and D5/G8's naps own the follow-up, never a reprise of the alarm that just
 * rang.
 *
 * D8 itself is untouched for an alarm that never rang: a target computed in the past because the phone dozed
 * through its tick, because arming failed, or because a reboot landed late still gets its ordinary
 * pull-forward - that is the whole case D8 exists for. Only [wakeAlarmFiredAt], the app layer's record of an
 * actual firing (PhoneAlarmReceiver.recordWakeOrNapFired), suppresses it, and only for the target that firing
 * belongs to: a later target, were one ever computed, is still armed normally.
 *
 * J1.3 (owner-reported, 2026-09-21) ADDS [phoneAlarmFiredFor] to the test, taking whichever of the two firing
 * markers is LATER: PhoneAlarmReceiver.recordWakeOrNapFired loads night state BEFORE the in-flight tick that
 * armed the alarm has saved its own plan, so `state.lastPlan.wakeAt` can still read the PREVIOUS tick's target
 * at the moment the alarm actually fires - the guard that attributes a firing to [wakeAlarmFiredAt] bails out
 * on that mismatch (logged, never silent, see recordWakeOrNapFired's own doc) and [wakeAlarmFiredAt] is left
 * null. Without this, [raw] (a fixed instant for the whole night) stayed unspent forever from this function's
 * own point of view: every following tick recomputed the same [raw], found it in the past, pulled it forward
 * to `now + minAlarmLead`, and rang it again - the SAME re-ring loop H8 already fixed for the case where
 * attribution succeeds, reopened by the one case where it does not. [phoneAlarmFiredFor]
 * (NightState.phoneAlarmFiredFor) is written unconditionally by markPhoneAlarmFired, before the attribution
 * check that can skip [wakeAlarmFiredAt] ever runs, so it survives exactly the race that leaves
 * [wakeAlarmFiredAt] null and closes the hole. Taking the LATER of the two (rather than [phoneAlarmFiredFor]
 * alone) matters because [wakeAlarmFiredAt] can legitimately be null-forever on a night whose spent marker
 * comes from an earlier stretch's own firing while a fresh [raw] is still pending - see the ASLEEP branch's
 * own [asleepNapTarget] for the parallel reasoning on the nap side.
 *
 * M2 (owner-reported, 2026-09-21): this comparison is only sound when [raw] and the fired markers it is
 * measured against are both millisecond-precision - a [raw] carrying finer precision than the alarm intent's
 * own epoch-milli round trip (PhoneAlarmScheduler.kt/PhoneAlarmReceiver.kt) can sit strictly AFTER an already-
 * fired marker by exactly its own sub-millisecond remainder, reading as "not yet rung" for an alarm that just
 * did - the night of 2026-09-21/22's own second ring (docs/decisions.md's M2 record). Fixed at the source
 * (AppClock.now() now truncates to whole milliseconds, app/night/AppClock.kt): every instant this function
 * sees is millisecond-clean by construction, so this comparison is exact again in ordinary operation.
 *
 * M3 (owner-reported, 2026-09-21) ADDS a tolerance on top of M2's own truncation, rather than relying on exact
 * equality alone - the owner's own objection: exact equality on an instant that has already survived one lossy
 * round trip is brittle by construction, and a future leak of sub-millisecond precision (a new clock seam, a
 * new persistence format, a refactor) would silently reopen this exact double-ring with nothing in the code to
 * say why it must not. [firedAtOrBefore] (`AlarmInstantTolerance.kt`) replaces the raw `!raw.isAfter(latestFired)`
 * below: [raw] up to [ALARM_INSTANT_TOLERANCE] AFTER [latestFired] now also counts as already rung - the
 * truncation above means that gap is normally exactly zero, so this tolerance normally does no work at all; see
 * [ALARM_INSTANT_TOLERANCE]'s own doc for why one second is the right size and why this is not J1.2's reverted
 * window under a new name.
 */
private fun morningAlarmAlreadyRang(raw: Instant, wakeAlarmFiredAt: Instant?, phoneAlarmFiredFor: Instant?): Boolean =
    firedAtOrBefore(raw, laterOf(wakeAlarmFiredAt, phoneAlarmFiredFor))

/** J1.3: the later of two possibly-null fired instants, or the one that is non-null, or null if both are. */
private fun laterOf(a: Instant?, b: Instant?): Instant? = when {
    a == null -> b
    b == null -> a
    else -> maxOf(a, b)
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
 * every 5 min, the nap is 20).
 *
 * J5 CORRECTION (reviewer-reported, 2026-09-21): the sentence that used to follow had the comparison exactly
 * backwards. It said `previousWakeAt.isAfter(now)` "could therefore never be true on the path it was written
 * for" - but a target sitting ~15 min AHEAD of `now` satisfies `isAfter(now)` ALWAYS, never never. The
 * conclusion that G3's guard was useless here is unchanged and still correct, only its reason: the guard was
 * meant to detect a previous target that had already lapsed, and on this path the previous target is a freshly
 * slid nap that by construction has not, so the guard was always satisfied and never stopped anything. Dead in
 * effect either way - see WakeAlarm.kt's H1 failure trace in phone-only-fixes-3.md - but a reader must not take
 * the old wording as a fact about how [Instant.isAfter] behaves.
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
    lastNapAlarmFiredAt: Instant?,
    /** J2 must-fix 3: threaded through to [asleepNapTarget] - see its own doc for why. */
    phoneAlarmFiredFor: Instant?
): Instant? {
    val napEnd = when (state) {
        SleepState.AWAKE -> awakeNapTarget(morningAlarmAt, wakeAlarmFiredAt, now, config) ?: return null
        else -> asleepNapTarget(referenceOnset, lastNapAlarmFiredAt, phoneAlarmFiredFor, config)
    }
    return if (deadline == null) napEnd else minOf(napEnd, deadline)
}

/**
 * H8: rule 7's AWAKE branch, in full - null once the sliding nap must stop for good
 * ([awakeSlidingNapMustStop]), the night's own morning alarm while that alarm is still PENDING, and the
 * sliding `now + napLength` only when there is no morning alarm to defer to at all.
 *
 * The middle case is what H8 adds, and without it the alarm could end up never ringing AT ALL. Waking a few
 * minutes before the morning alarm (the ordinary way a sleep-cycle alarm night ends) puts the owner in AWAKE
 * with the picked total already used up, which is exactly rule 7 - and the slid `now + napLength` is LATER
 * than the morning alarm, so every tick re-armed the phone PAST an alarm that was already armed correctly,
 * and the morning alarm itself was never reached. Then, the moment its own time passed,
 * [awakeSlidingNapMustStop] ended the sliding for good and the night arrived at no alarm whatsoever - the
 * owner's own report of it ("the alarm never fired"). Handing [morningAlarmAt] back instead leaves the phone
 * armed at exactly the instant it is already armed at, so nothing moves and the alarm rings when it was meant
 * to. Nothing is lost by not arming a nap of its own here: if the owner does doze off, the very next tick
 * sees ASLEEP and [asleepNapTarget] arms a real nap from that onset, which is D5/G8's own path anyway.
 *
 * [morningAlarmAt] is known to be strictly after [now] by the time it is returned - [awakeSlidingNapMustStop]
 * has already ended the night's sliding nap for any morning alarm at or before [now].
 */
private fun awakeNapTarget(morningAlarmAt: Instant?, wakeAlarmFiredAt: Instant?, now: Instant, config: EngineConfig): Instant? = when {
    awakeSlidingNapMustStop(wakeAlarmFiredAt, morningAlarmAt, now) -> null
    morningAlarmAt != null -> morningAlarmAt
    else -> now.plus(config.napLength)
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
 *
 * J2 must-fix 3 (owner-reported, 2026-09-21) ADDS [phoneAlarmFiredFor] to the test, taking the LATER of it and
 * [lastNapAlarmFiredAt] - the exact same correction J1.3 already made to [morningAlarmAlreadyRang], applied
 * here because this branch has the identical hole. J1.3 only closed the stale-load attribution race
 * (PhoneAlarmReceiver.recordWakeOrNapFired loading `state.lastPlan` before the in-flight tick that armed the
 * alarm has saved its own plan, so attribution bails out and logs rather than guessing) for the MORNING path -
 * but when that race skips attribution, it skips THREE facts at once, not one: [wakeAlarmFiredAt],
 * `napAlarmsUsed`, AND [lastNapAlarmFiredAt] alike, all guarded by the same `firedPlan == null` bail-out. This
 * function keyed entirely on [lastNapAlarmFiredAt], so a nap whose attribution was skipped this way was
 * invisible to it - `referenceOnset` unchanged (the band still reports the SAME onset, since nothing about
 * sleep state changed), the ordinary target recomputed as already in the past, and `pullForwardIfTooSoon`
 * squeezed it into a fresh `now + minAlarmLead` ring - and because [lastNapAlarmFiredAt] never gets a chance to
 * update either, the SAME thing happens again next tick, and the next, unbounded: `napAlarmsUsed` also never
 * increments on this path, so the two-nap cap that would otherwise stop it never engages, and NAP mode ticks
 * every 5 minutes, so a tick is nearly always near enough to the just-pulled-forward target to repeat. Taking
 * the later of the two (never [phoneAlarmFiredFor] alone) matters for the same reason it did in J1.3: a nap
 * whose attribution succeeded ordinarily can have a [phoneAlarmFiredFor] belonging to an EARLIER, unrelated
 * firing (the wake alarm itself, or an earlier already-ended nap stretch) while [lastNapAlarmFiredAt] holds the
 * correct, later value.
 *
 * S5 (reviewer note, 2026-09-21) CORRECTS this doc's own former claim here: the "not before the reference
 * onset" check below does NOT exclude an unrelated (including a genuine wake-alarm) firing by construction -
 * it is reachable, not merely hypothetical. Traced counter-example: an awakening registers at 06:28, the owner
 * is back asleep by 06:29:30 (clearing the minimum-awakening floor, so THIS stretch's own [referenceOnset] is
 * 06:29:30), and the still-pending latched morning alarm - armed from before this stretch even started - then
 * fires normally at 06:30, which is at-or-after 06:29:30 and so counts as "already fired for this onset" here,
 * even though it was the MAIN wake alarm, not a nap. Restored or quarantined state can widen that gap much
 * further than one tick's worth.
 *
 * J5 CORRECTION (reviewer-reported, 2026-09-21) REPLACES the safety paragraph that used to stand here. It had
 * the arithmetic backwards in both directions and drew a conclusion that does not follow, so it is restated
 * rather than patched. What it got right: [laterOf] can only push the effective anchor later than or equal to
 * [referenceOnset], never earlier, and this function always returns a real instant, never null.
 *
 * What it got WRONG, and what the real property is. A LATER anchor makes the nap LONGER as measured from the
 * owner's actual onset, not shorter: in this function's own worked example above (onset 06:29:30, the morning
 * alarm firing at 06:30) the anchor moves from 06:29:30 to 06:30 and the target returned is 06:50, where a nap
 * measured from the true onset would have been 06:49:30 - twenty minutes and thirty seconds of sleep, not
 * nineteen and a half. The old text claimed a "shorter effective nap, bounded by [napLength] itself"; the
 * opposite is true, and the overshoot is bounded instead by `latestFired - referenceOnset`, the gap between
 * this stretch's own onset and whatever firing the anchor jumped to.
 *
 * The old text also argued that "a spurious ring or a missed one would require an EARLIER target". That is not
 * a safety argument. A later target IS a later ring, which on a nap is precisely the thing that can be missed -
 * the owner sleeps past the twenty minutes they were promised, by that same gap. The honest statement of the
 * property is narrower: this function can never ring EARLY (it can only move the anchor forward), it can never
 * return nothing at all, and it can ring LATE by exactly `latestFired - referenceOnset`.
 *
 * And the old text's fallback does not hold unconditionally either: it said D4's own 15-minute out-of-bed
 * nudge "already covers a nap that turns out short". The nudge belonging to the FIRING this anchor jumped to
 * can itself be cancelled by nap supersession before it ever rings (NightOrchestrator.napSupersedesPendingNudge,
 * and the two orderings J4 and J5 had to close in it), so it is a real backstop but not a guaranteed one. The
 * overshoot above is the bound worth relying on; the nudge is what usually shortens it in practice.
 *
 * M2 (owner-reported, 2026-09-21): `napAlreadyFiredForThisOnset`'s own `!latestFired.isBefore(referenceOnset)`
 * has the identical precision hazard [morningAlarmAlreadyRang] documents - a [referenceOnset] finer than the
 * alarm intent's own millisecond round trip can sit strictly AFTER an already-fired [lastNapAlarmFiredAt]/
 * [phoneAlarmFiredFor] by its own sub-millisecond remainder, missing the "already fired for this onset" case.
 * Fixed the same way, at the source (AppClock.now() truncates to whole milliseconds), so this comparison is
 * exact again in ordinary operation.
 *
 * M3 (owner-reported, 2026-09-21) ADDS the same tolerance [morningAlarmAlreadyRang] gets, for the identical
 * reason: exact equality on an already-truncated instant is still brittle against a future precision leak.
 * `napAlreadyFiredForThisOnset` is now [firedAtOrBefore]`(referenceOnset, latestFired)` - [referenceOnset] up
 * to [ALARM_INSTANT_TOLERANCE] AFTER [latestFired] still counts as fired for this onset. See
 * [ALARM_INSTANT_TOLERANCE]'s own doc (`AlarmInstantTolerance.kt`) for the one-second justification.
 */
private fun asleepNapTarget(referenceOnset: Instant, lastNapAlarmFiredAt: Instant?, phoneAlarmFiredFor: Instant?, config: EngineConfig): Instant {
    val latestFired = laterOf(lastNapAlarmFiredAt, phoneAlarmFiredFor)
    val napAlreadyFiredForThisOnset = firedAtOrBefore(referenceOnset, latestFired)
    return if (napAlreadyFiredForThisOnset) checkNotNull(latestFired).plus(config.napLength) else referenceOnset.plus(config.napLength)
}

/**
 * D8: an alarm that would land before `now + minAlarmLead` is moved there instead, rounded up to a whole
 * minute (C1: the band used to only take hour:minute, and truncating down could drop the target inside
 * minAlarmLead by the time the write actually landed; the phone alarm has no such rounding need, but the
 * lead itself is still worth a clean whole-minute target). Still capped by the deadline when there is one, even
 * if that means landing closer than minAlarmLead - the deadline is the harder constraint of the two.
 *
 * J1.1 (owner-reported, 2026-09-21): [raw] still ahead of [now] is returned untouched, however close - the
 * lead only ever pulls forward a target that is AT OR BEFORE [now], i.e. already due or overdue. Before this,
 * `raw.isBefore(now.plus(minAlarmLead))` was true for any tick landing in `(raw - minAlarmLead, raw)`, not
 * just for an overdue raw: a correctly-armed target that simply had not rung yet got rearmed a minute or two
 * LATER, replacing the correct alarm in the phone's one AlarmManager slot (request code 2001,
 * FLAG_UPDATE_CURRENT) with a late one, and the owner saw the alarm visibly jump forward and ring late. C1's
 * own reasoning for the lead was band-specific - the band write needed processing time before the target
 * arrived - and does not apply to `AlarmManager.setAlarmClock`, which can be armed arbitrarily close to its
 * own firing instant. D8 and H8's pull-forward of an overdue or never-rung target (raw at or before now) is
 * untouched: that is still the whole case the lead exists for.
 */
private fun pullForwardIfTooSoon(raw: Instant, deadline: Instant?, now: Instant, config: EngineConfig): Instant {
    if (raw.isAfter(now)) return raw
    val lead = now.plus(config.minAlarmLead)
    val pulled = ceilToWholeMinute(lead)
    return if (deadline != null) minOf(pulled, deadline) else pulled
}

/** C1: rounds [instant] up to the next whole minute; an instant already exactly on a minute boundary is returned unchanged. */
private fun ceilToWholeMinute(instant: Instant): Instant {
    val truncated = instant.truncatedTo(ChronoUnit.MINUTES)
    return if (truncated == instant) instant else truncated.plus(Duration.ofMinutes(1))
}
