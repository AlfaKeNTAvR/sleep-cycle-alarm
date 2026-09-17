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
        referenceOnset: String = "2026-09-17T00:30",
        wakeBoundary: String? = null,
        now: String = "2026-09-17T01:00",
        previousPlan: AlarmPlan? = null
    ) = chooseMode(
        state, afterAwakening, deadline, cycles, instant(referenceOnset), wakeBoundary?.let(::instant),
        instant(now), previousPlan, config
    )

    @Test fun `rule 1 a deadline at or before now is FINISHED`() {
        assertEquals(PlanRule.FINISHED, rule(deadline = instant("2026-09-17T01:00"), now = "2026-09-17T01:00"))
    }

    @Test fun `rule 1 waking at or after the previous band alarm is FINISHED`() {
        val previous = AlarmPlan(
            AlarmMode.FULL_CYCLES, instant("2026-09-17T08:00"), null, 5, instant("2026-09-17T00:30"), false, null, "r"
        )
        assertEquals(
            PlanRule.FINISHED,
            rule(state = SleepState.AWAKE, now = "2026-09-17T08:00", previousPlan = previous)
        )
    }

    @Test fun `rule 1 wins over rule 7 when both would otherwise match`() {
        val previous = AlarmPlan(
            AlarmMode.FULL_CYCLES, instant("2026-09-17T08:00"), null, 5, instant("2026-09-17T00:30"), false,
            instant("2026-09-17T08:00"), "r"
        )
        assertEquals(
            PlanRule.FINISHED,
            rule(
                state = SleepState.AWAKE, afterAwakening = true, now = "2026-09-17T08:00",
                wakeBoundary = "2026-09-17T08:00", previousPlan = previous
            )
        )
    }

    @Test fun `rule 7 fires when less than a cycle is left before the wake boundary`() {
        assertEquals(
            PlanRule.NAP,
            rule(afterAwakening = true, referenceOnset = "2026-09-17T07:30", wakeBoundary = "2026-09-17T08:30")
        )
    }

    @Test fun `rule 7 never fires with no wake boundary`() {
        assertEquals(
            PlanRule.FULL_CYCLES,
            rule(afterAwakening = true, referenceOnset = "2026-09-17T07:30", wakeBoundary = null)
        )
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
            rule(afterAwakening = true, cycles = 0, deadline = instant("2026-09-17T08:30"), wakeBoundary = null)
        )
    }

    @Test fun `rule 4 is the default case`() {
        assertEquals(PlanRule.FULL_CYCLES, rule(cycles = 5))
    }
}
