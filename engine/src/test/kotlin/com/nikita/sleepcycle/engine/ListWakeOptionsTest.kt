package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ListWakeOptionsTest {
    @Test fun `lists only cycle ends at or before deadline`() {
        val options = listWakeOptions(instant("2026-09-17T00:00"), settings("2026-09-17T06:00", 5), EngineConfig())
        assertEquals(listOf(1, 2, 3, 4), options.map { it.cycles })
        assertEquals(360, options.last().sleepDuration.toMinutes())
    }
}
