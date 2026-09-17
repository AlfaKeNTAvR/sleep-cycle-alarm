package com.nikita.sleepcycle.bridge

// File purpose: pure freshness decision for one read of the exported band database - is the newest sample
// recent enough, and did the export file actually change since the last successful read.

import java.time.Duration
import java.time.Instant

/** Heart-rate data older than this, or from an export file that did not change since last time, is stale. */
val BAND_DATA_FRESHNESS_THRESHOLD: Duration = Duration.ofMinutes(30)

/** Whether this read's data can be trusted, and if not, one plain-English reason why. */
data class FreshnessCheck(val isFresh: Boolean, val reason: String?)

/**
 * Data is stale when [newestSampleAt] is missing or older than [threshold] relative to [now], or when the
 * export file's last-modified time ([exportFileModifiedAt]) did not advance past the previous successful
 * read's ([previousExportFileModifiedAt]) - meaning Gadgetbridge reported success but wrote nothing new.
 * The file-advance check is skipped when there is no previous value to compare against (first read of the night).
 */
fun checkDataFreshness(
    newestSampleAt: Instant?,
    now: Instant,
    exportFileModifiedAt: Instant?,
    previousExportFileModifiedAt: Instant?,
    threshold: Duration = BAND_DATA_FRESHNESS_THRESHOLD
): FreshnessCheck {
    if (newestSampleAt == null) return FreshnessCheck(isFresh = false, reason = "no heart-rate samples yet")

    val age = Duration.between(newestSampleAt, now)
    if (age > threshold) {
        return FreshnessCheck(isFresh = false, reason = "newest sample is $age old, older than the $threshold threshold")
    }

    val fileDidNotAdvance = exportFileModifiedAt != null && previousExportFileModifiedAt != null &&
        !exportFileModifiedAt.isAfter(previousExportFileModifiedAt)
    if (fileDidNotAdvance) {
        return FreshnessCheck(isFresh = false, reason = "export file did not change since the last successful sync")
    }

    return FreshnessCheck(isFresh = true, reason = null)
}
