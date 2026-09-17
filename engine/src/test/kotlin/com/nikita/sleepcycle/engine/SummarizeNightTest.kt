package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SummarizeNightTest {
    @Test fun `totals valid stretches and rounds cycle equivalents`() {
        val summary = summarizeNight(listOf(
            SleepStretch(instant("2026-09-17T00:00"), instant("2026-09-17T01:00"), false),
            SleepStretch(instant("2026-09-17T02:00"), instant("2026-09-17T02:45"), true),
            SleepStretch(instant("2026-09-17T03:00"), instant("2026-09-17T03:00"), true)
        ), EngineConfig())
        assertEquals(105, summary.totalSleep.toMinutes())
        assertEquals(listOf(0.7, 0.5), summary.stretches.map { it.cycles })
    }
}
