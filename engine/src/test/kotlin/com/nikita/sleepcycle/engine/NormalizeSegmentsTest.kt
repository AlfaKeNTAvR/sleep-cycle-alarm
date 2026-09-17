package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NormalizeSegmentsTest {
    @Test fun `a mark dated after now is dropped entirely`() {
        val result = normalizeSegments(
            listOf(segment("2026-09-17T00:30", "2026-09-17T01:00", SegmentKind.LIGHT),
                segment("2026-09-17T05:00", "2026-09-17T06:00", SegmentKind.LIGHT)),
            instant("2026-09-17T02:00"), EngineConfig()
        )
        assertEquals(listOf(NormalizedInterval(instant("2026-09-17T00:30"), instant("2026-09-17T01:00"), true)), result)
    }

    @Test fun `a segment still running is clipped to now`() {
        val result = normalizeSegments(
            listOf(segment("2026-09-17T00:30", "2026-09-17T02:00", SegmentKind.LIGHT)),
            instant("2026-09-17T01:00"), EngineConfig()
        )
        assertEquals(listOf(NormalizedInterval(instant("2026-09-17T00:30"), instant("2026-09-17T01:00"), true)), result)
    }

    @Test fun `zero-length and unsorted overlapping segments do not crash and merge cleanly`() {
        val result = normalizeSegments(
            listOf(
                segment("2026-09-17T01:00", "2026-09-17T02:00", SegmentKind.LIGHT),
                segment("2026-09-17T00:30", "2026-09-17T00:30", SegmentKind.LIGHT),
                segment("2026-09-17T00:00", "2026-09-17T01:30", SegmentKind.DEEP)
            ),
            instant("2026-09-17T03:00"), EngineConfig()
        )
        assertEquals(listOf(NormalizedInterval(instant("2026-09-17T00:00"), instant("2026-09-17T02:00"), true)), result)
    }

    @Test fun `empty input produces an empty timeline`() {
        assertEquals(emptyList<NormalizedInterval>(), normalizeSegments(emptyList(), instant("2026-09-17T01:00"), EngineConfig()))
    }

    @Test fun `awake wins over overlapping sleep and sleep reopens after it`() {
        val result = normalizeSegments(
            listOf(
                segment("2026-09-17T00:30", "2026-09-17T04:00", SegmentKind.LIGHT),
                segment("2026-09-17T01:44", "2026-09-17T01:50", SegmentKind.AWAKE)
            ),
            instant("2026-09-17T05:00"), EngineConfig()
        )
        assertEquals(
            listOf(
                NormalizedInterval(instant("2026-09-17T00:30"), instant("2026-09-17T01:44"), true),
                NormalizedInterval(instant("2026-09-17T01:44"), instant("2026-09-17T01:50"), false),
                NormalizedInterval(instant("2026-09-17T01:50"), instant("2026-09-17T04:00"), true)
            ),
            result
        )
    }

    @Test fun `an awake blip shorter than minAwakening does not split the sleep interval`() {
        val result = normalizeSegments(
            listOf(
                segment("2026-09-17T00:00", "2026-09-17T01:00", SegmentKind.LIGHT),
                segment("2026-09-17T00:30", "2026-09-17T00:30:30", SegmentKind.AWAKE)
            ),
            instant("2026-09-17T02:00"), EngineConfig()
        )
        assertEquals(listOf(NormalizedInterval(instant("2026-09-17T00:00"), instant("2026-09-17T01:00"), true)), result)
    }
}
