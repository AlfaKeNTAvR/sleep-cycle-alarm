package com.nikita.sleepcycle.night

// File purpose: unit tests for AwakeningLog.kt's pure detection, built from the actual segments the owner's
// real night-20260920-0104.jsonl recorded (the LIGHT/DEEP/AWAKE marks around its first awakening, 06:15-06:19)
// so this is checked against real band data, not just a hand-built fixture.

import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.engine.SegmentKind
import com.nikita.sleepcycle.engine.SleepSegment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class AwakeningDetectionTest {
    private val config = EngineConfig()

    /** Accepts "HH:mm" or "HH:mm:ss" and parses it as an instant on the real log's own date, so the test's own instants read like clock times. */
    private fun at(clockTime: String): Instant {
        val normalized = if (clockTime.count { it == ':' } == 1) "$clockTime:00" else clockTime
        return Instant.parse("2026-09-20T${normalized}Z")
    }

    private val lightBeforeAwakening = SleepSegment(at("05:39"), at("06:00"), SegmentKind.LIGHT)
    private val deepBeforeAwakening = SleepSegment(at("06:00"), at("06:15"), SegmentKind.DEEP)
    private val firstAwakeMark = SleepSegment(at("06:15"), at("06:17"), SegmentKind.AWAKE)
    private val lightAfterAwakening = SleepSegment(at("06:19"), at("06:45"), SegmentKind.LIGHT)

    @Test
    fun `an awake mark with no sleep resumed yet is reported as just started, once`() {
        val previous = listOf(lightBeforeAwakening, deepBeforeAwakening)
        val current = previous + firstAwakeMark
        val now = at("06:31")

        val delta = detectAwakeningsThisTick(previous, current, now, config, wakeAlarmFiredAt = null)

        assertEquals(at("06:15"), delta.justStartedAt)
        assertTrue(delta.closed.isEmpty(), "sleep has not resumed yet, so nothing has closed")
    }

    @Test
    fun `the same still-open awakening is not reported as started again on the next tick`() {
        val previous = listOf(lightBeforeAwakening, deepBeforeAwakening, firstAwakeMark)
        val current = previous // no new data this tick, still awake
        val now = at("06:33")

        val delta = detectAwakeningsThisTick(previous, current, now, config, wakeAlarmFiredAt = null)

        assertNull(delta.justStartedAt, "already reported as started on an earlier tick")
        assertTrue(delta.closed.isEmpty())
    }

    @Test
    fun `sleep resuming after the awake mark closes it, with the real 4-minute span from the log`() {
        val previous = listOf(lightBeforeAwakening, deepBeforeAwakening, firstAwakeMark)
        val current = previous + lightAfterAwakening
        val now = at("06:46")

        val delta = detectAwakeningsThisTick(previous, current, now, config, wakeAlarmFiredAt = null)

        assertEquals(1, delta.closed.size)
        val awakening = delta.closed.single()
        assertEquals(at("06:15"), awakening.startedAt)
        assertEquals(at("06:19"), awakening.endedAt)
        assertEquals(Duration.ofMinutes(4), awakening.duration, "the real log's first awakening was 4 minutes")
        assertNull(delta.justStartedAt, "the awakening that just closed is not also reported as newly opened")
    }

    @Test
    fun `an awakening that started and closed within the same tick reports only the closed event`() {
        val previous = listOf(lightBeforeAwakening, deepBeforeAwakening)
        val current = previous + firstAwakeMark + lightAfterAwakening
        val now = at("06:46")

        val delta = detectAwakeningsThisTick(previous, current, now, config, wakeAlarmFiredAt = null)

        assertEquals(1, delta.closed.size)
        assertEquals(Duration.ofMinutes(4), delta.closed.single().duration)
        assertNull(delta.justStartedAt, "sleep already resumed this same tick, nothing is left open")
    }

    @Test
    fun `an awakening starting after the wake alarm fired is flagged afterWakeAlarm, one before it is not`() {
        val previous = listOf(lightBeforeAwakening, deepBeforeAwakening)
        val current = previous + firstAwakeMark + lightAfterAwakening
        val now = at("06:46")
        val wakeAlarmFiredBeforeThisAwakening = at("06:10")
        val wakeAlarmFiredAfterThisAwakening = at("06:16")

        val beforeAlarm = detectAwakeningsThisTick(previous, current, now, config, wakeAlarmFiredAt = wakeAlarmFiredAfterThisAwakening)
        val afterAlarm = detectAwakeningsThisTick(previous, current, now, config, wakeAlarmFiredAt = wakeAlarmFiredBeforeThisAwakening)

        assertEquals(false, beforeAlarm.closed.single().afterWakeAlarm, "the awakening started at 06:15, before the alarm fired at 06:16")
        assertEquals(true, afterAlarm.closed.single().afterWakeAlarm, "the awakening started at 06:15, at/after the alarm fired at 06:10")
    }

    @Test
    fun `a blip shorter than minAwakening never opens or closes an awakening`() {
        val blip = SleepSegment(at("06:15"), at("06:15:30"), SegmentKind.AWAKE)
        val previous = listOf(lightBeforeAwakening, deepBeforeAwakening)
        val current = previous + blip
        val now = at("06:20")

        val delta = detectAwakeningsThisTick(previous, current, now, config, wakeAlarmFiredAt = null)

        assertNull(delta.justStartedAt, "a 30 s blip is below EngineConfig's default 1-minute floor")
        assertTrue(delta.closed.isEmpty())
    }

    @Test
    fun `a retroactively-reported awake mark that splits an already-logged stretch closes as newly learned`() {
        // Tick 1: the band has only reported one continuous run, no split yet.
        val singleRun = listOf(lightBeforeAwakening, deepBeforeAwakening, lightAfterAwakening)
        // Tick 2: a late-arriving AWAKE mark retroactively splits it - the same real 06:15-06:19 gap.
        val splitRun = singleRun + firstAwakeMark
        val now = at("06:50")

        val delta = detectAwakeningsThisTick(singleRun, splitRun, now, config, wakeAlarmFiredAt = null)

        assertEquals(1, delta.closed.size, "the split is learned about this tick, so it is reported now")
        assertEquals(Duration.ofMinutes(4), delta.closed.single().duration)
    }
}
