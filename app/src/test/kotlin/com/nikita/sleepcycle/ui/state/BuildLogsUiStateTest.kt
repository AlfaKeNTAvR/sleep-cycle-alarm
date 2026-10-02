package com.nikita.sleepcycle.ui.state

import com.nikita.sleepcycle.night.NightRatings
import com.nikita.sleepcycle.night.RecordedRating
import com.nikita.sleepcycle.night.SleepRating
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

private val TEST_ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")

class BuildLogsUiStateTest {
    private fun logFile(dir: Path, name: String, sizeBytes: Int, modifiedAt: Instant): File {
        val file = File(dir.toFile(), name)
        file.writeBytes(ByteArray(sizeBytes))
        file.setLastModified(modifiedAt.toEpochMilli())
        return file
    }

    @Test
    fun `names each row by the night's own date and time in the local zone`(@TempDir dir: Path) {
        val file = logFile(dir, "night-20260918-0011.jsonl", sizeBytes = 10, modifiedAt = Instant.parse("2026-09-18T06:17:31Z"))

        val state = buildLogsUiState(listOf(file), emptyMap(), TEST_ZONE)

        assertEquals(1, state.logs.size)
        assertEquals("2026-09-18 08:17", state.logs.single().displayName)
        assertEquals(file, state.logs.single().file)
    }

    @Test
    fun `keeps the order it was given, which is newest first`(@TempDir dir: Path) {
        val newest = logFile(dir, "night-20260918-0011.jsonl", 10, Instant.parse("2026-09-18T06:17:00Z"))
        val oldest = logFile(dir, "night-20260916-2340.jsonl", 10, Instant.parse("2026-09-16T21:40:00Z"))

        val state = buildLogsUiState(listOf(newest, oldest), emptyMap(), TEST_ZONE)

        assertEquals(listOf("2026-09-18 08:17", "2026-09-16 23:40"), state.logs.map { it.displayName })
    }

    @Test
    fun `labels a size under a kilobyte in bytes, and larger ones in kilobytes`(@TempDir dir: Path) {
        val small = logFile(dir, "night-20260918-0011.jsonl", 512, Instant.parse("2026-09-18T06:17:00Z"))
        val large = logFile(dir, "night-20260917-0011.jsonl", 2048, Instant.parse("2026-09-17T06:17:00Z"))

        val state = buildLogsUiState(listOf(small, large), emptyMap(), TEST_ZONE)

        assertEquals("512 B", state.logs[0].sizeLabel)
        assertEquals("2 KB", state.logs[1].sizeLabel)
    }

    @Test
    fun `tags a simulated night by its file name prefix, so it is never mistaken for a real one`(@TempDir dir: Path) {
        val real = logFile(dir, "night-20260918-0011.jsonl", 10, Instant.parse("2026-09-18T06:17:00Z"))
        val simulated = logFile(dir, "night-sim-20260917-1430.jsonl", 10, Instant.parse("2026-09-17T12:30:00Z"))

        val state = buildLogsUiState(listOf(real, simulated), emptyMap(), TEST_ZONE)

        assertFalse(state.logs[0].isSimulated)
        assertTrue(state.logs[1].isSimulated)
    }

    @Test
    fun `an empty listing derives an empty list rather than a placeholder row`() {
        assertEquals(emptyList<NightLogSummary>(), buildLogsUiState(emptyList(), emptyMap(), TEST_ZONE).logs)
    }

    @Test
    fun `a rated night shows its rating after End night first, then the later one`(@TempDir dir: Path) {
        val rated = logFile(dir, "night-20261005-2340.jsonl", 10, Instant.parse("2026-10-05T05:40:00Z"))
        val laterOnly = logFile(dir, "night-20261004-2340.jsonl", 10, Instant.parse("2026-10-04T05:40:00Z"))
        val unrated = logFile(dir, "night-20261003-2340.jsonl", 10, Instant.parse("2026-10-03T05:40:00Z"))
        val at = Instant.parse("2026-10-05T06:00:00Z")
        val ratings = mapOf(
            rated to NightRatings(RecordedRating(SleepRating.GOOD, at), RecordedRating(SleepRating.OKAY, at), laterAsked = true),
            laterOnly to NightRatings(null, RecordedRating(SleepRating.BAD, at), laterAsked = true),
            unrated to null,
        )

        val state = buildLogsUiState(listOf(rated, laterOnly, unrated), ratings, TEST_ZONE)

        assertEquals(
            listOf(listOf(SleepRating.GOOD, SleepRating.OKAY), listOf(SleepRating.BAD), emptyList()),
            state.logs.map { it.ratingChips }
        )
    }
}
