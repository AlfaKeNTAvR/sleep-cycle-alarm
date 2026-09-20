package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ComputeWakeAlarmTest {
    private val config = EngineConfig()

    @Test fun `rule 1 FINISHED produces no alarm`() {
        val result = computeWakeAlarm(
            PlanRule.FINISHED, SleepState.AWAKE, instant("2026-09-17T00:30"), null, 0,
            instant("2026-09-17T08:00"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null
        )
        assertNull(result)
    }

    @Test fun `rule 5 DEADLINE_ONLY sets the alarm exactly at the deadline`() {
        val result = computeWakeAlarm(
            PlanRule.DEADLINE_ONLY, SleepState.NOT_YET_ASLEEP, instant("2026-09-17T00:40"),
            instant("2026-09-17T00:50"), 0, instant("2026-09-17T00:40"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null
        )
        assertEquals(instant("2026-09-17T00:50"), result)
    }

    @Test fun `rule 4 FULL_CYCLES adds the picked cycles to the onset`() {
        val result = computeWakeAlarm(
            PlanRule.FULL_CYCLES, SleepState.ASLEEP, instant("2026-09-17T00:30"), null, 5,
            instant("2026-09-17T01:00"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null
        )
        assertEquals(instant("2026-09-17T08:00"), result)
    }

    @Test fun `rule 7 NAP while awake slides to now plus napLength, cut short by the deadline`() {
        // now + 20 min would be 01:00, but the deadline at 00:55 cuts it short.
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.AWAKE, instant("2026-09-17T00:30"), instant("2026-09-17T00:55"), 0,
            instant("2026-09-17T00:40"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null
        )
        assertEquals(instant("2026-09-17T00:55"), result)
    }

    @Test fun `rule 7 NAP with no deadline runs the full napLength, with nothing to cap it`() {
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.AWAKE, instant("2026-09-17T00:30"), null, 0,
            instant("2026-09-17T00:40"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null
        )
        assertEquals(instant("2026-09-17T01:00"), result)
    }

    @Test fun `rule 7 NAP once asleep is 20 min after onset`() {
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.ASLEEP, instant("2026-09-17T01:10"), instant("2026-09-17T01:40"), 0,
            instant("2026-09-17T01:12"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null
        )
        assertEquals(instant("2026-09-17T01:30"), result)
    }

    @Test fun `F5 rule 7 NAP once asleep still arms even after the wake alarm has fired - only the AWAKE branch is affected`() {
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.ASLEEP, instant("2026-09-17T07:10"), null, 0,
            instant("2026-09-17T07:11"), config, wakeAlarmFiredAt = instant("2026-09-17T07:00"), morningAlarmAt = null, lastNapAlarmFiredAt = null
        )
        assertEquals(instant("2026-09-17T07:30"), result)
    }

    @Test fun `F5 rule 7's AWAKE safety net arms nothing once the wake alarm has already fired`() {
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.AWAKE, instant("2026-09-17T07:00"), null, 0,
            instant("2026-09-17T07:20"), config, wakeAlarmFiredAt = instant("2026-09-17T07:00"), morningAlarmAt = null, lastNapAlarmFiredAt = null
        )
        assertNull(result)
    }

    @Test fun `F5 rule 7's AWAKE safety net still slides normally before any wake alarm has fired`() {
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.AWAKE, instant("2026-09-17T00:30"), null, 0,
            instant("2026-09-17T00:40"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null
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
            instant("2026-09-17T07:10"), config, wakeAlarmFiredAt = null, morningAlarmAt = instant("2026-09-17T06:45"), lastNapAlarmFiredAt = null
        )
        assertNull(result)
    }

    @Test fun `H1 rule 7's AWAKE safety net arms nothing when the latched morningAlarmAt is exactly now`() {
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.AWAKE, instant("2026-09-17T06:30"), null, 0,
            instant("2026-09-17T06:45"), config, wakeAlarmFiredAt = null, morningAlarmAt = instant("2026-09-17T06:45"), lastNapAlarmFiredAt = null
        )
        assertNull(result)
    }

    @Test fun `H1 rule 7's AWAKE safety net still slides when the latched morningAlarmAt is still ahead`() {
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.AWAKE, instant("2026-09-17T06:30"), null, 0,
            instant("2026-09-17T06:40"), config, wakeAlarmFiredAt = null, morningAlarmAt = instant("2026-09-17T06:45"), lastNapAlarmFiredAt = null
        )
        assertEquals(instant("2026-09-17T07:00"), result)
    }

    @Test fun `an alarm too close to now is pulled forward to now plus minAlarmLead`() {
        val result = computeWakeAlarm(
            PlanRule.FULL_CYCLES, SleepState.ASLEEP, instant("2026-09-17T00:30"), null, 5,
            instant("2026-09-17T08:05"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null
        )
        assertEquals(instant("2026-09-17T08:07"), result)
    }

    @Test fun `C1 a pull-forward from a mid-minute now rounds up to the next whole minute`() {
        val result = computeWakeAlarm(
            PlanRule.FULL_CYCLES, SleepState.ASLEEP, instant("2026-09-17T00:30"), null, 5,
            instant("2026-09-17T08:05:30"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null
        )
        assertEquals(instant("2026-09-17T08:08"), result)
    }

    @Test fun `C1 a pull-forward exactly on a whole minute is left unchanged`() {
        val result = computeWakeAlarm(
            PlanRule.FULL_CYCLES, SleepState.ASLEEP, instant("2026-09-17T00:30"), null, 5,
            instant("2026-09-17T08:05:00"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null
        )
        assertEquals(instant("2026-09-17T08:07"), result)
    }

    @Test fun `a pull-forward is still capped by an imminent deadline, even closer than minAlarmLead`() {
        val result = computeWakeAlarm(
            PlanRule.FULL_CYCLES, SleepState.ASLEEP, instant("2026-09-17T00:30"),
            instant("2026-09-17T08:31"), 5, instant("2026-09-17T08:30"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null
        )
        assertEquals(instant("2026-09-17T08:31"), result)
    }

    // ---- H2: the ASLEEP branch's own target - lastNapAlarmFiredAt + napLength once a nap alarm has already
    // fired for THIS onset, rather than pullForwardIfTooSoon's ordinary 2-minute reprise. Anchored on
    // lastNapAlarmFiredAt itself, NOT on `now`, so the target holds STEADY across every tick until the owner
    // is confirmed asleep or awake again - see asleepNapTarget's own doc for why "now + napLength" would
    // instead re-arm a later instant on every single tick and never actually fire. ------------------------

    @Test fun `H2 the ASLEEP branch is the ordinary onset plus napLength when no nap has fired yet`() {
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.ASLEEP, instant("2026-09-17T07:10"), null, 0,
            instant("2026-09-17T07:11"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null
        )
        assertEquals(instant("2026-09-17T07:30"), result)
    }

    @Test fun `H2 a nap alarm that fired for an EARLIER onset does not count - the ordinary target still applies, pulled forward if overdue`() {
        // lastNapAlarmFiredAt (07:05) is BEFORE this stretch's own onset (07:10): a genuine new awakening
        // happened between them, so this is a fresh stretch that never had its own nap ring yet. The ordinary
        // target (07:30) is still in the future here, so nothing is pulled forward either.
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.ASLEEP, instant("2026-09-17T07:10"), null, 0,
            instant("2026-09-17T07:11"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = instant("2026-09-17T07:05")
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
            instant("2026-09-17T07:31"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = instant("2026-09-17T07:30")
        )
        assertEquals(instant("2026-09-17T07:50"), result)
    }

    @Test fun `H2 the target does NOT drift forward on a later tick before the fresh nap fires - anchored on lastNapAlarmFiredAt, not now`() {
        // The exact bug a naive "now + napLength" fix would introduce: recomputed against a LATER now on a
        // later tick, still before the fresh nap has fired, the target must be the SAME 07:50 as the 07:31
        // tick above - never a further-out instant that would keep the alarm from ever actually arriving.
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.ASLEEP, instant("2026-09-17T07:10"), null, 0,
            instant("2026-09-17T07:40"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = instant("2026-09-17T07:30")
        )
        assertEquals(instant("2026-09-17T07:50"), result)
    }

    @Test fun `H2 the fresh napLength is still capped by an imminent deadline`() {
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.ASLEEP, instant("2026-09-17T07:10"), instant("2026-09-17T07:40"), 0,
            instant("2026-09-17T07:31"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = instant("2026-09-17T07:30")
        )
        assertEquals(instant("2026-09-17T07:40"), result)
    }

    @Test fun `H2 a nap detected late after a reboot, with no firing ever recorded, still gets the ordinary pull-forward - never a fresh napLength`() {
        // No lastNapAlarmFiredAt at all (state.lastNapAlarmFiredAt lost, e.g. a fresh install or a corrupt
        // store) must not be mistaken for "a nap already fired here" - pullForwardIfTooSoon keeps its ordinary
        // job for this case (D8).
        val result = computeWakeAlarm(
            PlanRule.NAP, SleepState.ASLEEP, instant("2026-09-17T07:10"), null, 0,
            instant("2026-09-17T07:31"), config, wakeAlarmFiredAt = null, morningAlarmAt = null, lastNapAlarmFiredAt = null
        )
        assertEquals(instant("2026-09-17T07:33"), result)
    }
}
