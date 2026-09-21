package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ComputeWakeAlarmTest {
    private val config = EngineConfig()

    @Test fun `rule 1 FINISHED produces no alarm`() {
        val result = computeWakeAlarm(
            PlanRule.FINISHED, SleepState.AWAKE, instant("2026-09-17T00:30"), null, 0,
            instant("2026-09-17T08:00"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        assertNull(result)
    }

    @Test fun `rule 5 DEADLINE_ONLY sets the alarm exactly at the deadline`() {
        val result = computeWakeAlarm(
            PlanRule.DEADLINE_ONLY, SleepState.NOT_YET_ASLEEP, instant("2026-09-17T00:40"),
            instant("2026-09-17T00:50"), 0, instant("2026-09-17T00:40"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        assertEquals(instant("2026-09-17T00:50"), result)
    }

    @Test fun `rule 4 FULL_CYCLES adds the picked cycles to the onset`() {
        val result = computeWakeAlarm(
            PlanRule.FULL_CYCLES, SleepState.ASLEEP, instant("2026-09-17T00:30"), null, 5,
            instant("2026-09-17T01:00"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        assertEquals(instant("2026-09-17T08:00"), result)
    }

    @Test fun `rule 7 NAP while awake slides to now plus napLength, cut short by the deadline`() {
        // now + 20 min would be 01:00, but the deadline at 00:55 cuts it short.
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.AWAKE, instant("2026-09-17T00:30"), instant("2026-09-17T00:55"), 0,
            instant("2026-09-17T00:40"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        assertEquals(instant("2026-09-17T00:55"), result)
    }

    @Test fun `rule 7 NAP with no deadline runs the full napLength, with nothing to cap it`() {
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.AWAKE, instant("2026-09-17T00:30"), null, 0,
            instant("2026-09-17T00:40"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        assertEquals(instant("2026-09-17T01:00"), result)
    }

    @Test fun `rule 7 NAP once asleep is 20 min after onset`() {
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.ASLEEP, instant("2026-09-17T01:10"), instant("2026-09-17T01:40"), 0,
            instant("2026-09-17T01:12"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        assertEquals(instant("2026-09-17T01:30"), result)
    }

    @Test fun `F5 rule 7 NAP once asleep still arms even after the wake alarm has fired - only the AWAKE branch is affected`() {
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.ASLEEP, instant("2026-09-17T07:10"), null, 0,
            instant("2026-09-17T07:11"), config, wakeAlarmFiredAt = instant("2026-09-17T07:00"), morningAlarmAt = null, lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        assertEquals(instant("2026-09-17T07:30"), result)
    }

    @Test fun `F5 rule 7's AWAKE safety net arms nothing once the wake alarm has already fired`() {
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.AWAKE, instant("2026-09-17T07:00"), null, 0,
            instant("2026-09-17T07:20"), config, wakeAlarmFiredAt = instant("2026-09-17T07:00"), morningAlarmAt = null, lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        assertNull(result)
    }

    @Test fun `F5 rule 7's AWAKE safety net still slides normally before any wake alarm has fired`() {
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.AWAKE, instant("2026-09-17T00:30"), null, 0,
            instant("2026-09-17T00:40"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        assertEquals(instant("2026-09-17T01:00"), result)
    }

    // ---- H1 SUPERSEDES G3: the AWAKE branch also stops once the latched morningAlarmAt has passed, even with
    // no firing ever recorded for it ---------------------------------------------------------------------------

    @Test fun `H1 rule 7's AWAKE safety net arms nothing once the latched morningAlarmAt has already passed, even with no firing recorded`() {
        // The owner woke naturally before the alarm and rule 7's AWAKE branch replaced it - wakeAlarmFiredAt
        // stays null forever in this scenario (see H1's failure trace in phone-only-fixes-3.md), so the
        // latched morningAlarmAt is what must stop it. This single-call shape is still a faithful unit test of
        // computeWakeAlarm itself; H1's actual bug was in how the app layer used to DERIVE this value across a
        // sequence of ticks (previousPlan?.wakeAt, always sliding along with the nap) - see
        // WholeMorningSequenceTest's sequence test for that part.
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.AWAKE, instant("2026-09-17T06:50"), null, 0,
            instant("2026-09-17T07:10"), config, wakeAlarmFiredAt = null, morningAlarmAt = instant("2026-09-17T06:45"), lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        assertNull(result)
    }

    @Test fun `H1 rule 7's AWAKE safety net arms nothing when the latched morningAlarmAt is exactly now`() {
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.AWAKE, instant("2026-09-17T06:30"), null, 0,
            instant("2026-09-17T06:45"), config, wakeAlarmFiredAt = null, morningAlarmAt = instant("2026-09-17T06:45"), lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        assertNull(result)
    }

    @Test fun `H8 SUPERSEDES H1 - rule 7's AWAKE branch keeps a morningAlarmAt that is still ahead instead of sliding past it`() {
        // H1 let this slide to now + napLength (07:00), which is LATER than the morning alarm the phone is
        // already armed at (06:45): every tick re-armed the phone past it, so the alarm the owner actually
        // asked for never rang, and once 06:45 passed the sliding stopped for good - a night with no alarm at
        // all. Keeping 06:45 leaves the phone armed exactly where it already is. See WakeAlarm.kt's
        // `awakeNapTarget`, and AlarmSequenceReplayTest for the whole sequence this comes from.
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.AWAKE, instant("2026-09-17T06:30"), null, 0,
            instant("2026-09-17T06:40"), config, wakeAlarmFiredAt = null, morningAlarmAt = instant("2026-09-17T06:45"), lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        assertEquals(instant("2026-09-17T06:45"), result)
    }

    @Test fun `H8 rule 7's AWAKE branch still slides by napLength on a night with no morning alarm to defer to`() {
        // morningAlarmAt null and no firing recorded: nothing to keep, so the pre-H8 safety net is unchanged.
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.AWAKE, instant("2026-09-17T06:30"), null, 0,
            instant("2026-09-17T06:40"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        assertEquals(instant("2026-09-17T07:00"), result)
    }

    @Test fun `H8 a morning target the wake alarm already rang for is not pulled forward into a fresh alarm`() {
        // 00:30 + 5 cycles = 08:00, already rung at 08:00 and now three minutes gone. Without H8 this came
        // back as 08:05 (now + minAlarmLead), a brand new alarm armed and rung on every following tick.
        val result = computeWakeAlarm(
            PlanRule.FULL_CYCLES, SleepState.ASLEEP, instant("2026-09-17T00:30"), null, 5,
            instant("2026-09-17T08:03"), config, wakeAlarmFiredAt = instant("2026-09-17T08:00"), morningAlarmAt = instant("2026-09-17T08:00"),
            lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        assertNull(result)
    }

    @Test fun `J1_3 a morning target is still spent when only phoneAlarmFiredFor recorded the firing`() {
        // The re-ring loop: the wake alarm fired at 08:00, but PhoneAlarmReceiver.recordWakeOrNapFired's own
        // attribution to wakeAlarmFiredAt was skipped (a stale state.lastPlan read racing the in-flight tick's
        // own save) - so wakeAlarmFiredAt is still null here, exactly like D8's own overdue case above. Without
        // J1.3, this is indistinguishable from D8 and gets pulled forward to a fresh alarm, ringing again.
        // phoneAlarmFiredFor (08:00), written unconditionally regardless of the attribution race, is what
        // tells the two apart.
        val result = computeWakeAlarm(
            PlanRule.FULL_CYCLES, SleepState.ASLEEP, instant("2026-09-17T00:30"), null, 5,
            instant("2026-09-17T08:05"), config, wakeAlarmFiredAt = null, morningAlarmAt = instant("2026-09-17T08:00"),
            lastNapAlarmFiredAt = null, phoneAlarmFiredFor = instant("2026-09-17T08:00")
        )
        assertNull(result)
    }

    @Test fun `J1_3 the LATER of wakeAlarmFiredAt and phoneAlarmFiredFor decides whether the target is spent`() {
        // wakeAlarmFiredAt here belongs to an EARLIER stretch's own firing (before raw), so it alone would not
        // mark today's raw target spent - only phoneAlarmFiredFor, at or after raw, does.
        val result = computeWakeAlarm(
            PlanRule.FULL_CYCLES, SleepState.ASLEEP, instant("2026-09-17T00:30"), null, 5,
            instant("2026-09-17T08:05"), config, wakeAlarmFiredAt = instant("2026-09-17T05:00"), morningAlarmAt = instant("2026-09-17T08:00"),
            lastNapAlarmFiredAt = null, phoneAlarmFiredFor = instant("2026-09-17T08:00")
        )
        assertNull(result)
    }

    @Test fun `D8 an overdue morning target that never rang is still pulled forward`() {
        // The same overdue target, with no firing ever recorded (the phone dozed through the tick, arming
        // failed, a reboot landed late): D8's pull-forward is untouched.
        val result = computeWakeAlarm(
            PlanRule.FULL_CYCLES, SleepState.ASLEEP, instant("2026-09-17T00:30"), null, 5,
            instant("2026-09-17T08:03"), config, wakeAlarmFiredAt = null, morningAlarmAt = instant("2026-09-17T08:00"), lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        assertEquals(instant("2026-09-17T08:05"), result)
    }

    @Test fun `an alarm too close to now is pulled forward to now plus minAlarmLead`() {
        val result = computeWakeAlarm(
            PlanRule.FULL_CYCLES, SleepState.ASLEEP, instant("2026-09-17T00:30"), null, 5,
            instant("2026-09-17T08:05"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        assertEquals(instant("2026-09-17T08:07"), result)
    }

    @Test fun `C1 a pull-forward from a mid-minute now rounds up to the next whole minute`() {
        val result = computeWakeAlarm(
            PlanRule.FULL_CYCLES, SleepState.ASLEEP, instant("2026-09-17T00:30"), null, 5,
            instant("2026-09-17T08:05:30"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        assertEquals(instant("2026-09-17T08:08"), result)
    }

    @Test fun `C1 a pull-forward exactly on a whole minute is left unchanged`() {
        val result = computeWakeAlarm(
            PlanRule.FULL_CYCLES, SleepState.ASLEEP, instant("2026-09-17T00:30"), null, 5,
            instant("2026-09-17T08:05:00"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        assertEquals(instant("2026-09-17T08:07"), result)
    }

    @Test fun `a pull-forward is still capped by an imminent deadline, even closer than minAlarmLead`() {
        val result = computeWakeAlarm(
            PlanRule.FULL_CYCLES, SleepState.ASLEEP, instant("2026-09-17T00:30"),
            instant("2026-09-17T08:31"), 5, instant("2026-09-17T08:30"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        assertEquals(instant("2026-09-17T08:31"), result)
    }

    @Test fun `J1_1 a target that has not rung yet is left alone even inside the lead window`() {
        // Traced from the owner's own report: onset 23:00, 5 cycles, raw target 06:30 next day. A tick
        // landing at 06:28:30 (1 min 30 s ahead of the correctly-armed 06:30, inside the 2-minute lead) used
        // to compute raw < now + minAlarmLead as true and pull the already-correct 06:30 target forward to
        // 06:31, replacing the correct alarm with a late one - see WakeAlarm.kt's own J1.1 doc. raw being
        // strictly AFTER now, not merely close to it, is what must decide this, and only raw at or before now
        // (D8/H8's actual overdue case) may still be pulled forward.
        val result = computeWakeAlarm(
            PlanRule.FULL_CYCLES, SleepState.ASLEEP, instant("2026-09-17T23:00"), null, 5,
            instant("2026-09-18T06:28:30"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        assertEquals(instant("2026-09-18T06:30:00"), result)
    }

    // ---- H2: the ASLEEP branch's own target - lastNapAlarmFiredAt + napLength once a nap alarm has already
    // fired for THIS onset, rather than pullForwardIfTooSoon's ordinary 2-minute reprise. Anchored on
    // lastNapAlarmFiredAt itself, NOT on `now`, so the target holds STEADY across every tick until the owner
    // is confirmed asleep or awake again - see asleepNapTarget's own doc for why "now + napLength" would
    // instead re-arm a later instant on every single tick and never actually fire. ------------------------

    @Test fun `H2 the ASLEEP branch is the ordinary onset plus napLength when no nap has fired yet`() {
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.ASLEEP, instant("2026-09-17T07:10"), null, 0,
            instant("2026-09-17T07:11"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        assertEquals(instant("2026-09-17T07:30"), result)
    }

    @Test fun `H2 a nap alarm that fired for an EARLIER onset does not count - the ordinary target still applies, pulled forward if overdue`() {
        // lastNapAlarmFiredAt (07:05) is BEFORE this stretch's own onset (07:10): a genuine new awakening
        // happened between them, so this is a fresh stretch that never had its own nap ring yet. The ordinary
        // target (07:30) is still in the future here, so nothing is pulled forward either.
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.ASLEEP, instant("2026-09-17T07:10"), null, 0,
            instant("2026-09-17T07:11"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = instant("2026-09-17T07:05"), phoneAlarmFiredFor = null
        )
        assertEquals(instant("2026-09-17T07:30"), result)
    }

    @Test fun `H2 a nap alarm that already fired for THIS onset gets lastNapAlarmFiredAt plus a fresh napLength, not a 2-minute pull-forward`() {
        // The owner slept straight through nap 1's own ring: the band still shows the SAME onset (07:10) it
        // did when nap 1 was armed, and lastNapAlarmFiredAt (07:30) falls at or after that onset - the exact
        // "already rang for this stretch" case H2 fixes. Without the fix, the ordinary target (07:10 + 20 =
        // 07:30) is already in the past at now=07:31, and pullForwardIfTooSoon would squeeze it to 07:33.
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.ASLEEP, instant("2026-09-17T07:10"), null, 0,
            instant("2026-09-17T07:31"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = instant("2026-09-17T07:30"), phoneAlarmFiredFor = null
        )
        assertEquals(instant("2026-09-17T07:50"), result)
    }

    @Test fun `H2 the target does NOT drift forward on a later tick before the fresh nap fires - anchored on lastNapAlarmFiredAt, not now`() {
        // The exact bug a naive "now + napLength" fix would introduce: recomputed against a LATER now on a
        // later tick, still before the fresh nap has fired, the target must be the SAME 07:50 as the 07:31
        // tick above - never a further-out instant that would keep the alarm from ever actually arriving.
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.ASLEEP, instant("2026-09-17T07:10"), null, 0,
            instant("2026-09-17T07:40"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = instant("2026-09-17T07:30"), phoneAlarmFiredFor = null
        )
        assertEquals(instant("2026-09-17T07:50"), result)
    }

    @Test fun `H2 the fresh napLength is still capped by an imminent deadline`() {
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.ASLEEP, instant("2026-09-17T07:10"), instant("2026-09-17T07:40"), 0,
            instant("2026-09-17T07:31"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = instant("2026-09-17T07:30"), phoneAlarmFiredFor = null
        )
        assertEquals(instant("2026-09-17T07:40"), result)
    }

    @Test fun `H2 a nap detected late after a reboot, with no firing ever recorded, still gets the ordinary pull-forward - never a fresh napLength`() {
        // No lastNapAlarmFiredAt at all (state.lastNapAlarmFiredAt lost, e.g. a fresh install or a corrupt
        // store) must not be mistaken for "a nap already fired here" - pullForwardIfTooSoon keeps its ordinary
        // job for this case (D8).
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.ASLEEP, instant("2026-09-17T07:10"), null, 0,
            instant("2026-09-17T07:31"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        assertEquals(instant("2026-09-17T07:33"), result)
    }

    // ---- J2 must-fix 3 (owner-reported, 2026-09-21): the ASLEEP branch (asleepNapTarget) has the exact same
    // stale-load-attribution hole J1.3 fixed on the morning path - PhoneAlarmReceiver.recordWakeOrNapFired's
    // own firedPlan == null bail-out skips wakeAlarmFiredAt, napAlarmsUsed AND lastNapAlarmFiredAt together, not
    // just one of the three - so a nap whose attribution was skipped this way was invisible to a branch keyed
    // on lastNapAlarmFiredAt alone, and re-rang unbounded (napAlarmsUsed never incrementing means the two-nap
    // cap never engages either). Now takes the LATER of lastNapAlarmFiredAt and phoneAlarmFiredFor, mirroring
    // morningAlarmAlreadyRang's own J1.3 fix exactly. -----------------------------------------------------

    @Test fun `J2 must-fix 3 - the ASLEEP branch anchors on phoneAlarmFiredFor when lastNapAlarmFiredAt's own attribution was skipped`() {
        // The owner's traced sequence: onset 02:45, nap target 03:05 armed, a tick's own sync straddles the
        // 03:05 firing so PhoneAlarmReceiver's attribution is skipped - lastNapAlarmFiredAt stays null, but
        // phoneAlarmFiredFor (written unconditionally, before attribution ever runs) is 03:05. Without this
        // fix, referenceOnset (02:45, unchanged - the band still reports the same onset) plus napLength would
        // recompute to 03:05, already in the past at now=03:08, and get pulled forward to a fresh 03:10 ring -
        // a second alarm for the same nap. With the fix, 03:05 (phoneAlarmFiredFor) is at or after referenceOnset,
        // so the target is anchored on it instead: 03:05 + napLength (20 min) = 03:25, still ahead of now.
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.ASLEEP, instant("2026-09-17T02:45"), null, 0,
            instant("2026-09-17T03:08"), config, wakeAlarmFiredAt = null, morningAlarmAt = null,
            lastNapAlarmFiredAt = null, phoneAlarmFiredFor = instant("2026-09-17T03:05")
        )
        assertEquals(instant("2026-09-17T03:25"), result)
    }

    @Test fun `J2 must-fix 3 - the LATER of lastNapAlarmFiredAt and phoneAlarmFiredFor decides the ASLEEP branch too`() {
        // phoneAlarmFiredFor here belongs to an EARLIER, unrelated firing (before referenceOnset) - only
        // lastNapAlarmFiredAt, at or after referenceOnset, marks THIS stretch's own nap as already spent. Same
        // numbers as the existing H2 "already fired for THIS onset" test above, with an earlier
        // phoneAlarmFiredFor added and no change to the expected result - proving the later-of logic, not just
        // the plain lastNapAlarmFiredAt path, which the test above already covers on its own.
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.ASLEEP, instant("2026-09-17T07:10"), null, 0,
            instant("2026-09-17T07:31"), config, wakeAlarmFiredAt = null, morningAlarmAt = null,
            lastNapAlarmFiredAt = instant("2026-09-17T07:30"), phoneAlarmFiredFor = instant("2026-09-17T05:00")
        )
        assertEquals(instant("2026-09-17T07:50"), result)
    }
}
