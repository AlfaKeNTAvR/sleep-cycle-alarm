package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildSleepStretchesTest {
    private fun stretchesFor(segments: List<SleepSegment>, now: String) =
        buildSleepStretches(normalizeSegments(segments, instant(now), EngineConfig()))

    @Test fun `night one splits only at meaningful awake marks and marks followsAwakening`() {
        val result = stretchesFor(
            listOf(
                segment("2026-09-17T00:30", "2026-09-17T01:44", SegmentKind.LIGHT),
                segment("2026-09-17T01:44", "2026-09-17T01:50", SegmentKind.AWAKE),
                segment("2026-09-17T01:50", "2026-09-17T02:29", SegmentKind.DEEP),
                segment("2026-09-17T02:29", "2026-09-17T02:33", SegmentKind.AWAKE),
                segment("2026-09-17T02:33", "2026-09-17T04:47", SegmentKind.LIGHT),
                segment("2026-09-17T04:47", "2026-09-17T04:49", SegmentKind.AWAKE),
                segment("2026-09-17T05:05", "2026-09-17T08:13", SegmentKind.DEEP),
                segment("2026-09-17T08:13", "2026-09-17T08:14", SegmentKind.AWAKE)
            ),
            "2026-09-17T08:14"
        )
        assertEquals(listOf("00:30", "01:50", "02:33", "05:05"), result.map { formatTime(it.onset, testZone) })
        assertEquals(listOf(false, true, true, true), result.map { it.followsAwakening })
    }

    @Test fun `a data gap alone does not split the stretch`() {
        val result = stretchesFor(
            listOf(
                segment("2026-09-17T00:30", "2026-09-17T01:00", SegmentKind.DEEP),
                segment("2026-09-17T01:16", "2026-09-17T02:30", SegmentKind.DEEP)
            ),
            "2026-09-17T03:00"
        )
        assertEquals(1, result.size)
        assertEquals("00:30", formatTime(result.single().onset, testZone))
        assertEquals("02:30", formatTime(result.single().end, testZone))
    }

    @Test fun `awake carves a stretch into two with the reopened piece starting after it`() {
        val result = stretchesFor(
            listOf(
                segment("2026-09-17T00:30", "2026-09-17T04:00", SegmentKind.LIGHT),
                segment("2026-09-17T01:44", "2026-09-17T01:50", SegmentKind.AWAKE)
            ),
            "2026-09-17T05:00"
        )
        assertEquals(listOf("00:30" to "01:44", "01:50" to "04:00"), result.map { formatTime(it.onset, testZone) to formatTime(it.end, testZone) })
    }

    @Test fun `a stray awake mark before the first stretch does not count as followsAwakening`() {
        val result = stretchesFor(
            listOf(
                segment("2026-09-17T01:00", "2026-09-17T02:00", SegmentKind.AWAKE),
                segment("2026-09-17T01:30", "2026-09-17T03:00", SegmentKind.LIGHT),
                segment("2026-09-17T01:45", "2026-09-17T01:50", SegmentKind.AWAKE)
            ),
            "2026-09-17T03:00"
        )
        assertEquals(1, result.size)
        assertEquals("02:00", formatTime(result.single().onset, testZone))
        assertFalse(result.single().followsAwakening)
    }

    @Test fun `a second stretch after a real awakening is marked followsAwakening`() {
        val result = stretchesFor(
            listOf(
                segment("2026-09-17T00:00", "2026-09-17T01:00", SegmentKind.LIGHT),
                segment("2026-09-17T01:00", "2026-09-17T01:05", SegmentKind.AWAKE),
                segment("2026-09-17T01:05", "2026-09-17T02:00", SegmentKind.LIGHT)
            ),
            "2026-09-17T02:00"
        )
        assertEquals(2, result.size)
        assertTrue(result.last().followsAwakening)
    }

    @Test fun `empty timeline produces no stretches`() {
        assertEquals(emptyList<SleepStretch>(), buildSleepStretches(emptyList()))
    }
}
