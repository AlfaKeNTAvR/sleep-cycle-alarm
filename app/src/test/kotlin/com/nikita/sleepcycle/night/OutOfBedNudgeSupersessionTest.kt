package com.nikita.sleepcycle.night

// File purpose: H7.2, H7.3 and (since L1) PhoneAlarmReceiver's own pure decision seams - each needs a real
// Context (AlarmManager, disk I/O, a band sync) to run for real, so each exposes the one decision that
// actually matters as a plain function, JVM-testable directly. See NightOrchestrator.napSupersedesPendingNudge,
// OutOfBedPreNudgeCheck.shouldCancelNudgeForPreCheck and PhoneAlarmReceiver.firedAlarmRecordsPlanBookkeeping.
//
// L1's own repeating behaviour itself is a SEQUENCE property, not a predicate, and is pinned where sequences
// live: `L1 replay` in the engine module's TickScheduleRaceTest.kt. The L1 section below pins only the
// predicates around it - the one boundary the nudge is still excluded from, and the two interactions the
// repeat has with H7.2's supersession and J5's re-arm.

import com.nikita.sleepcycle.alarm.firedAlarmRecordsPlanBookkeeping
import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.AlarmPlan
import com.nikita.sleepcycle.engine.SleepState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class OutOfBedNudgeSupersessionTest {
    /** [onsetIsProjected] false is a plan measured from a REAL onset, i.e. the owner is actually asleep. */
    private fun plan(mode: AlarmMode, wakeAt: Instant?, onsetIsProjected: Boolean = false) =
        AlarmPlan(mode, wakeAt, 0, Instant.parse("2026-09-17T07:10:00Z"), onsetIsProjected, "r")

    private val nudgeAt = Instant.parse("2026-09-17T07:15:00Z")

    // ---- H7.2/FIX4: napSupersedesPendingNudge -------------------------------------------------------------

    @Test
    fun `H7_2 a NAP plan with a non-null wakeAt, a pending nudge, a confirmed ASLEEP reading and a successfully armed replacement supersedes it`() {
        assertTrue(
            napSupersedesPendingNudge(
                plan(AlarmMode.NAP, Instant.parse("2026-09-17T07:30:00Z")), nudgeAt, SleepState.ASLEEP, napAlarmArmed = true
            )
        )
    }

    @Test
    fun `H7_2 no pending nudge means nothing to supersede`() {
        assertFalse(
            napSupersedesPendingNudge(
                plan(AlarmMode.NAP, Instant.parse("2026-09-17T07:30:00Z")), pendingNudgeAt = null, SleepState.ASLEEP, napAlarmArmed = true
            )
        )
    }

    @Test
    fun `H7_2 a NAP plan that arms nothing (F5's AWAKE safety net past the wake alarm) does not supersede - there is no fresh alarm to replace the nudge with`() {
        assertFalse(napSupersedesPendingNudge(plan(AlarmMode.NAP, wakeAt = null), nudgeAt, SleepState.ASLEEP, napAlarmArmed = true))
    }

    @Test
    fun `H8 a NAP plan measured from a PROJECTED onset never supersedes - the owner is awake, which is what the nudge is for`() {
        // Rule 7 covers lying awake too, and since H8 such a plan carries the still-pending morning alarm as
        // its own wakeAt (before H8, a slid nap - equally non-null). Cancelling the nudge on either would
        // silence it in exactly the situation it exists for: awake, in bed, not getting up.
        //
        // The projected onset and the AWAKE state always travel together - a projected onset IS
        // `now + fallAsleepEstimate`, computed because no real onset was detected - so this pins the whole
        // rule-7-while-awake shape the owner actually hits, not just the state flag on its own.
        assertFalse(
            napSupersedesPendingNudge(
                plan(AlarmMode.NAP, Instant.parse("2026-09-17T07:30:00Z"), onsetIsProjected = true),
                nudgeAt,
                SleepState.AWAKE,
                napAlarmArmed = true,
            )
        )
    }

    @Test
    fun `H7_2 a FULL_CYCLES, DEADLINE_ONLY or FINISHED plan never supersedes the nudge - only a nap does`() {
        assertFalse(napSupersedesPendingNudge(plan(AlarmMode.FULL_CYCLES, Instant.parse("2026-09-17T08:00:00Z")), nudgeAt, SleepState.ASLEEP, napAlarmArmed = true))
        assertFalse(napSupersedesPendingNudge(plan(AlarmMode.DEADLINE_ONLY, Instant.parse("2026-09-17T08:00:00Z")), nudgeAt, SleepState.ASLEEP, napAlarmArmed = true))
        assertFalse(napSupersedesPendingNudge(plan(AlarmMode.FINISHED, wakeAt = null), nudgeAt, SleepState.ASLEEP, napAlarmArmed = true))
    }

    // ---- FIX4 (owner-reported, 2026-09-21): an awake owner, or a nap that failed to arm, never supersedes --

    @Test
    fun `FIX4 an AWAKE owner is never superseded - the owner never went back to sleep, the nudge must still ring`() {
        assertFalse(
            napSupersedesPendingNudge(
                plan(AlarmMode.NAP, Instant.parse("2026-09-17T07:30:00Z")), nudgeAt, SleepState.AWAKE, napAlarmArmed = true
            )
        )
    }

    @Test
    fun `FIX4 NOT_YET_ASLEEP is never superseded either - only a confirmed ASLEEP reading counts`() {
        assertFalse(
            napSupersedesPendingNudge(
                plan(AlarmMode.NAP, Instant.parse("2026-09-17T07:30:00Z")), nudgeAt, SleepState.NOT_YET_ASLEEP, napAlarmArmed = true
            )
        )
    }

    @Test
    fun `FIX4 a nap that failed to arm never supersedes - the nudge would otherwise be cancelled with nothing to replace it`() {
        assertFalse(
            napSupersedesPendingNudge(
                plan(AlarmMode.NAP, Instant.parse("2026-09-17T07:30:00Z")), nudgeAt, SleepState.ASLEEP, napAlarmArmed = false
            )
        )
    }

    // ---- J5 must-fix (owner-reported, 2026-09-21): the nudge is compared against the target it is traded for -

    @Test
    fun `J5 a nudge due AFTER the nap's own target is never superseded - it is that nap's own fresh nudge, not a stale leftover`() {
        // The ordering J4's own fix does not reach: the nap is armed SUCCESSFULLY (napAlarmArmed genuinely
        // true, nothing to do with an already-fired marker match), and only THEN does the receiver handle the
        // firing and write its own fresh nudge - before cancelNudgeIfSupersededByNap gets around to reading the
        // pending instant. The owner's own traced night: light 23:00 to 06:28, awake 06:28 to 06:30, light
        // again from 06:30, five cycles, so the nap target is 06:50 and the nap's own fresh nudge is 07:05.
        // Pre-J5 this returned true on the strength of napAlarmArmed alone, cancelling that 07:05 nudge - the
        // nap silencing its own follow-up by a second route.
        val napWakeAt = Instant.parse("2026-09-21T06:50:00Z")
        assertFalse(
            napSupersedesPendingNudge(
                plan(AlarmMode.NAP, napWakeAt), Instant.parse("2026-09-21T07:05:00Z"), SleepState.ASLEEP, napAlarmArmed = true
            )
        )
    }

    @Test
    fun `J5 a nudge due exactly AT the nap's own target is not superseded either - the tie errs toward ringing`() {
        val napWakeAt = Instant.parse("2026-09-21T06:50:00Z")
        assertFalse(napSupersedesPendingNudge(plan(AlarmMode.NAP, napWakeAt), napWakeAt, SleepState.ASLEEP, napAlarmArmed = true))
    }

    @Test
    fun `J5 a nudge due strictly BEFORE the nap's own target is still superseded - the one case this predicate exists for`() {
        // The unchanged, load-bearing case: the nudge would ring mid-nap, at full alarm volume.
        val napWakeAt = Instant.parse("2026-09-21T06:53:00Z")
        assertTrue(
            napSupersedesPendingNudge(
                plan(AlarmMode.NAP, napWakeAt), Instant.parse("2026-09-21T06:45:00Z"), SleepState.ASLEEP, napAlarmArmed = true
            )
        )
    }

    // ---- J5 (reviewer-reported, 2026-09-21): awakeNapCancellationNeedsNudge, the other end of a supersession -

    private val armedNap = plan(AlarmMode.NAP, Instant.parse("2026-09-21T06:53:00Z"))
    private val cancellingNap = plan(AlarmMode.NAP, wakeAt = null)

    @Test
    fun `J5 a nap cancelled by the AWAKE branch with no nudge left pending re-arms one`() {
        // The composed sequence: a nap superseded the nudge, and now the AWAKE branch cancels that same nap -
        // leaving no alarm, no nudge and no pre-check, with nothing in the app able to re-arm any of them.
        assertTrue(awakeNapCancellationNeedsNudge(cancellingNap, armedNap, SleepState.AWAKE, pendingNudgeAt = null))
    }

    @Test
    fun `J5 a nudge that is already pending is left exactly as it is`() {
        // The ordinary tick right after the wake alarm fires: rule 7's AWAKE branch has nothing to arm, so the
        // morning alarm itself is cancelled - but its own D4 nudge is still pending and must not be replaced by
        // a later one measured from this tick instead of from the firing.
        assertFalse(awakeNapCancellationNeedsNudge(cancellingNap, armedNap, SleepState.AWAKE, nudgeAt))
    }

    @Test
    fun `J5 nothing is re-armed when this tick did not actually cancel anything`() {
        // Every tick after the cancelling one carries the same null-wakeAt NAP plan; only the transition tick,
        // whose previous plan still had a target, is a cancellation.
        assertFalse(awakeNapCancellationNeedsNudge(cancellingNap, cancellingNap, SleepState.AWAKE, pendingNudgeAt = null))
        assertFalse(awakeNapCancellationNeedsNudge(cancellingNap, previousPlan = null, sleepState = SleepState.AWAKE, pendingNudgeAt = null))
    }

    @Test
    fun `J5 an asleep owner is never given a nudge here - only the AWAKE branch's own cancellation counts`() {
        assertFalse(awakeNapCancellationNeedsNudge(cancellingNap, armedNap, SleepState.ASLEEP, pendingNudgeAt = null))
        assertFalse(awakeNapCancellationNeedsNudge(cancellingNap, armedNap, SleepState.NOT_YET_ASLEEP, pendingNudgeAt = null))
    }

    @Test
    fun `J5 a FINISHED night is left alone - the night is over, not waiting on the owner to get up`() {
        assertFalse(awakeNapCancellationNeedsNudge(plan(AlarmMode.FINISHED, wakeAt = null), armedNap, SleepState.AWAKE, pendingNudgeAt = null))
    }

    @Test
    fun `J5 a nap that still has a target to arm needs no replacement nudge`() {
        assertFalse(awakeNapCancellationNeedsNudge(armedNap, armedNap, SleepState.AWAKE, pendingNudgeAt = null))
    }

    // ---- L1 (owner decision, 2026-09-21): the nudge repeats until the night ends --------------------------

    @Test
    fun `L1 a wake or nap firing still does the phone alarm slot's own bookkeeping`() {
        assertTrue(firedAlarmRecordsPlanBookkeeping(isOutOfBed = false))
    }

    @Test
    fun `L1 the nudge's own firing still does none of it - that boundary is all the nudge is excluded from now`() {
        // The four facts behind this: phoneAlarmFiredFor, wakeAlarmFiredAt, napAlarmsUsed and
        // lastNapAlarmFiredAt. Before L1 the same flag ALSO suppressed the nudge arming that follows, through
        // one early return covering both, which is why the nudge rang once per night. Only this half survives.
        assertFalse(firedAlarmRecordsPlanBookkeeping(isOutOfBed = true))
    }

    @Test
    fun `L1 a repeating chain never triggers J5's own re-arm, so the two can never double-arm`() {
        // The interaction the owner asked to be checked. A nudge firing clears its record and re-arms in the
        // same receiver call, so from any tick's point of view a nudge is always pending while the chain is
        // alive - and awakeNapCancellationNeedsNudge requires none to be. Pinned with the shape a repeating
        // chain actually presents to the transition tick: the AWAKE branch has just cancelled the nap, which
        // is every one of this predicate's other conditions satisfied at once.
        assertFalse(awakeNapCancellationNeedsNudge(cancellingNap, armedNap, SleepState.AWAKE, nudgeAt))
    }

    @Test
    fun `L1 a nap still supersedes the currently pending link of a repeating chain`() {
        // Unchanged by L1 and deliberately so: the owner is confirmed asleep again, so the nap owns the
        // wake-up, and the nap's own firing arms a fresh nudge that restarts the chain from there. What this
        // predicate sees is no longer "the one nudge this night has" but "the pending link of a live chain",
        // and cancelling that link is still right.
        val secondLink = Instant.parse("2026-09-21T07:15:00Z")
        assertTrue(
            napSupersedesPendingNudge(
                plan(AlarmMode.NAP, Instant.parse("2026-09-21T07:20:00Z")), secondLink, SleepState.ASLEEP, napAlarmArmed = true
            )
        )
    }

    // ---- H7.3: shouldCancelNudgeForPreCheck --------------------------------------------------------------

    @Test
    fun `H7_3 the pre-nudge check cancels the nudge only on a confirmed ASLEEP reading`() {
        assertTrue(shouldCancelNudgeForPreCheck(SleepState.ASLEEP))
    }

    @Test
    fun `H7_3 the pre-nudge check rings when the sync failed, timed out, or returned stale data (represented as null)`() {
        assertFalse(shouldCancelNudgeForPreCheck(null))
    }

    @Test
    fun `H7_3 the pre-nudge check rings when the owner is still awake`() {
        assertFalse(shouldCancelNudgeForPreCheck(SleepState.AWAKE))
    }

    @Test
    fun `H7_3 the pre-nudge check rings when there is no sleep data at all yet`() {
        assertFalse(shouldCancelNudgeForPreCheck(SleepState.NOT_YET_ASLEEP))
    }
}
