package com.nikita.sleepcycle.night

// File purpose: H1 - the one pure latch rule NightOrchestrator.runNightTickLocked and NightController.startNight
// both use, so the fix (the night's own morning alarm time, set once from a FULL_CYCLES/DEADLINE_ONLY plan and
// never rewritten by a NAP/FINISHED one) is pinned independently of the sequence tests in
// WholeMorningSequenceTest.kt.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.AlarmPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class LatchMorningAlarmAtTest {
    private fun plan(mode: AlarmMode, wakeAt: Instant?) =
        AlarmPlan(mode, wakeAt, 0, Instant.parse("2026-09-17T00:30:00Z"), false, "r")

    @Test
    fun `a FULL_CYCLES plan latches its own wakeAt`() {
        val wakeAt = Instant.parse("2026-09-17T06:45:00Z")
        assertEquals(wakeAt, latchMorningAlarmAt(previous = null, plan(AlarmMode.FULL_CYCLES, wakeAt)))
    }

    @Test
    fun `a DEADLINE_ONLY plan also latches its own wakeAt`() {
        val wakeAt = Instant.parse("2026-09-17T08:00:00Z")
        assertEquals(wakeAt, latchMorningAlarmAt(previous = null, plan(AlarmMode.DEADLINE_ONLY, wakeAt)))
    }

    @Test
    fun `a NAP plan never overwrites the latch - not even with its own wakeAt, not even from null`() {
        val previous = Instant.parse("2026-09-17T06:45:00Z")
        assertEquals(previous, latchMorningAlarmAt(previous, plan(AlarmMode.NAP, Instant.parse("2026-09-17T06:50:00Z"))))
        assertNull(latchMorningAlarmAt(previous = null, plan(AlarmMode.NAP, Instant.parse("2026-09-17T06:50:00Z"))))
    }

    @Test
    fun `a FINISHED plan never overwrites the latch either`() {
        val previous = Instant.parse("2026-09-17T06:45:00Z")
        assertEquals(previous, latchMorningAlarmAt(previous, plan(AlarmMode.FINISHED, wakeAt = null)))
    }

    @Test
    fun `a later FULL_CYCLES plan re-latches to its own newer wakeAt - a genuine restart, not a slid nap`() {
        val previous = Instant.parse("2026-09-17T06:45:00Z")
        val newer = Instant.parse("2026-09-17T08:15:00Z")
        assertEquals(newer, latchMorningAlarmAt(previous, plan(AlarmMode.FULL_CYCLES, newer)))
    }

    @Test
    fun `H8 a FULL_CYCLES plan with no alarm left to arm keeps the latch instead of erasing it`() {
        // Since H8 a FULL_CYCLES or DEADLINE_ONLY plan can carry a null wakeAt: the morning alarm has already
        // rung, so there is nothing left to arm (WakeAlarm.kt's morningAlarmAlreadyRang). Latching that null
        // would throw away the one fact rule 7's AWAKE branch still has when a firing goes unrecorded.
        val previous = Instant.parse("2026-09-17T06:45:00Z")
        assertEquals(previous, latchMorningAlarmAt(previous, plan(AlarmMode.FULL_CYCLES, wakeAt = null)))
        assertEquals(previous, latchMorningAlarmAt(previous, plan(AlarmMode.DEADLINE_ONLY, wakeAt = null)))
    }
}
