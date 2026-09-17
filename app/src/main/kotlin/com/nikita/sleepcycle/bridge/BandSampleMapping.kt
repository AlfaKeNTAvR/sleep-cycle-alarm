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
