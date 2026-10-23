package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/** PER-150: a No-alarm night tracks sleep with no morning alarm, no engine naps, and never finishes by itself. */
class NoAlarmNightTest {
    private val noAlarm = NightSettings(deadline = null, pickedCycles = 5, noAlarm = true)

    private fun plan(
        segments: List<SleepSegment>, now: String, setting: NightSettings = noAlarm,
        wakeAlarmFiredAt: Instant? = null, napAlarmsUsed: Int = 0
    ) = computeAlarmPlan(
        segments, setting, instant(now), morningAlarmAt = null, testZone, EngineConfig(), wakeAlarmFiredAt, napAlarmsUsed,
        lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
    )

    @Test fun `before any sleep it arms nothing and says there is no alarm`() {
        val result = plan(emptyList(), "2026-09-17T00:00")
        assertEquals(AlarmMode.NO_ALARM, result.mode)
        assertNull(result.wakeAt)
        assertEquals(0, result.cycles)
        assertFalse(result.reason.contains("alarm 0"), result.reason)
        assertTrue(result.reason.contains("no alarm"), result.reason)
    }

    @Test fun `asleep it tracks the real onset and still arms nothing`() {
        val asleep = listOf(segment("2026-09-17T00:30", "2026-09-17T03:00", SegmentKind.LIGHT))
        val result = plan(asleep, "2026-09-17T03:01")
        assertEquals(AlarmMode.NO_ALARM, result.mode)
        assertNull(result.wakeAt)
        assertEquals(instant("2026-09-17T00:30"), result.referenceOnset)
        assertFalse(result.onsetIsProjected)
    }

    @Test fun `a return to sleep long past the picked total gets no engine nap`() {
        val segments = listOf(
            segment("2026-09-16T23:00", "2026-09-17T08:00", SegmentKind.LIGHT),
            segment("2026-09-17T08:00", "2026-09-17T08:10", SegmentKind.AWAKE),
            segment("2026-09-17T08:10", "2026-09-17T08:20", SegmentKind.LIGHT)
        )
        val result = plan(segments, "2026-09-17T08:21")
        assertEquals(AlarmMode.NO_ALARM, result.mode)
        assertNull(result.wakeAt)
    }

    @Test fun `it never reaches FINISHED, even with the nap cap spent after I'm up`() {
        val segments = listOf(
            segment("2026-09-16T23:00", "2026-09-17T09:00", SegmentKind.LIGHT),
            segment("2026-09-17T09:00", "2026-09-17T09:10", SegmentKind.AWAKE),
            segment("2026-09-17T09:10", "2026-09-17T09:30", SegmentKind.LIGHT)
        )
        val result = plan(segments, "2026-09-17T13:00", wakeAlarmFiredAt = instant("2026-09-17T09:00"), napAlarmsUsed = MAX_NAP_ALARMS)
        assertEquals(AlarmMode.NO_ALARM, result.mode)
        assertNotNull(nextSyncDelay(result, instant("2026-09-17T13:00"), EngineConfig()), "the night keeps syncing")
    }

    @Test fun `a No-alarm night with a deadline is rejected, the two contradict each other`() {
        assertThrows<IllegalArgumentException> {
            plan(emptyList(), "2026-09-17T00:00", setting = noAlarm.copy(deadline = instant("2026-09-17T08:00")))
        }
    }

    @Test fun `a normal night is unchanged by the flag's default`() {
        val result = plan(emptyList(), "2026-09-17T00:00", setting = settings(cycles = 5))
        assertEquals(AlarmMode.FULL_CYCLES, result.mode)
        assertNotNull(result.wakeAt)
    }
}
