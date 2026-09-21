package com.nikita.sleepcycle.bridge

// File purpose: pure mapping from raw Gadgetbridge activity-sample rows to engine types. Kept apart from
// SQLite so the row-to-segment logic is unit testable on the JVM without a database.

import com.nikita.sleepcycle.engine.SegmentKind
import com.nikita.sleepcycle.engine.SleepSegment
import java.time.Instant

const val SOURCE_HEART_RATE = 11
const val SOURCE_SLEEP = 13

private const val RAW_KIND_LIGHT = 6
private const val RAW_KIND_DEEP = 7
private const val RAW_KIND_AWAKE = 8

/** One row of `HUAWEI_ACTIVITY_SAMPLE`, in plain types so it needs no Android/SQLite import to test. */
data class RawActivitySample(
    val timestampSeconds: Long,
    val otherTimestampSeconds: Long,
    val rawKind: Int,
    val source: Int,
    val deviceId: Int
)

/**
 * Keeps sleep rows (`SOURCE = 13`) for [deviceId], drops the mirrored reverse-order duplicate of each
 * start/end pair, drops unknown raw kinds, and maps the rest to [SleepSegment]. Findings.md documents the
 * mirrored-pair and raw-kind facts this depends on.
 */
fun mapRawSamplesToSleepSegments(samples: List<RawActivitySample>, deviceId: Int): List<SleepSegment> =
    samples
        .filter { it.source == SOURCE_SLEEP && it.deviceId == deviceId }
        .filter { it.timestampSeconds < it.otherTimestampSeconds }
        .mapNotNull { sample ->
            val kind = rawKindToSegmentKind(sample.rawKind) ?: return@mapNotNull null
            SleepSegment(
                start = Instant.ofEpochSecond(sample.timestampSeconds),
                end = Instant.ofEpochSecond(sample.otherTimestampSeconds),
                kind = kind
            )
        }

private fun rawKindToSegmentKind(rawKind: Int): SegmentKind? = when (rawKind) {
    RAW_KIND_LIGHT -> SegmentKind.LIGHT
    RAW_KIND_DEEP -> SegmentKind.DEEP
    RAW_KIND_AWAKE -> SegmentKind.AWAKE
    else -> null
}

/** Latest per-minute heart-rate sample time (`SOURCE = 11`) for [deviceId], used to tell stale data from fresh. */
fun newestHeartRateSampleAt(samples: List<RawActivitySample>, deviceId: Int): Instant? =
    samples
        .filter { it.source == SOURCE_HEART_RATE && it.deviceId == deviceId }
        .maxOfOrNull { it.timestampSeconds }
        ?.let(Instant::ofEpochSecond)

/**
 * J1.6 (owner-reported, 2026-09-21): clips every segment in [segments] so none of them starts before
 * [nightStartedAt] - crediting only the part of a segment that actually falls inside the night, never the
 * part from before the owner tapped Start night, and never dropping a straddling segment outright either. A
 * segment that ENDS at or before [nightStartedAt] (none of it falls inside the night at all) is dropped, since
 * there is nothing left of it to clip. Pairs with NightOrchestrator.kt's own BAND_QUERY_LOOKBACK, which widens
 * the SQL query far enough back to retrieve a straddling row in the first place - see that constant's own doc
 * for the 40-minutes-late bug this closes. `clipToNow` (Intervals.kt) is the engine's own matching clip at the
 * OTHER end of a segment (to `now`, the ceiling); this is the same idea at the START (to [nightStartedAt], the
 * floor) - the two never overlap, so nothing here duplicates `clipToNow`'s own job.
 */
fun clipSegmentsToNightStart(segments: List<SleepSegment>, nightStartedAt: Instant): List<SleepSegment> =
    segments.mapNotNull { segment ->
        when {
            !segment.end.isAfter(nightStartedAt) -> null
            segment.start.isBefore(nightStartedAt) -> segment.copy(start = nightStartedAt)
            else -> segment
        }
    }
