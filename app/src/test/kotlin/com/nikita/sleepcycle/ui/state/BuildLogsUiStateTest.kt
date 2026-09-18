package com.nikita.sleepcycle.ui.state

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

        val state = buildLogsUiState(listOf(file), TEST_ZONE)

        assertEquals(1, state.logs.size)
        assertEquals("2026-09-18 08:17", state.logs.single().displayName)
        assertEquals(file, state.logs.single().file)
    }

    @Test
    fun `keeps the order it was given, which is newest first`(@TempDir dir: Path) {
        val newest = logFile(dir, "night-20260918-0011.jsonl", 10, Instant.parse("2026-09-18T06:17:00Z"))
        val oldest = logFile(dir, "night-20260916-2340.jsonl", 10, Instant.parse("2026-09-16T21:40:00Z"))

        val state = buildLogsUiState(listOf(newest, oldest), TEST_ZONE)

        assertEquals(listOf("2026-09-18 08:17", "2026-09-16 23:40"), state.logs.map { it.displayName })
    }

    @Test
    fun `labels a size under a kilobyte in bytes, and larger ones in kilobytes`(@TempDir dir: Path) {
        val small = logFile(dir, "night-20260918-0011.jsonl", 512, Instant.parse("2026-09-18T06:17:00Z"))
        val large = logFile(dir, "night-20260917-0011.jsonl", 2048, Instant.parse("2026-09-17T06:17:00Z"))

        val state = buildLogsUiState(listOf(small, large), TEST_ZONE)

        assertEquals("512 B", state.logs[0].sizeLabel)
        assertEquals("2 KB", state.logs[1].sizeLabel)
    }

    @Test
    fun `tags a simulated night by its file name prefix, so it is never mistaken for a real one`(@TempDir dir: Path) {
        val real = logFile(dir, "night-20260918-0011.jsonl", 10, Instant.parse("2026-09-18T06:17:00Z"))
        val simulated = logFile(dir, "night-sim-20260917-1430.jsonl", 10, Instant.parse("2026-09-17T12:30:00Z"))

        val state = buildLogsUiState(listOf(real, simulated), TEST_ZONE)

        assertFalse(state.logs[0].isSimulated)
        assertTrue(state.logs[1].isSimulated)
    }

    @Test
    fun `an empty listing derives an empty list rather than a placeholder row`() {
        assertEquals(emptyList<NightLogSummary>(), buildLogsUiState(emptyList(), TEST_ZONE).logs)
    }
}
