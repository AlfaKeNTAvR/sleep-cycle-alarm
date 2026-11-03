package com.nikita.sleepcycle.bridge

// File purpose: pure freshness decision for one read of the exported band database - is the newest sample
// recent enough, and did the export file actually change since the last successful read.

import java.time.Duration
import java.time.Instant

/** Heart-rate data older than this, or from an export file that did not change since last time, is stale. */
val BAND_DATA_FRESHNESS_THRESHOLD: Duration = Duration.ofMinutes(30)

/**
 * PER-130: why one read's data is stale, so the screens, the notification and the log can name the right fix.
 * [logValue] is the `staleReason` field of the night log's `stale_data` event.
 */
enum class StaleReason(val logValue: String) {
    /** Nothing new reached the export: no recent heart-rate rows at all, or the export file did not change. The fix is to disconnect and reconnect the band. */
    NO_NEW_SAMPLES("no_new_samples"),

    /**
     * Heart-rate rows still arrive within the threshold, but none carries a real value (all -1). The band is
     * connected and syncing; it just is not measuring. Seen from 2026-10-30 to 2026-11-03; the fix is to switch
     * "Enable automatic heart rate measuring" off and on in Gadgetbridge's band settings.
     */
    NO_HEART_RATE_VALUES("no_heart_rate_values")
}

/** Whether this read's data can be trusted, and if not, one plain-English [reason] and its [staleReason] kind. */
data class FreshnessCheck(val isFresh: Boolean, val reason: String?, val staleReason: StaleReason? = null)

/**
 * Data is stale when [newestSampleAt] (the newest heart-rate row with a REAL value) is missing or older than
 * [threshold] relative to [now], or when the export file's last-modified time ([exportFileModifiedAt]) did not
 * advance past the previous successful read's ([previousExportFileModifiedAt]) - meaning Gadgetbridge reported
 * success but wrote nothing new. The file-advance check is skipped when there is no previous value to compare
 * against (first read of the night).
 *
 * PER-130: when real values are missing or old but [newestHeartRateRowAt] (the newest heart-rate row whatever
 * its value) is within [threshold], rows are still arriving without a measurement, and the reason is
 * [StaleReason.NO_HEART_RATE_VALUES]; every other stale case is [StaleReason.NO_NEW_SAMPLES].
 */
fun checkDataFreshness(
    newestSampleAt: Instant?,
    now: Instant,
    exportFileModifiedAt: Instant?,
    previousExportFileModifiedAt: Instant?,
    newestHeartRateRowAt: Instant?,
    threshold: Duration = BAND_DATA_FRESHNESS_THRESHOLD
): FreshnessCheck {
    val realValueAge = newestSampleAt?.let { Duration.between(it, now) }
    if (realValueAge == null || realValueAge > threshold) {
        val rowsStillArrive = newestHeartRateRowAt != null && Duration.between(newestHeartRateRowAt, now) <= threshold
        if (rowsStillArrive) {
            return FreshnessCheck(
                isFresh = false,
                reason = "heart-rate rows arrive (newest at $newestHeartRateRowAt) but none carries a real value; newest real value: ${newestSampleAt ?: "none"}",
                staleReason = StaleReason.NO_HEART_RATE_VALUES
            )
        }
        val reason = if (realValueAge == null) "no heart-rate samples yet" else "newest sample is $realValueAge old, older than the $threshold threshold"
        return FreshnessCheck(isFresh = false, reason = reason, staleReason = StaleReason.NO_NEW_SAMPLES)
    }

    val fileDidNotAdvance = exportFileModifiedAt != null && previousExportFileModifiedAt != null &&
        !exportFileModifiedAt.isAfter(previousExportFileModifiedAt)
    if (fileDidNotAdvance) {
        return FreshnessCheck(isFresh = false, reason = "export file did not change since the last successful sync", staleReason = StaleReason.NO_NEW_SAMPLES)
    }

    return FreshnessCheck(isFresh = true, reason = null)
}
