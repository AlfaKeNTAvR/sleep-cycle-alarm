package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ListWakeOptionsTest {
    @Test fun `lists only cycle ends at or before deadline`() {
        val options = listWakeOptions(instant("2026-09-17T00:00"), settings("2026-09-17T06:00", 5), EngineConfig())
        assertEquals(listOf(1, 2, 3, 4), options.map { it.cycles })
        assertEquals(360, options.last().sleepDuration.toMinutes())
    }

    @Test fun `upToCycles lists what is still owed, not the whole picked length`() {
        // Mid-night, 6 h slept of the picked 7.5 h: one cycle is still owed, so one option is offered.
        val options = listWakeOptions(instant("2026-09-17T05:30"), settings(cycles = 5), EngineConfig(), upToCycles = 1)
        assertEquals(listOf(1), options.map { it.cycles })
        assertEquals(instant("2026-09-17T07:00"), options.single().wakeTime)
    }

    @Test fun `upToCycles of zero lists nothing at all - it is a plain count, not a picker value`() {
        val options = listWakeOptions(instant("2026-09-17T05:30"), settings(cycles = 5), EngineConfig(), upToCycles = 0)
        assertEquals(emptyList<WakeOption>(), options)
    }
}
