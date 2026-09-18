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
        previousPlan: AlarmPlan? = null
    ) = chooseMode(
        state, afterAwakening, deadline, cycles, owedCycles, instant(referenceOnset), instant(now), previousPlan,
        config
    )

    @Test fun `rule 1 a deadline at or before now is FINISHED`() {
        assertEquals(PlanRule.FINISHED, rule(deadline = instant("2026-09-17T01:00"), now = "2026-09-17T01:00"))
    }

    @Test fun `rule 1 waking at or after the previous band alarm is FINISHED`() {
        val previous = AlarmPlan(
            AlarmMode.FULL_CYCLES, instant("2026-09-17T08:00"), null, 5, instant("2026-09-17T00:30"), false, "r"
        )
        assertEquals(
            PlanRule.FINISHED,
            rule(state = SleepState.AWAKE, now = "2026-09-17T08:00", previousPlan = previous)
        )
    }

    @Test fun `rule 1 wins over rule 7 when both would otherwise match`() {
        val previous = AlarmPlan(
            AlarmMode.FULL_CYCLES, instant("2026-09-17T08:00"), null, 5, instant("2026-09-17T00:30"), false, "r"
        )
        // Rule 7's deadline test also matches here: 07:30 plus one cycle lands past the 08:30 deadline.
        assertEquals(
            PlanRule.FINISHED,
            rule(
                state = SleepState.AWAKE, afterAwakening = true, deadline = instant("2026-09-17T08:30"),
                referenceOnset = "2026-09-17T07:30", now = "2026-09-17T08:00", previousPlan = previous
            )
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
}
