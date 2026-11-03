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
    val deviceId: Int,
    /**
     * The row's `HEART_RATE` column in beats per minute. PER-130: Gadgetbridge writes -1 (or 0) when the band
     * measured nothing that minute, and from 2026-10-30 every heart-rate row of the night carried -1 while every
     * sync still succeeded. Only a value above 0 is a real measurement (see [isRealHeartRate]).
     */
    val heartRate: Int
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

/** Whether a row's `HEART_RATE` is a real measurement: Gadgetbridge stores -1 (or 0) for a minute with none. */
fun isRealHeartRate(heartRate: Int): Boolean = heartRate > 0

/**
 * Latest per-minute heart-rate sample time (`SOURCE = 11`) for [deviceId] that carries a REAL value, used to
 * tell stale data from fresh. PER-130: a row with `HEART_RATE` -1 does not count - a band that syncs but
 * measures nothing is stale, not fresh.
 */
fun newestHeartRateSampleAt(samples: List<RawActivitySample>, deviceId: Int): Instant? =
    newestHeartRateRowTimestamp(samples, deviceId) { isRealHeartRate(it.heartRate) }

/**
 * PER-130: latest heart-rate row time (`SOURCE = 11`) for [deviceId] whatever its value, -1 included. Newer
 * than [newestHeartRateSampleAt] means rows still arrive but carry no measurement - the band is connected
 * and syncing, it just is not measuring (see [StaleReason.NO_HEART_RATE_VALUES]).
 */
fun newestHeartRateRowAt(samples: List<RawActivitySample>, deviceId: Int): Instant? =
    newestHeartRateRowTimestamp(samples, deviceId) { true }

private fun newestHeartRateRowTimestamp(samples: List<RawActivitySample>, deviceId: Int, counts: (RawActivitySample) -> Boolean): Instant? =
    samples
        .filter { it.source == SOURCE_HEART_RATE && it.deviceId == deviceId && counts(it) }
        .maxOfOrNull { it.timestampSeconds }
        ?.let(Instant::ofEpochSecond)

/** Everything one read of the export yields from its raw rows: the sleep segments and the two heart-rate times freshness is judged from. */
fun buildBandDataResult(samples: List<RawActivitySample>, deviceId: Int, exportFileModifiedAt: Instant?): BandDataResult.Success =
    BandDataResult.Success(
        segments = mapRawSamplesToSleepSegments(samples, deviceId),
        newestSampleAt = newestHeartRateSampleAt(samples, deviceId),
        exportFileModifiedAt = exportFileModifiedAt,
        newestHeartRateRowAt = newestHeartRateRowAt(samples, deviceId)
    )

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
