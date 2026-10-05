package com.nikita.sleepcycle.ui.state

import com.nikita.sleepcycle.night.NightRatings
import com.nikita.sleepcycle.night.PastNightLog
import com.nikita.sleepcycle.night.PastNightSummary
import com.nikita.sleepcycle.night.RatingSymptom
import com.nikita.sleepcycle.night.RecordedRating
import com.nikita.sleepcycle.night.RecordedStretch
import com.nikita.sleepcycle.night.SleepRating
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
        speed: Int = 1,
    ) = PastNightLog(
        summary = summary,
        startedAt = Instant.parse("2026-09-17T22:11:00Z"),
        endedAt = Instant.parse("2026-09-18T06:17:00Z"),
        deadline = deadline,
        pickedCycles = pickedCycles,
        speed = speed,
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

    /**
     * T7: a simulated-time night's picked length always labels in real hours now - a fast debug night comes
     * from warping the clock (SimulatedClock.kt), never from shrinking EngineConfig's own cycle length, so the
     * picked length label no longer depends on the night's own speed at all.
     */
    @Test
    fun `T7 a simulated-time night still labels its picked length in real hours, regardless of speed`() {
        val state = buildPastNightUiState(
            row(isSimulated = true),
            log(detailed, pickedCycles = 3, speed = 60),
            TEST_ZONE
        )

        assertTrue(state.isSimulated)
        assertEquals("4.5 h", state.pickedLengthLabel)
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
    fun `P1 a night logged with no band data shows the no-data wording, not no sleep`() {
        val state = buildPastNightUiState(row(), log(PastNightSummary.Detailed(Duration.ZERO, emptyList())).copy(noBandData = true), TEST_ZONE)

        assertTrue(requireNotNull(state.report).noBandData)
    }

    @Test
    fun `P1 a no-sleep night logged without the flag keeps the no-sleep wording`() {
        val state = buildPastNightUiState(row(), log(PastNightSummary.Detailed(Duration.ZERO, emptyList())), TEST_ZONE)

        assertFalse(requireNotNull(state.report).noBandData)
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

    @Test
    fun `a night from before the rating existed has no rating section`() {
        assertNull(buildPastNightUiState(row(), log(detailed), TEST_ZONE).ratings)
    }

    @Test
    fun `a rated night shows both ratings, each with the time it was given`() {
        val ratings = NightRatings(
            afterEndNight = RecordedRating(SleepRating.GOOD, Instant.parse("2026-09-18T05:52:00Z")),
            later = RecordedRating(SleepRating.OKAY, Instant.parse("2026-09-18T13:02:00Z")),
            laterAsked = true,
        )

        val state = buildPastNightUiState(row(), log(detailed).copy(ratings = ratings), TEST_ZONE)

        assertEquals(PastNightRatingRow(SleepRating.GOOD, "07:52"), state.ratings?.afterEndNight)
        assertEquals(PastNightRatingRow(SleepRating.OKAY, "15:02", symptomsEditable = true), state.ratings?.later)
    }

    @Test
    fun `an Okay or Bad row shows its ticked symptoms in the dialog's order and can edit them, a Good row cannot`() {
        val ratings = NightRatings(
            afterEndNight = RecordedRating(
                SleepRating.BAD, Instant.parse("2026-09-18T05:52:00Z"), setOf(RatingSymptom.HEADACHE, RatingSymptom.STILL_SLEEPY),
            ),
            later = RecordedRating(SleepRating.GOOD, Instant.parse("2026-09-18T13:02:00Z")),
            laterAsked = true,
        )

        val state = buildPastNightUiState(row(), log(detailed).copy(ratings = ratings), TEST_ZONE)

        assertEquals(listOf(RatingSymptom.STILL_SLEEPY, RatingSymptom.HEADACHE), state.ratings?.afterEndNight?.symptoms)
        assertEquals(true, state.ratings?.afterEndNight?.symptomsEditable)
        assertEquals(emptyList<RatingSymptom>(), state.ratings?.later?.symptoms)
        assertEquals(false, state.ratings?.later?.symptomsEditable)
    }

    @Test
    fun `a rateable night not rated yet still offers both ratings, empty`() {
        val state = buildPastNightUiState(row(), log(detailed).copy(ratings = NightRatings(null, null, false)), TEST_ZONE)

        assertEquals(PastNightRatingRow(rating = null, timeLabel = null), state.ratings?.afterEndNight)
        assertEquals(PastNightRatingRow(rating = null, timeLabel = null), state.ratings?.later)
    }
}
