package com.nikita.sleepcycle.night

// File purpose: U6 - the pre-nudge check's simulated-data seam. On a simulated night the check must read the
// Debug screen's own timeline rather than re-syncing the real band, otherwise the nudge's cancel path can
// never be exercised in debug mode at all (a virtual `now` against real band timestamps always reads stale).
// See OutOfBedPreNudgeCheck.simulatedSleepStateAt and its caller.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.engine.SleepState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class PreNudgeSimulatedDataTest {
    private val config = EngineConfig()
    private val now: Instant = Instant.parse("2026-09-21T07:15:00Z")

    private fun asleepAt(minutesAgo: Long) =
        SimulatedSleepEvent(SimulatedSleepEventKind.ASLEEP, now.minus(Duration.ofMinutes(minutesAgo)))

    private fun awakeAt(minutesAgo: Long) =
        SimulatedSleepEvent(SimulatedSleepEventKind.AWAKE, now.minus(Duration.ofMinutes(minutesAgo)))

    // L2.2: none of these tests are about the night's own plan mode, so they all pass a plain non-FINISHED
    // mode (FULL_CYCLES) - see OutOfBedNudgeSupersessionTest.kt's own L2.2 section for the mode half.

    @Test
    fun `an untouched simulator reads as nothing known, so the nudge still rings`() {
        assertNull(simulatedSleepStateAt(emptyList(), now, config))
        assertFalse(shouldCancelNudgeForPreCheck(simulatedSleepStateAt(emptyList(), now, config), AlarmMode.FULL_CYCLES))
    }

    @Test
    fun `an open asleep segment reads as ASLEEP, which cancels the nudge`() {
        val state = simulatedSleepStateAt(listOf(asleepAt(30)), now, config)
        assertEquals(SleepState.ASLEEP, state)
        assertTrue(shouldCancelNudgeForPreCheck(state, AlarmMode.FULL_CYCLES))
    }

    @Test
    fun `an open awake segment does not cancel the nudge`() {
        val state = simulatedSleepStateAt(listOf(asleepAt(60), awakeAt(10)), now, config)
        assertFalse(shouldCancelNudgeForPreCheck(state, AlarmMode.FULL_CYCLES))
    }

    @Test
    fun `falling back asleep after the alarm cancels the nudge`() {
        // The owner's own nap case: awake when the alarm rang, asleep again well before the nudge is due.
        val events = listOf(asleepAt(300), awakeAt(40), asleepAt(25))
        val state = simulatedSleepStateAt(events, now, config)
        assertEquals(SleepState.ASLEEP, state)
        assertTrue(shouldCancelNudgeForPreCheck(state, AlarmMode.FULL_CYCLES))
    }
}
