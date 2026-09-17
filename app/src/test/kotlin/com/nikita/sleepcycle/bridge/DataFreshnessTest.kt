package com.nikita.sleepcycle.bridge

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

private val NOW = Instant.parse("2026-09-17T08:00:00Z")
private val THRESHOLD: Duration = Duration.ofMinutes(30)

class DataFreshnessTest {
    @Test
    fun `no samples at all is stale`() {
        val check = checkDataFreshness(newestSampleAt = null, now = NOW, exportFileModifiedAt = null, previousExportFileModifiedAt = null, threshold = THRESHOLD)

        assertFalse(check.isFresh)
    }

    @Test
    fun `a sample within the threshold and a first-ever read (no previous file time) is fresh`() {
        val check = checkDataFreshness(
            newestSampleAt = NOW.minus(Duration.ofMinutes(10)),
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
            now = NOW,
            exportFileModifiedAt = null,
            previousExportFileModifiedAt = NOW.minus(Duration.ofMinutes(20)),
            threshold = THRESHOLD
        )

        assertTrue(check.isFresh)
    }
}
