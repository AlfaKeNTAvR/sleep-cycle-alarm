package com.nikita.sleepcycle.ui.state

import com.nikita.sleepcycle.night.PastNightLog
import com.nikita.sleepcycle.night.PastNightSummary
import com.nikita.sleepcycle.night.RecordedStretch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private val TEST_ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")

class BuildPastNightUiStateTest {
    private fun row(displayName: String = "2026-09-18 00:11", isSimulated: Boolean = false) = NightLogSummary(
        file = File("night-20260918-0011.jsonl"),
        displayName = displayName,
        sizeLabel = "64 KB",
        isSimulated = isSimulated,
    )

    private fun log(
        summary: PastNightSummary,
        deadline: Instant? = Instant.parse("2026-09-18T06:30:00Z"),
        pickedCycles: Int? = 5,
        fastNight: Boolean = false,
    ) = PastNightLog(
        summary = summary,
        startedAt = Instant.parse("2026-09-17T22:11:00Z"),
        endedAt = Instant.parse("2026-09-18T06:17:00Z"),
        deadline = deadline,
        pickedCycles = pickedCycles,
        fastNight = fastNight,
    )

    private val detailed = PastNightSummary.Detailed(
        totalSleep = Duration.ofHours(6).plusMinutes(50),
        stretches = listOf(
            RecordedStretch(Instant.parse("2026-09-17T22:23:00Z"), Instant.parse("2026-09-17T23:44:00Z"), 0.9),
            RecordedStretch(Instant.parse("2026-09-18T00:50:00Z"), Instant.parse("2026-09-18T05:06:00Z"), 2.8)
        )
    )

    @Test
    fun `a fully recorded night renders every stretch with its span, length and cycles`() {
        val state = buildPastNightUiState(row(), log(detailed), TEST_ZONE)

        val report = requireNotNull(state.report)
        assertTrue(report.stretchDetailRecorded)
        assertEquals("6 h 50", report.totalSleepDurationLabel)
        assertEquals("07:06", report.wokeAtTimeLabel)
        assertEquals("08:17", report.endedAtTimeLabel)
        assertEquals(
            listOf(
                StretchLine(startTimeLabel = "00:23", endTimeLabel = "01:44", durationLabel = "1 h 21", cyclesLabel = "0.9"),
                StretchLine(startTimeLabel = "02:50", endTimeLabel = "07:06", durationLabel = "4 h 16", cyclesLabel = "2.8")
            ),
            report.stretches
        )
        assertNull(state.recordedStretchCount)
    }

    @Test
    fun `the night's own deadline and picked length are shown alongside the report`() {
        val state = buildPastNightUiState(row(), log(detailed), TEST_ZONE)

        assertEquals("08:30", state.deadlineTimeLabel)
        assertEquals("7.5 h", state.pickedLengthLabel)
    }

    @Test
    fun `a night with no deadline shows no deadline row`() {
        val state = buildPastNightUiState(row(), log(detailed, deadline = null), TEST_ZONE)

        assertNull(state.deadlineTimeLabel)
        assertEquals("7.5 h", state.pickedLengthLabel)
    }

    @Test
    fun `a simulated fast night labels its picked length in minutes, not decimal hours`() {
        val state = buildPastNightUiState(
            row(isSimulated = true),
            log(detailed, pickedCycles = 3, fastNight = true),
            TEST_ZONE
        )

        assertTrue(state.isSimulated)
        assertEquals("15 min", state.pickedLengthLabel)
    }

    @Test
    fun `a night logged before per-stretch detail shows its real total and says the rest was not recorded`() {
        val summary = PastNightSummary.TotalOnly(totalSleep = Duration.ofHours(6).plusMinutes(50), stretchCount = 5)

        val state = buildPastNightUiState(row(), log(summary), TEST_ZONE)

        val report = requireNotNull(state.report)
        assertFalse(report.stretchDetailRecorded)
        assertEquals("6 h 50", report.totalSleepDurationLabel)
        assertEquals(emptyList<StretchLine>(), report.stretches)
        assertNull(report.wokeAtTimeLabel, "the woke-at time was never recorded, so it must not be guessed")
        assertEquals(5, state.recordedStretchCount)
    }

    @Test
    fun `a night whose log recorded no summary at all has no report to show`() {
        val state = buildPastNightUiState(row(), log(PastNightSummary.Missing), TEST_ZONE)

        assertNull(state.report)
        assertNull(state.recordedStretchCount)
        assertEquals("08:30", state.deadlineTimeLabel, "the settings are still known from night_start")
    }

    @Test
    fun `a night that recorded no sleep keeps its empty stretch list, which the report shows as no sleep`() {
        val state = buildPastNightUiState(row(), log(PastNightSummary.Detailed(Duration.ZERO, emptyList())), TEST_ZONE)

        val report = requireNotNull(state.report)
        assertTrue(report.stretchDetailRecorded, "an empty list that WAS recorded is not the same as a missing one")
        assertEquals(emptyList<StretchLine>(), report.stretches)
    }

    @Test
    fun `a log with no night_end instant still renders, with the ended time marked unknown`() {
        val log = log(detailed).copy(endedAt = null)

        val state = buildPastNightUiState(row(), log, TEST_ZONE)

        assertEquals(MISSING_TIME_LABEL, requireNotNull(state.report).endedAtTimeLabel)
    }

    @Test
    fun `the row's own display name titles the screen`() {
        val state = buildPastNightUiState(row(displayName = "2026-09-18 00:11"), log(detailed), TEST_ZONE)

        assertEquals("2026-09-18 00:11", state.title)
    }
}
