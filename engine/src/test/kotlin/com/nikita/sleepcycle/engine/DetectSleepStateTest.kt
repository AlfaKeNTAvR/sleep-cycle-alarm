package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DetectSleepStateTest {
    private fun stateFor(segments: List<SleepSegment>, now: String) =
        detectSleepState(normalizeSegments(segments, instant(now), EngineConfig()))

    @Test fun `no sleep interval at all is NOT_YET_ASLEEP`() {
        assertEquals(SleepState.NOT_YET_ASLEEP, detectSleepState(emptyList()))
    }

    @Test fun `only an awake interval so far is still NOT_YET_ASLEEP`() {
        assertEquals(
            SleepState.NOT_YET_ASLEEP,
            stateFor(listOf(segment("2026-09-17T00:00", "2026-09-17T00:10", SegmentKind.AWAKE)), "2026-09-17T00:10")
        )
    }

    @Test fun `the last interval being sleep is ASLEEP`() {
        assertEquals(
            SleepState.ASLEEP,
            stateFor(listOf(segment("2026-09-17T00:30", "2026-09-17T01:00", SegmentKind.LIGHT)), "2026-09-17T01:01")
        )
    }

    @Test fun `the last interval being awake is AWAKE`() {
        assertEquals(
            SleepState.AWAKE,
            stateFor(
                listOf(
                    segment("2026-09-17T00:30", "2026-09-17T01:00", SegmentKind.LIGHT),
                    segment("2026-09-17T01:00", "2026-09-17T01:01", SegmentKind.AWAKE)
                ),
                "2026-09-17T01:02"
            )
        )
    }

    @Test fun `equal end times give AWAKE regardless of input order`() {
        val forward = listOf(
            segment("2026-09-17T00:30", "2026-09-17T08:14", SegmentKind.LIGHT),
            segment("2026-09-17T08:13", "2026-09-17T08:14", SegmentKind.AWAKE)
        )
        val reversed = forward.reversed()
        assertEquals(SleepState.AWAKE, stateFor(forward, "2026-09-17T08:14"))
        assertEquals(SleepState.AWAKE, stateFor(reversed, "2026-09-17T08:14"))
    }

    @Test fun `an interior awake interval leaves the later sleep interval as ASLEEP`() {
        assertEquals(
            SleepState.ASLEEP,
            stateFor(
                listOf(
                    segment("2026-09-17T01:00", "2026-09-17T02:00", SegmentKind.AWAKE),
                    segment("2026-09-17T01:30", "2026-09-17T03:00", SegmentKind.LIGHT),
                    segment("2026-09-17T01:45", "2026-09-17T01:50", SegmentKind.AWAKE)
                ),
                "2026-09-17T03:00"
            )
        )
    }
}
