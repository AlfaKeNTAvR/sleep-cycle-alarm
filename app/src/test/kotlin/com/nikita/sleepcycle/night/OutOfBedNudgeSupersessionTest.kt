package com.nikita.sleepcycle.night

// File purpose: H7.2 and H7.3's own pure decision seams - both need a real Context (AlarmManager, disk I/O,
// a band sync) to run for real, so each exposes the one decision that actually matters as a plain function,
// JVM-testable directly. See NightOrchestrator.napSupersedesPendingNudge and
// OutOfBedPreNudgeCheck.shouldCancelNudgeForPreCheck.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.AlarmPlan
import com.nikita.sleepcycle.engine.SleepState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class OutOfBedNudgeSupersessionTest {
    private fun plan(mode: AlarmMode, wakeAt: Instant?) =
        AlarmPlan(mode, wakeAt, 0, Instant.parse("2026-09-17T07:10:00Z"), false, "r")

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
