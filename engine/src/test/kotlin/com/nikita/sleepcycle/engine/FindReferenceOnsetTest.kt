package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FindReferenceOnsetTest {
    @Test fun `asleep uses the latest stretch onset, not projected`() {
        val stretches = listOf(
            SleepStretch(instant("2026-09-17T00:30"), instant("2026-09-17T01:00"), false),
            SleepStretch(instant("2026-09-17T01:10"), instant("2026-09-17T01:20"), true)
        )
        val result = findReferenceOnset(SleepState.ASLEEP, stretches, instant("2026-09-17T01:25"), EngineConfig())
        assertEquals(instant("2026-09-17T01:10"), result.onset)
        assertFalse(result.projected)
    }

    @Test fun `awake projects from now plus the fall-asleep estimate`() {
        val result = findReferenceOnset(SleepState.AWAKE, emptyList(), instant("2026-09-17T01:00"), EngineConfig())
        assertEquals(instant("2026-09-17T01:15"), result.onset)
        assertTrue(result.projected)
    }

    @Test fun `not yet asleep also projects from now`() {
        val result = findReferenceOnset(SleepState.NOT_YET_ASLEEP, emptyList(), instant("2026-09-17T22:00"), EngineConfig())
        assertEquals(instant("2026-09-17T22:15"), result.onset)
        assertTrue(result.projected)
    }

    @Test fun `asleep with no stretches falls back to a projection`() {
        val result = findReferenceOnset(SleepState.ASLEEP, emptyList(), instant("2026-09-17T01:00"), EngineConfig())
        assertTrue(result.projected)
    }
}
