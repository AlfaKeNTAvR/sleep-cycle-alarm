package com.nikita.sleepcycle.engine

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChooseModeTest {
    private val config = EngineConfig()

    private fun rule(
        state: SleepState = SleepState.ASLEEP,
        afterAwakening: Boolean = false,
        deadline: Instant? = null,
        cycles: Int = 5,
        owedCycles: Int = 5,
        referenceOnset: String = "2026-09-17T00:30",
        now: String = "2026-09-17T01:00",
        napAlarmsUsed: Int = 0
    ) = chooseMode(
        state, afterAwakening, deadline, cycles, owedCycles, instant(referenceOnset), instant(now), config, napAlarmsUsed
    )

    @Test fun `rule 1 a deadline at or before now is FINISHED`() {
        assertEquals(PlanRule.FINISHED, rule(deadline = instant("2026-09-17T01:00"), now = "2026-09-17T01:00"))
    }

    @Test fun `D3 waking at or after the previous alarm no longer FINISHES the night by itself`() {
        // owedCycles = 0 (the default `cycles`/`owedCycles` of 5 do not apply once the whole picked total has
        // been slept), afterAwakening from being AWAKE with history: rule 7's own, unmodified test now governs
        // this window (a sliding nap alarm, not FINISHED) - see PlanSteps.kt's D3 doc comment on chooseMode.
        assertEquals(
            PlanRule.NAP,
            rule(state = SleepState.AWAKE, afterAwakening = true, cycles = 0, owedCycles = 0, now = "2026-09-17T08:00")
        )
    }

    @Test fun `rule 7 fires when less than a cycle fits before the deadline`() {
        assertEquals(
            PlanRule.NAP,
            rule(afterAwakening = true, deadline = instant("2026-09-17T08:30"), referenceOnset = "2026-09-17T07:30")
        )
    }

    @Test fun `rule 7's fitting test is a deadline test only - with no deadline a cycle still owed always wins`() {
        // The picked total is the only cap on a night with no deadline (decided 2026-09-17 with the owner): no
        // earlier plan's own alarm, however close, may turn a still-owed cycle into a 20 min nap.
        assertEquals(
            PlanRule.FULL_CYCLES,
            rule(afterAwakening = true, cycles = 1, owedCycles = 1, referenceOnset = "2026-09-17T07:30")
        )
    }

    @Test fun `rule 7 fires with no deadline when nothing is still owed of the night's total`() {
        assertEquals(PlanRule.NAP, rule(afterAwakening = true, cycles = 0, owedCycles = 0))
    }

    @Test fun `nothing owed before the first awakening is never a nap - rule 7 always needs a return to sleep`() {
        assertEquals(PlanRule.FULL_CYCLES, rule(afterAwakening = false, cycles = 0, owedCycles = 0))
    }

    @Test fun `rule 5 fires when no whole cycle fits and this is not a return to sleep`() {
        assertEquals(
            PlanRule.DEADLINE_ONLY,
            rule(afterAwakening = false, cycles = 0, deadline = instant("2026-09-17T08:30"))
        )
    }

    @Test fun `rule 5 does not fire after an awakening, rule 7 or 4 takes over instead`() {
        assertEquals(
            PlanRule.FULL_CYCLES,
            rule(afterAwakening = true, cycles = 0, deadline = instant("2026-09-17T08:30"))
        )
    }

    @Test fun `rule 4 is the default case`() {
        assertEquals(PlanRule.FULL_CYCLES, rule(cycles = 5))
    }

    // ---- D5/G8: naps after an awakening, capped at MAX_NAP_ALARMS regardless of whether the main wake alarm
    // has fired ------------------------------------------------------------------------------------------------

    @Test fun `D5 a return to sleep while under the cap is a nap, same as rule 7`() {
        assertEquals(
            PlanRule.NAP,
            rule(afterAwakening = true, cycles = 0, owedCycles = 0, napAlarmsUsed = 1)
        )
    }

    @Test fun `D5 a genuinely new return to sleep once the cap is spent FINISHES the night instead of a third nap`() {
        assertEquals(
            PlanRule.FINISHED,
            rule(afterAwakening = true, cycles = 0, owedCycles = 0, referenceOnset = "2026-09-17T08:00", napAlarmsUsed = MAX_NAP_ALARMS)
        )
    }

    @Test fun `G8 SUPERSEDES D5 - the cap applies even before the main wake alarm has ever fired`() {
        // The old guard required NightState.wakeAlarmFiredAt to be non-null before the cap could engage at
        // all - reachable on a night that uses up the picked total through repeated waking, where the FIRST
        // nap is entered without any FULL_CYCLES/DEADLINE_ONLY alarm ever having rung. chooseMode itself does
        // not even take wakeAlarmFiredAt as a parameter any more: the cap counts every nap alarm that fires,
        // pre-wake or post-wake alike (see PlanSteps.kt's isPostWakeNapCapSpent).
        assertEquals(
            PlanRule.FINISHED,
            rule(afterAwakening = true, cycles = 0, owedCycles = 0, napAlarmsUsed = MAX_NAP_ALARMS)
        )
    }

    @Test fun `G8 state AWAKE right after a nap fires never spends the cap by itself - only a genuine return to sleep does`() {
        // Matches PostWakeNapTest's `thirdAwake` cases: the cap must not force FINISHED while the owner is
        // simply awake between naps (F5's own AWAKE-branch-arms-nothing already covers that window at the
        // wakeAt level); only ASLEEP + afterAwakening - a genuine new return to sleep - can spend it.
        assertEquals(
            PlanRule.NAP,
            rule(state = SleepState.AWAKE, afterAwakening = true, cycles = 0, owedCycles = 0, napAlarmsUsed = MAX_NAP_ALARMS)
        )
    }

    @Test fun `F4 SUPERSEDES the original spec - a spent cap with a deadline still ahead is DEADLINE_ONLY, not FINISHED`() {
        assertEquals(
            PlanRule.DEADLINE_ONLY,
            rule(
                afterAwakening = true, cycles = 0, owedCycles = 0, referenceOnset = "2026-09-17T09:00",
                deadline = instant("2026-09-17T09:30"), now = "2026-09-17T09:00", napAlarmsUsed = MAX_NAP_ALARMS
            )
        )
    }

    @Test fun `F4 a spent cap FINISHES the night only once the deadline is also gone`() {
        assertEquals(
            PlanRule.FINISHED,
            rule(
                afterAwakening = true, cycles = 0, owedCycles = 0, referenceOnset = "2026-09-17T09:00",
                deadline = null, now = "2026-09-17T09:00", napAlarmsUsed = MAX_NAP_ALARMS
            )
        )
    }
}
