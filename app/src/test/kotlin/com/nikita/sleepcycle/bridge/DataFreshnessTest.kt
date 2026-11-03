package com.nikita.sleepcycle.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

private val NOW = Instant.parse("2026-09-17T08:00:00Z")
private val THRESHOLD: Duration = Duration.ofMinutes(30)

class DataFreshnessTest {
    @Test
    fun `no samples at all is stale`() {
        val check = checkDataFreshness(newestSampleAt = null, newestHeartRateRowAt = null, now = NOW, exportFileModifiedAt = null, previousExportFileModifiedAt = null, threshold = THRESHOLD)

        assertFalse(check.isFresh)
    }

    @Test
    fun `a sample within the threshold and a first-ever read (no previous file time) is fresh`() {
        val check = checkDataFreshness(
            newestSampleAt = NOW.minus(Duration.ofMinutes(10)),
            newestHeartRateRowAt = NOW.minus(Duration.ofMinutes(10)),
            now = NOW,
            exportFileModifiedAt = NOW,
            previousExportFileModifiedAt = null,
            threshold = THRESHOLD
        )

        assertTrue(check.isFresh)
    }

    @Test
    fun `a sample older than the threshold is stale`() {
        val check = checkDataFreshness(
            newestSampleAt = NOW.minus(Duration.ofMinutes(31)),
            newestHeartRateRowAt = NOW.minus(Duration.ofMinutes(31)),
            now = NOW,
            exportFileModifiedAt = NOW,
            previousExportFileModifiedAt = NOW.minus(Duration.ofMinutes(5)),
            threshold = THRESHOLD
        )

        assertFalse(check.isFresh)
    }

    @Test
    fun `a sample exactly at the threshold is still fresh`() {
        val check = checkDataFreshness(
            newestSampleAt = NOW.minus(THRESHOLD),
            newestHeartRateRowAt = NOW.minus(THRESHOLD),
            now = NOW,
            exportFileModifiedAt = NOW,
            previousExportFileModifiedAt = null,
            threshold = THRESHOLD
        )

        assertTrue(check.isFresh)
    }

    @Test
    fun `export file that did not advance since the last successful read is stale even with a recent sample`() {
        val sameModifiedAt = NOW.minus(Duration.ofMinutes(20))

        val check = checkDataFreshness(
            newestSampleAt = NOW.minus(Duration.ofMinutes(5)),
            newestHeartRateRowAt = NOW.minus(Duration.ofMinutes(5)),
            now = NOW,
            exportFileModifiedAt = sameModifiedAt,
            previousExportFileModifiedAt = sameModifiedAt,
            threshold = THRESHOLD
        )

        assertFalse(check.isFresh)
    }

    @Test
    fun `export file that advanced past the previous read is fresh`() {
        val check = checkDataFreshness(
            newestSampleAt = NOW.minus(Duration.ofMinutes(5)),
            newestHeartRateRowAt = NOW.minus(Duration.ofMinutes(5)),
            now = NOW,
            exportFileModifiedAt = NOW.minus(Duration.ofMinutes(1)),
            previousExportFileModifiedAt = NOW.minus(Duration.ofMinutes(20)),
            threshold = THRESHOLD
        )

        assertTrue(check.isFresh)
    }

    @Test
    fun `a missing export file modified time never blocks freshness by itself`() {
        val check = checkDataFreshness(
            newestSampleAt = NOW.minus(Duration.ofMinutes(5)),
            newestHeartRateRowAt = NOW.minus(Duration.ofMinutes(5)),
            now = NOW,
            exportFileModifiedAt = null,
            previousExportFileModifiedAt = NOW.minus(Duration.ofMinutes(20)),
            threshold = THRESHOLD
        )

        assertTrue(check.isFresh)
    }

    // ---- PER-130: from 2026-10-30 every heart-rate row carried HEART_RATE -1 while every sync succeeded. Only a
    // real value counts as fresh, and the stale reason tells that case apart from "nothing new arrived". --------

    @Test
    fun `PER-130 rows arriving within the threshold with no real value are stale for no heart-rate values`() {
        val check = checkDataFreshness(
            newestSampleAt = null,
            newestHeartRateRowAt = NOW.minus(Duration.ofMinutes(2)),
            now = NOW,
            exportFileModifiedAt = NOW,
            previousExportFileModifiedAt = NOW.minus(Duration.ofMinutes(15)),
            threshold = THRESHOLD
        )

        assertFalse(check.isFresh)
        assertEquals(StaleReason.NO_HEART_RATE_VALUES, check.staleReason)
    }

    @Test
    fun `PER-130 real values that stopped hours ago while -1 rows keep arriving are stale for no heart-rate values`() {
        val check = checkDataFreshness(
            newestSampleAt = NOW.minus(Duration.ofHours(3)),
            newestHeartRateRowAt = NOW.minus(Duration.ofMinutes(1)),
            now = NOW,
            exportFileModifiedAt = NOW,
            previousExportFileModifiedAt = null,
            threshold = THRESHOLD
        )

        assertFalse(check.isFresh)
        assertEquals(StaleReason.NO_HEART_RATE_VALUES, check.staleReason)
    }

    @Test
    fun `PER-130 a recent real value is fresh even when newer rows carry -1`() {
        val check = checkDataFreshness(
            newestSampleAt = NOW.minus(Duration.ofMinutes(10)),
            newestHeartRateRowAt = NOW.minus(Duration.ofMinutes(1)),
            now = NOW,
            exportFileModifiedAt = NOW,
            previousExportFileModifiedAt = null,
            threshold = THRESHOLD
        )

        assertTrue(check.isFresh)
        assertNull(check.staleReason)
    }

    @Test
    fun `PER-130 no rows at all, or only old ones, is stale for no new samples`() {
        val noRows = checkDataFreshness(
            newestSampleAt = null, newestHeartRateRowAt = null, now = NOW,
            exportFileModifiedAt = NOW, previousExportFileModifiedAt = null, threshold = THRESHOLD
        )
        val oldRows = checkDataFreshness(
            newestSampleAt = NOW.minus(Duration.ofHours(2)), newestHeartRateRowAt = NOW.minus(Duration.ofMinutes(45)), now = NOW,
            exportFileModifiedAt = NOW, previousExportFileModifiedAt = null, threshold = THRESHOLD
        )

        assertEquals(StaleReason.NO_NEW_SAMPLES, noRows.staleReason)
        assertEquals(StaleReason.NO_NEW_SAMPLES, oldRows.staleReason)
    }

    @Test
    fun `PER-130 an export file that did not advance is stale for no new samples`() {
        val sameModifiedAt = NOW.minus(Duration.ofMinutes(20))

        val check = checkDataFreshness(
            newestSampleAt = NOW.minus(Duration.ofMinutes(5)), newestHeartRateRowAt = NOW.minus(Duration.ofMinutes(5)), now = NOW,
            exportFileModifiedAt = sameModifiedAt, previousExportFileModifiedAt = sameModifiedAt, threshold = THRESHOLD
        )

        assertEquals(StaleReason.NO_NEW_SAMPLES, check.staleReason)
    }
}
