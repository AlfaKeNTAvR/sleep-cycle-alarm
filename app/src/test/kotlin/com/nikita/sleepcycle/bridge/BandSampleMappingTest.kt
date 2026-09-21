package com.nikita.sleepcycle.bridge

import com.nikita.sleepcycle.engine.SegmentKind
import com.nikita.sleepcycle.engine.SleepSegment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

private const val DEVICE_ID = 1
private const val OTHER_DEVICE_ID = 2

class BandSampleMappingTest {
    @Test
    fun `maps light deep and awake rows to their segment kinds`() {
        val samples = listOf(
            sample(start = 1000, end = 2000, rawKind = 6, source = SOURCE_SLEEP),
            sample(start = 2000, end = 2500, rawKind = 7, source = SOURCE_SLEEP),
            sample(start = 2500, end = 2600, rawKind = 8, source = SOURCE_SLEEP)
        )

        val segments = mapRawSamplesToSleepSegments(samples, DEVICE_ID)

        assertEquals(3, segments.size)
        assertEquals(SegmentKind.LIGHT, segments[0].kind)
        assertEquals(SegmentKind.DEEP, segments[1].kind)
        assertEquals(SegmentKind.AWAKE, segments[2].kind)
        assertEquals(Instant.ofEpochSecond(1000), segments[0].start)
        assertEquals(Instant.ofEpochSecond(2000), segments[0].end)
    }

    @Test
    fun `drops the mirrored reverse-order duplicate of a start end pair`() {
        val samples = listOf(
            sample(start = 1000, end = 2000, rawKind = 6, source = SOURCE_SLEEP),
            sample(start = 2000, end = 1000, rawKind = 6, source = SOURCE_SLEEP)
        )

        val segments = mapRawSamplesToSleepSegments(samples, DEVICE_ID)

        assertEquals(1, segments.size)
    }

    @Test
    fun `drops rows of an unknown raw kind`() {
        val samples = listOf(sample(start = 1000, end = 2000, rawKind = 99, source = SOURCE_SLEEP))

        assertTrue(mapRawSamplesToSleepSegments(samples, DEVICE_ID).isEmpty())
    }

    @Test
    fun `drops rows that are not SOURCE 13 sleep rows`() {
        val samples = listOf(sample(start = 1000, end = 2000, rawKind = 6, source = SOURCE_HEART_RATE))

        assertTrue(mapRawSamplesToSleepSegments(samples, DEVICE_ID).isEmpty())
    }

    @Test
    fun `drops rows belonging to a different device`() {
        val samples = listOf(sample(start = 1000, end = 2000, rawKind = 6, source = SOURCE_SLEEP, deviceId = OTHER_DEVICE_ID))

        assertTrue(mapRawSamplesToSleepSegments(samples, DEVICE_ID).isEmpty())
    }

    @Test
    fun `newest heart rate sample is the maximum timestamp for the device`() {
        val samples = listOf(
            sample(start = 1000, end = 1000, rawKind = 0, source = SOURCE_HEART_RATE),
            sample(start = 3000, end = 3000, rawKind = 0, source = SOURCE_HEART_RATE),
            sample(start = 2000, end = 2000, rawKind = 0, source = SOURCE_HEART_RATE)
        )

        assertEquals(Instant.ofEpochSecond(3000), newestHeartRateSampleAt(samples, DEVICE_ID))
    }

    @Test
    fun `newest heart rate sample is null when there are none`() {
        assertNull(newestHeartRateSampleAt(emptyList(), DEVICE_ID))
    }

    @Test
    fun `a zero-length pair - start equals end - is dropped`() {
        val samples = listOf(sample(start = 1000, end = 1000, rawKind = 6, source = SOURCE_SLEEP))

        assertTrue(mapRawSamplesToSleepSegments(samples, DEVICE_ID).isEmpty())
    }

    @Test
    fun `rows straddling a since boundary are mapped as given - since filtering is the SQL query's job, not this function's`() {
        // mapRawSamplesToSleepSegments has no `since` parameter: whatever rows it is handed, it maps, whether
        // they started before, straddle, or started after any particular cutoff the caller applied upstream.
        val since = 5000L
        val samples = listOf(
            sample(start = 4000, end = 6000, rawKind = 6, source = SOURCE_SLEEP), // straddles `since`
            sample(start = 100, end = 200, rawKind = 7, source = SOURCE_SLEEP), // entirely before `since`
            sample(start = 6000, end = 7000, rawKind = 8, source = SOURCE_SLEEP) // entirely after `since`
        )

        val segments = mapRawSamplesToSleepSegments(samples, DEVICE_ID)

        assertEquals(3, segments.size)
        assertEquals(Instant.ofEpochSecond(4000), segments[0].start)
        assertTrue(segments[0].start.epochSecond < since && segments[0].end.epochSecond > since)
    }

    private fun sample(start: Long, end: Long, rawKind: Int, source: Int, deviceId: Int = DEVICE_ID) =
        RawActivitySample(timestampSeconds = start, otherTimestampSeconds = end, rawKind = rawKind, source = source, deviceId = deviceId)

    // ---- clipSegmentsToNightStart (J1.6): a segment straddling the night's own start is clipped to it, not
    // dropped whole or kept whole - see the function's own doc for the 40-minutes-late bug this closes. -------

    @Test
    fun `J1_6 the owner's own traced case - dozed off at 22-50, tapped Start night at 23-05, the segment is clipped to 23-05, not dropped or kept whole`() {
        val nightStartedAt = Instant.parse("2026-09-17T23:05:00Z")
        val segments = listOf(SleepSegment(Instant.parse("2026-09-17T22:50:00Z"), Instant.parse("2026-09-17T23:30:00Z"), SegmentKind.LIGHT))

        val clipped = clipSegmentsToNightStart(segments, nightStartedAt)

        assertEquals(1, clipped.size)
        assertEquals(nightStartedAt, clipped[0].start)
        assertEquals(Instant.parse("2026-09-17T23:30:00Z"), clipped[0].end)
    }

    @Test
    fun `a segment entirely before the night's own start is dropped, not clipped to a zero-length remainder`() {
        val nightStartedAt = Instant.parse("2026-09-17T23:05:00Z")
        val segments = listOf(SleepSegment(Instant.parse("2026-09-17T21:00:00Z"), Instant.parse("2026-09-17T22:00:00Z"), SegmentKind.LIGHT))

        assertTrue(clipSegmentsToNightStart(segments, nightStartedAt).isEmpty())
    }

    @Test
    fun `a segment entirely after the night's own start is returned unchanged`() {
        val nightStartedAt = Instant.parse("2026-09-17T23:05:00Z")
        val segment = SleepSegment(Instant.parse("2026-09-17T23:10:00Z"), Instant.parse("2026-09-17T23:40:00Z"), SegmentKind.LIGHT)

        val clipped = clipSegmentsToNightStart(listOf(segment), nightStartedAt)

        assertEquals(listOf(segment), clipped)
    }

    @Test
    fun `a segment ending exactly at the night's own start is dropped - nothing of it falls inside the night`() {
        val nightStartedAt = Instant.parse("2026-09-17T23:05:00Z")
        val segments = listOf(SleepSegment(Instant.parse("2026-09-17T22:50:00Z"), nightStartedAt, SegmentKind.LIGHT))

        assertTrue(clipSegmentsToNightStart(segments, nightStartedAt).isEmpty())
    }

    @Test
    fun `a segment starting exactly at the night's own start is returned unchanged, not re-copied`() {
        val nightStartedAt = Instant.parse("2026-09-17T23:05:00Z")
        val segment = SleepSegment(nightStartedAt, Instant.parse("2026-09-17T23:30:00Z"), SegmentKind.LIGHT)

        assertEquals(listOf(segment), clipSegmentsToNightStart(listOf(segment), nightStartedAt))
    }

    @Test
    fun `multiple segments are each clipped independently - only the straddling one changes`() {
        val nightStartedAt = Instant.parse("2026-09-17T23:05:00Z")
        val before = SleepSegment(Instant.parse("2026-09-17T22:00:00Z"), Instant.parse("2026-09-17T22:30:00Z"), SegmentKind.AWAKE)
        val straddling = SleepSegment(Instant.parse("2026-09-17T22:50:00Z"), Instant.parse("2026-09-17T23:30:00Z"), SegmentKind.LIGHT)
        val after = SleepSegment(Instant.parse("2026-09-17T23:30:00Z"), Instant.parse("2026-09-18T00:00:00Z"), SegmentKind.DEEP)

        val clipped = clipSegmentsToNightStart(listOf(before, straddling, after), nightStartedAt)

        assertEquals(2, clipped.size)
        assertEquals(nightStartedAt, clipped[0].start)
        assertEquals(after, clipped[1])
    }
}
