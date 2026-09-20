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

    @Test
    fun `at every offered speed, a live ClockWarp means in-process - 1x with no warp at all is the only case that does not`() {
        val anchor = Instant.parse("2026-09-17T20:00:00Z")
        // 1x with a genuine ClockWarp object (U2's jump-at-1x state) is STILL warped - AppClock.warp() != null
        // is the honest test, not "is speed 1". Only warp == null (real time, never touched) stays off the
        // in-process path.
        for (speed in SIMULATION_SPEEDS) {
            val warp: ClockWarp? = ClockWarp(speed, anchor, anchor)
            assertTrue(shouldScheduleTickInProcess(warp != null), "speed $speed with a live warp must be in-process")
        }
        val noWarp: ClockWarp? = null
        assertFalse(shouldScheduleTickInProcess(noWarp != null), "no warp at all must stay on AlarmManager")
    }

    @Test
    fun `at 1x-unwarped, both real EngineConfig sync delays stay conceptually on the AlarmManager path`() {
        // T6's own invariant, still pinned: at 1x unwarped (AppClock.warp() == null, since U1 guarantees a warp
        // can only be live while simulatedBandData is on, and a real night never sets that), the predicate must
        // be false regardless of how long either real sync delay is - the in-process path is dead code on a
        // real night. EngineConfig's own delays are read here only to document that they are real numbers this
        // invariant actually has to hold for, not because the (now duration-free) predicate consults them.
        val config = EngineConfig()
        check(config.frequentSyncDelay > Duration.ZERO && config.normalSyncDelay > Duration.ZERO)
        assertFalse(shouldScheduleTickInProcess(warped = false))
    }

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
