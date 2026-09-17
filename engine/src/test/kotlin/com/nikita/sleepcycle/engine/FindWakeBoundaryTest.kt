package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FindWakeBoundaryTest {
    @Test fun `a deadline always wins`() {
        val previous = AlarmPlan(
            AlarmMode.FULL_CYCLES, instant("2026-09-17T08:00"), null, 5,
            instant("2026-09-17T00:30"), false, instant("2026-09-17T08:00"), "r"
        )
        assertEquals(instant("2026-09-17T09:00"), findWakeBoundary(instant("2026-09-17T09:00"), previous))
    }

    @Test fun `without a deadline it carries forward the previous plan's wake boundary`() {
        val previous = AlarmPlan(
            AlarmMode.NAP, instant("2026-09-17T08:10"), null, 5,
            instant("2026-09-17T00:30"), false, instant("2026-09-17T08:00"), "r"
        )
        assertEquals(instant("2026-09-17T08:00"), findWakeBoundary(null, previous))
    }

    @Test fun `null on the very first plan with no deadline`() {
        assertNull(findWakeBoundary(null, null))
    }
}
