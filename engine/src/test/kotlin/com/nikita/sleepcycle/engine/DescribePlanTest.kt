package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DescribePlanTest {
    @Test fun `FULL_CYCLES names the onset, cycle count, deadline and band alarm`() {
        val reason = describePlan(
            AlarmMode.FULL_CYCLES, ReferenceOnset(instant("2026-09-17T00:30"), projected = false),
            settings("2026-09-17T08:30", 5), 5, instant("2026-09-17T08:00"), instant("2026-09-17T08:00"), testZone
        )
        assertTrue(reason.contains("00:30"))
        assertTrue(reason.contains("5 of 5"))
        assertTrue(reason.contains("08:30"))
        assertTrue(reason.contains("08:00"))
        assertTrue(reason.contains("Asleep since"))
    }

    @Test fun `FULL_CYCLES with a projected onset says estimated, not asleep since`() {
        val reason = describePlan(
            AlarmMode.FULL_CYCLES, ReferenceOnset(instant("2026-09-17T00:45"), projected = true),
            settings(cycles = 5), 5, instant("2026-09-17T07:15"), instant("2026-09-17T07:15"), testZone
        )
        assertTrue(reason.contains("Estimated asleep at"))
    }

    @Test fun `DEADLINE_ONLY names the band alarm`() {
        val reason = describePlan(
            AlarmMode.DEADLINE_ONLY, ReferenceOnset(instant("2026-09-17T00:40"), projected = true),
            settings("2026-09-17T00:50"), 0, instant("2026-09-17T00:50"), null, testZone
        )
        assertTrue(reason.contains("00:50"))
    }

    @Test fun `NAP names the wake boundary cap`() {
        val reason = describePlan(
            AlarmMode.NAP, ReferenceOnset(instant("2026-09-17T00:20"), projected = false),
            settings(), 0, instant("2026-09-17T00:40"), instant("2026-09-17T01:00"), testZone
        )
        assertTrue(reason.contains("Nap mode"))
        assertTrue(reason.contains("01:00"))
    }

    @Test fun `OVERDUE names the band alarm`() {
        val reason = describePlan(
            AlarmMode.OVERDUE, ReferenceOnset(instant("2026-09-17T00:30"), projected = false),
            settings(), 5, instant("2026-09-17T08:07"), null, testZone
        )
        assertTrue(reason.contains("08:07"))
    }

    @Test fun `FINISHED with a deadline names it`() {
        val reason = describePlan(
            AlarmMode.FINISHED, ReferenceOnset(instant("2026-09-17T00:30"), projected = false),
            settings("2026-09-17T08:30"), 5, null, null, testZone
        )
        assertTrue(reason.contains("08:30"))
    }

    @Test fun `FINISHED with no deadline still produces a sentence`() {
        val reason = describePlan(
            AlarmMode.FINISHED, ReferenceOnset(instant("2026-09-17T00:30"), projected = false),
            settings(), 5, null, null, testZone
        )
        assertTrue(reason.startsWith("Night finished"))
    }
}
