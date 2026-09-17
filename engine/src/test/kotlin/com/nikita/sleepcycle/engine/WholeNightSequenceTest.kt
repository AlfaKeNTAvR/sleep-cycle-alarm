package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Replays a night as a series of syncs, feeding each plan back in as `previousPlan`, per the engine's real call shape. */
class WholeNightSequenceTest {
    private fun plan(
        segments: List<SleepSegment>, setting: NightSettings, now: String, previous: AlarmPlan?
    ) = computeAlarmPlan(segments, setting, instant(now), previous, testZone, EngineConfig())

    @Test fun `sleeping through the alarm escalates to OVERDUE and locks the phone backup`() {
        val night = listOf(segment("2026-09-17T00:30", "2026-09-17T08:14", SegmentKind.LIGHT))
        val setting = settings(cycles = 5, backup = true)

        val established = plan(night, setting, "2026-09-17T07:00", null)
        assertEquals(AlarmMode.FULL_CYCLES, established.mode)
        assertEquals("08:00", formatTime(established.bandAlarm!!, testZone))
        assertEquals("08:15", formatTime(established.phoneAlarm!!, testZone))

        val firstOverdue = plan(night, setting, "2026-09-17T08:05", established)
        assertEquals(AlarmMode.OVERDUE, firstOverdue.mode)
        assertEquals("08:07", formatTime(firstOverdue.bandAlarm!!, testZone))
        assertEquals("08:15", formatTime(firstOverdue.phoneAlarm!!, testZone))

        val staysPut = plan(night, setting, "2026-09-17T08:06", firstOverdue)
        assertEquals(AlarmMode.OVERDUE, staysPut.mode)
        assertEquals("08:07", formatTime(staysPut.bandAlarm!!, testZone))
        assertEquals("08:15", formatTime(staysPut.phoneAlarm!!, testZone))

        val movesOn = plan(night, setting, "2026-09-17T08:10", staysPut)
        assertEquals(AlarmMode.OVERDUE, movesOn.mode)
        assertEquals("08:12", formatTime(movesOn.bandAlarm!!, testZone))
        assertEquals("08:15", formatTime(movesOn.phoneAlarm!!, testZone))
    }

    @Test fun `a full night with a brief awakening restarts the count, then overdues, then finishes at the deadline`() {
        val setting = settings(deadline = "2026-09-17T08:30", cycles = 5)

        val fallsAsleep = plan(listOf(segment("2026-09-17T00:30", "2026-09-17T00:45", SegmentKind.LIGHT)), setting, "2026-09-17T00:45", null)
        assertEquals(AlarmMode.FULL_CYCLES, fallsAsleep.mode)
        assertEquals("08:00", formatTime(fallsAsleep.bandAlarm!!, testZone))

        val wakesBriefly = plan(
            listOf(segment("2026-09-17T00:30", "2026-09-17T06:30", SegmentKind.LIGHT), segment("2026-09-17T06:30", "2026-09-17T06:35", SegmentKind.AWAKE)),
            setting, "2026-09-17T06:35", fallsAsleep
        )
        assertEquals(AlarmMode.FULL_CYCLES, wakesBriefly.mode)
        assertEquals("08:20", formatTime(wakesBriefly.bandAlarm!!, testZone))

        val fallsBackAsleep = plan(
            listOf(
                segment("2026-09-17T00:30", "2026-09-17T06:30", SegmentKind.LIGHT),
                segment("2026-09-17T06:30", "2026-09-17T06:36", SegmentKind.AWAKE),
                segment("2026-09-17T06:36", "2026-09-17T06:45", SegmentKind.LIGHT)
            ),
            setting, "2026-09-17T06:45", wakesBriefly
        )
        assertEquals(AlarmMode.FULL_CYCLES, fallsBackAsleep.mode)
        assertEquals("08:06", formatTime(fallsBackAsleep.bandAlarm!!, testZone))

        val overdue = plan(
            listOf(
                segment("2026-09-17T00:30", "2026-09-17T06:30", SegmentKind.LIGHT),
                segment("2026-09-17T06:30", "2026-09-17T06:36", SegmentKind.AWAKE),
                segment("2026-09-17T06:36", "2026-09-17T06:45", SegmentKind.LIGHT)
            ),
            setting, "2026-09-17T08:07", fallsBackAsleep
        )
        assertEquals(AlarmMode.OVERDUE, overdue.mode)
        assertEquals("08:09", formatTime(overdue.bandAlarm!!, testZone))

        val finished = plan(
            listOf(
                segment("2026-09-17T00:30", "2026-09-17T06:30", SegmentKind.LIGHT),
                segment("2026-09-17T06:30", "2026-09-17T06:36", SegmentKind.AWAKE),
                segment("2026-09-17T06:36", "2026-09-17T06:45", SegmentKind.LIGHT)
            ),
            setting, "2026-09-17T08:31", overdue
        )
        assertEquals(AlarmMode.FINISHED, finished.mode)
        assertNull(finished.bandAlarm)
        assertNull(finished.referenceOnset)
    }
}
