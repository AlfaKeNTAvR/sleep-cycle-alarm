package com.nikita.sleepcycle.night

// File purpose: T6/V3 - shouldScheduleTickInProcess is the one pure decision seam behind scheduleTick's (and,
// shared, OutOfBedPreNudgeCheck.kt's own V2 routing) choice of AlarmManager vs. an in-process coroutine delay,
// extracted so it is testable without Android.
//
// V3 REPLACES the original duration-threshold version of this predicate (`realDelay < IN_PROCESS_TICK_
// THRESHOLD`) with the honest one: in-process if and only if the clock is warped. The duration threshold left
// 10x stranded - normalSyncDelay (15 virtual min) at 10x is 90 REAL seconds, above the old 60 s threshold, so
// it fell to the Doze-throttled AlarmManager path and could stretch to 9 real minutes, 90 virtual minutes of
// missed syncs. Pinned here at every offered speed, plus the one invariant that must never regress: at
// 1x-unwarped, every real sync delay stays on the AlarmManager path.

import com.nikita.sleepcycle.engine.EngineConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class TickSchedulingTest {
    @Test
    fun `unwarped is never in-process`() {
        assertFalse(shouldScheduleTickInProcess(warped = false))
    }

    @Test
    fun `warped is always in-process`() {
        assertTrue(shouldScheduleTickInProcess(warped = true))
    }

    // Emulator audit, 2026-10-03: two tests that passed the predicate its own answer ("a live warp", "no warp")
    // were removed - they could not fail. The two above pin the predicate; the scenario tests drive real ticks.

    // ---- V3: the in-process delay floor -------------------------------------------------------------------

    @Test
    fun `a real delay at or above the floor is used unchanged`() {
        assertEquals(IN_PROCESS_TICK_MIN_DELAY, inProcessTickDelay(IN_PROCESS_TICK_MIN_DELAY))
        val longer = IN_PROCESS_TICK_MIN_DELAY.plusSeconds(5)
        assertEquals(longer, inProcessTickDelay(longer))
    }

    @Test
    fun `a real delay below the floor - including zero and negative (an overdue tick) - is floored`() {
        assertEquals(IN_PROCESS_TICK_MIN_DELAY, inProcessTickDelay(Duration.ZERO))
        assertEquals(IN_PROCESS_TICK_MIN_DELAY, inProcessTickDelay(Duration.ofMillis(1)))
        assertEquals(IN_PROCESS_TICK_MIN_DELAY, inProcessTickDelay(Duration.ofSeconds(-5)))
    }
}
