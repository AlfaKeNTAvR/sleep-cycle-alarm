package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ComputePhoneAlarmTest {
    private val config = EngineConfig()

    @Test fun `a deadline always wins, in every mode`() {
        val deadline = instant("2026-09-17T08:30")
        assertEquals(deadline, computePhoneAlarm(deadline, false, AlarmMode.FULL_CYCLES, instant("2026-09-17T08:00"), null, config))
        assertEquals(deadline, computePhoneAlarm(deadline, true, AlarmMode.OVERDUE, instant("2026-09-17T08:32"), null, config))
        assertEquals(deadline, computePhoneAlarm(deadline, true, AlarmMode.FINISHED, null, null, config))
    }

    @Test fun `no deadline, backup enabled adds the offset after the band alarm in FULL_CYCLES and NAP`() {
        val bandAlarm = instant("2026-09-17T08:00")
        assertEquals(instant("2026-09-17T08:15"), computePhoneAlarm(null, true, AlarmMode.FULL_CYCLES, bandAlarm, null, config))
        assertEquals(instant("2026-09-17T08:15"), computePhoneAlarm(null, true, AlarmMode.NAP, bandAlarm, null, config))
    }

    @Test fun `no deadline, backup disabled is null`() {
        assertNull(computePhoneAlarm(null, false, AlarmMode.FULL_CYCLES, instant("2026-09-17T08:00"), null, config))
    }

    @Test fun `no deadline, OVERDUE and FINISHED keep the previous phone alarm so the snooze cannot drag it`() {
        val previous = AlarmPlan(
            AlarmMode.FULL_CYCLES, instant("2026-09-17T08:00"), instant("2026-09-17T08:15"), 5,
            instant("2026-09-17T00:30"), false, "r"
        )
        assertEquals(
            instant("2026-09-17T08:15"),
            computePhoneAlarm(null, true, AlarmMode.OVERDUE, instant("2026-09-17T08:02"), previous, config)
        )
        assertEquals(
            instant("2026-09-17T08:15"),
            computePhoneAlarm(null, true, AlarmMode.FINISHED, null, previous, config)
        )
    }

    @Test fun `no deadline, OVERDUE with no previous phone alarm at all is null`() {
        assertNull(computePhoneAlarm(null, true, AlarmMode.OVERDUE, instant("2026-09-17T08:02"), null, config))
    }
}
