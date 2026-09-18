package com.nikita.sleepcycle.engine

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NextSyncDelayTest {
    private val config = EngineConfig()

    private fun planOf(mode: AlarmMode, bandAlarm: String?) = AlarmPlan(
        mode, bandAlarm?.let(::instant), null, 5, instant("2026-09-17T00:30"), false, "r"
    )

    @Test fun `FULL_CYCLES far from the band alarm uses the normal cadence`() {
        val plan = planOf(AlarmMode.FULL_CYCLES, "2026-09-17T08:00")
        assertEquals(Duration.ofMinutes(15), nextSyncDelay(plan, instant("2026-09-17T07:00"), config))
    }

    @Test fun `FULL_CYCLES near the band alarm switches to the frequent cadence`() {
        val plan = planOf(AlarmMode.FULL_CYCLES, "2026-09-17T08:00")
        assertEquals(Duration.ofMinutes(5), nextSyncDelay(plan, instant("2026-09-17T07:35"), config))
    }

    @Test fun `DEADLINE_ONLY follows the same near-alarm rule as FULL_CYCLES`() {
        val plan = planOf(AlarmMode.DEADLINE_ONLY, "2026-09-17T08:30")
        assertEquals(Duration.ofMinutes(15), nextSyncDelay(plan, instant("2026-09-17T07:00"), config))
        assertEquals(Duration.ofMinutes(5), nextSyncDelay(plan, instant("2026-09-17T08:05"), config))
    }

    @Test fun `NAP always uses the frequent cadence, even far from the alarm`() {
        val plan = planOf(AlarmMode.NAP, "2026-09-17T09:00")
        assertEquals(Duration.ofMinutes(5), nextSyncDelay(plan, instant("2026-09-17T00:30"), config))
    }

    @Test fun `OVERDUE always uses the frequent cadence`() {
        val plan = planOf(AlarmMode.OVERDUE, "2026-09-17T08:07")
        assertEquals(Duration.ofMinutes(5), nextSyncDelay(plan, instant("2026-09-17T08:06"), config))
    }

    @Test fun `FINISHED stops syncing`() {
        val plan = planOf(AlarmMode.FINISHED, null)
        assertNull(nextSyncDelay(plan, instant("2026-09-17T08:31"), config))
    }

    @Test fun `the delay is never zero even exactly at the band alarm`() {
        val plan = planOf(AlarmMode.FULL_CYCLES, "2026-09-17T08:00")
        val delay = nextSyncDelay(plan, instant("2026-09-17T08:00"), config)
        assertEquals(Duration.ofMinutes(5), delay)
    }
}
