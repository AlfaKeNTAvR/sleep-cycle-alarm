package com.nikita.sleepcycle.night

// File purpose: owner spec, 2026-10-21 (PER-126) - a night started by mistake, or with the wrong setting, is
// cheap to undo: under 1 h after Start night, End night or I'm up discards it. "If the sleep was shorter than 1
// hour, then it shouldn't ask me any questions... doesn't show me the final screen... and doesn't save the log
// because it doesn't matter unless it was a simulation." The hour counts from Start night on the night's own
// clock (wall clock, or the simulated clock in a simulation), not from when he fell asleep.

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ShortNightTest {
    private val startedAt = Instant.parse("2026-10-21T22:30:00Z")

    @Test
    fun `a night ended a few minutes after Start night is discarded`() {
        assertTrue(isShortNight(startedAt, startedAt.plus(Duration.ofMinutes(5))))
    }

    @Test
    fun `a night ended one minute short of the hour is still discarded`() {
        assertTrue(isShortNight(startedAt, startedAt.plus(Duration.ofMinutes(59))))
    }

    @Test
    fun `a night ended exactly one hour after Start night is kept, as before`() {
        assertFalse(isShortNight(startedAt, startedAt.plus(Duration.ofHours(1))))
    }

    @Test
    fun `a whole night is kept, as before`() {
        assertFalse(isShortNight(startedAt, startedAt.plus(Duration.ofHours(8))))
    }

    @Test
    fun `a real night discards its log, a simulated night keeps it`() {
        assertFalse(keepsDiscardedNightLog(DebugOptions()))
        assertTrue(keepsDiscardedNightLog(DebugOptions(simulatedBandData = true)))
    }
}
