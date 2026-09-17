package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

class ComputeAlarmPlanTest {
    private fun plan(
        segments: List<SleepSegment>, setting: NightSettings, now: String, previous: AlarmPlan? = null
    ) = computeAlarmPlan(segments, setting, instant(now), previous, testZone, EngineConfig())

    @Test fun `no deadline uses the picked cycles from the reference onset`() {
        val asleep = listOf(segment("2026-09-17T00:30", "2026-09-17T01:00", SegmentKind.LIGHT))
        val result = plan(asleep, settings(cycles = 5), "2026-09-17T01:01")
        assertEquals(AlarmMode.FULL_CYCLES, result.mode)
        assertEquals("08:00", formatTime(result.bandAlarm!!, testZone))
    }

    @Test fun `no deadline, phone backup enabled adds the offset after the band alarm`() {
        val asleep = listOf(segment("2026-09-17T00:30", "2026-09-17T01:00", SegmentKind.LIGHT))
        assertEquals("08:15", formatTime(plan(asleep, settings(cycles = 5, backup = true), "2026-09-17T01:01").phoneAlarm!!, testZone))
    }

    @Test fun `no deadline, phone backup disabled leaves the phone alarm null`() {
        val asleep = listOf(segment("2026-09-17T00:30", "2026-09-17T01:00", SegmentKind.LIGHT))
        assertNull(plan(asleep, settings(cycles = 5, backup = false), "2026-09-17T01:01").phoneAlarm)
    }

    @Test fun `a cycle ending exactly at the deadline fits`() {
        val asleep = listOf(segment("2026-09-17T00:00", "2026-09-17T00:05", SegmentKind.LIGHT))
        val result = plan(asleep, settings("2026-09-17T01:30", cycles = 3), "2026-09-17T00:06")
        assertEquals(AlarmMode.FULL_CYCLES, result.mode)
        assertEquals(1, result.cycles)
        assertEquals("01:30", formatTime(result.bandAlarm!!, testZone))
    }

    @Test fun `one minute past the boundary does not fit`() {
        val asleep = listOf(segment("2026-09-17T00:00", "2026-09-17T00:05", SegmentKind.LIGHT))
        val result = plan(asleep, settings("2026-09-17T01:29", cycles = 3), "2026-09-17T00:06")
        assertEquals(AlarmMode.DEADLINE_ONLY, result.mode)
        assertEquals(0, result.cycles)
        assertEquals("01:29", formatTime(result.bandAlarm!!, testZone))
    }

    @Test fun `the projected onset slides with now, dropping a cycle once it stops fitting`() {
        val early = plan(emptyList(), settings("2026-09-17T08:30", cycles = 6), "2026-09-17T00:40")
        assertEquals(5, early.cycles)
        assertEquals("08:25", formatTime(early.bandAlarm!!, testZone))
        val later = plan(emptyList(), settings("2026-09-17T08:30", cycles = 6), "2026-09-17T01:30")
        assertEquals(4, later.cycles)
        assertEquals("07:45", formatTime(later.bandAlarm!!, testZone))
    }

    @Test fun `first sleep too close to the deadline is DEADLINE_ONLY, not a nap`() {
        val asleep = listOf(segment("2026-09-17T07:30", "2026-09-17T07:31", SegmentKind.LIGHT))
        val result = plan(asleep, settings("2026-09-17T08:30"), "2026-09-17T07:31")
        assertEquals(AlarmMode.DEADLINE_ONLY, result.mode)
        assertEquals("08:30", formatTime(result.bandAlarm!!, testZone))
    }

    @Test fun `an awakening with a full cycle still fitting before the deadline restarts FULL_CYCLES`() {
        val segments = listOf(
            segment("2026-09-17T00:00", "2026-09-17T06:30", SegmentKind.LIGHT),
            segment("2026-09-17T06:30", "2026-09-17T06:45", SegmentKind.AWAKE),
            segment("2026-09-17T06:45", "2026-09-17T06:50", SegmentKind.LIGHT)
        )
        val result = plan(segments, settings("2026-09-17T08:30"), "2026-09-17T06:50")
        assertEquals(AlarmMode.FULL_CYCLES, result.mode)
        assertEquals("08:15", formatTime(result.bandAlarm!!, testZone))
    }

    @Test fun `no deadline, an awakening with an established plan restarts the full count`() {
        val previous = AlarmPlan(
            AlarmMode.FULL_CYCLES, instant("2026-09-17T05:30"), null, 5, instant("2026-09-17T22:00"), false,
            instant("2026-09-17T05:30"), "r"
        )
        val segments = listOf(
            segment("2026-09-16T22:00", "2026-09-17T03:00", SegmentKind.LIGHT),
            segment("2026-09-17T03:00", "2026-09-17T03:06", SegmentKind.AWAKE)
        )
        val result = plan(segments, settings(cycles = 5), "2026-09-17T03:10", previous)
        assertEquals(AlarmMode.FULL_CYCLES, result.mode)
        assertEquals("10:55", formatTime(result.bandAlarm!!, testZone))
    }

    @Test fun `no deadline nap mode slides while awake, then fixes once asleep`() {
        // Each round's "now" stays before the previous round's band alarm: once state is AWAKE and now
        // reaches or passes that alarm, rule 1 (already awake at alarm time) ends the night, by design.
        val previous = AlarmPlan(
            AlarmMode.FULL_CYCLES, instant("2026-09-17T08:00"), null, 5, instant("2026-09-17T00:30"), false,
            instant("2026-09-17T08:00"), "r"
        )
        val segments = listOf(
            segment("2026-09-17T00:30", "2026-09-17T07:00", SegmentKind.LIGHT),
            segment("2026-09-17T07:00", "2026-09-17T07:05", SegmentKind.AWAKE)
        )
        val awake = plan(segments, settings(cycles = 5), "2026-09-17T07:05", previous)
        assertEquals(AlarmMode.NAP, awake.mode)
        assertEquals("07:25", formatTime(awake.bandAlarm!!, testZone))

        val stillAwake = plan(segments, settings(cycles = 5), "2026-09-17T07:15", awake)
        assertEquals(AlarmMode.NAP, stillAwake.mode)
        assertEquals("07:35", formatTime(stillAwake.bandAlarm!!, testZone))

        val backAsleep = segments + segment("2026-09-17T07:16", "2026-09-17T07:20", SegmentKind.LIGHT)
        val asleepAgain = plan(backAsleep, settings(cycles = 5), "2026-09-17T07:20", stillAwake)
        assertEquals(AlarmMode.NAP, asleepAgain.mode)
        assertEquals("07:36", formatTime(asleepAgain.bandAlarm!!, testZone))

        val stillAsleep = plan(
            backAsleep + segment("2026-09-17T07:20", "2026-09-17T07:30", SegmentKind.LIGHT),
            settings(cycles = 5), "2026-09-17T07:30", asleepAgain
        )
        assertEquals(AlarmMode.NAP, stillAsleep.mode)
        assertEquals("07:36", formatTime(stillAsleep.bandAlarm!!, testZone))
    }

    @Test fun `late nap detection does not keep a stale alarm from a non-overdue previous plan`() {
        val previous = AlarmPlan(
            AlarmMode.NAP, instant("2026-09-17T01:20"), null, 0, instant("2026-09-17T00:30"), false,
            instant("2026-09-17T01:20"), "r"
        )
        val segments = listOf(
            segment("2026-09-17T00:00", "2026-09-17T00:30", SegmentKind.LIGHT),
            segment("2026-09-17T00:30", "2026-09-17T00:50", SegmentKind.AWAKE),
            segment("2026-09-17T00:50", "2026-09-17T01:15", SegmentKind.LIGHT)
        )
        val result = plan(segments, settings(cycles = 5), "2026-09-17T01:15", previous)
        assertEquals(AlarmMode.OVERDUE, result.mode)
        assertEquals("01:17", formatTime(result.bandAlarm!!, testZone))
    }

    @Test fun `deadline exactly at now is FINISHED without throwing`() {
        val result = plan(listOf(segment("2026-09-17T00:30", "2026-09-17T01:00", SegmentKind.LIGHT)), settings("2026-09-17T08:31"), "2026-09-17T08:31")
        assertEquals(AlarmMode.FINISHED, result.mode)
        assertNull(result.bandAlarm)
    }

    @Test fun `a deadline 30 seconds away never produces an alarm closer than minAlarmLead`() {
        val asleep = listOf(segment("2026-09-17T07:00", "2026-09-17T07:30", SegmentKind.LIGHT))
        val result = plan(asleep, settings("2026-09-17T08:29:30"), "2026-09-17T08:29:00")
        assertEquals(AlarmMode.FINISHED, result.mode)
        assertNull(result.bandAlarm)
    }

    @Test fun `an awake mark after the previous band alarm is FINISHED`() {
        val previous = AlarmPlan(
            AlarmMode.FULL_CYCLES, instant("2026-09-17T08:00"), null, 5, instant("2026-09-17T00:30"), false,
            instant("2026-09-17T08:00"), "r"
        )
        val segments = listOf(
            segment("2026-09-17T00:30", "2026-09-17T08:00", SegmentKind.LIGHT),
            segment("2026-09-17T08:00", "2026-09-17T08:05", SegmentKind.AWAKE)
        )
        val result = plan(segments, settings(cycles = 5), "2026-09-17T08:05", previous)
        assertEquals(AlarmMode.FINISHED, result.mode)
        assertNull(result.bandAlarm)
        assertNull(result.referenceOnset)
    }

    @Test fun `a night crossing midnight computes correctly`() {
        val asleep = listOf(segment("2026-09-16T23:05", "2026-09-16T23:10", SegmentKind.LIGHT))
        val result = plan(asleep, settings("2026-09-17T07:00", cycles = 4), "2026-09-16T23:10")
        assertEquals(AlarmMode.FULL_CYCLES, result.mode)
        assertEquals("05:05", formatTime(result.bandAlarm!!, testZone))
    }

    @Test fun `empty, unsorted, overlapping and zero-length segments never throw`() {
        val segments = listOf(
            segment("2026-09-17T01:00", "2026-09-17T02:00", SegmentKind.LIGHT),
            segment("2026-09-17T00:30", "2026-09-17T00:30", SegmentKind.AWAKE),
            segment("2026-09-17T00:00", "2026-09-17T01:30", SegmentKind.DEEP)
        )
        assertDoesNotThrow { plan(emptyList(), settings(cycles = 3), "2026-09-17T01:00") }
        assertDoesNotThrow { plan(segments, settings(cycles = 3), "2026-09-17T02:00") }
    }
}
