package com.nikita.sleepcycle.night

// File purpose: the sleep rating as the night log keeps it (owner spec, 2026-10-02) - which nights can be
// rated at all, and that the rating after End night and the later one are both kept, each with its own time.

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class SleepRatingTest {
    private val startedAt = Instant.parse("2026-10-02T20:30:00Z")
    private val endedAt = Instant.parse("2026-10-03T04:52:00Z")
    private val morning = Instant.parse("2026-10-03T04:53:00Z")
    private val afternoon = Instant.parse("2026-10-03T12:02:00Z")

    /** A night_start written by a build with the rating: its usual fields plus [nightStartRatingFields]. */
    private val rateableStartLine = formatNightLogLine(
        NightLogEvent(startedAt, "night_start", mapOf("pickedCycles" to "5", "deadline" to "none") + nightStartRatingFields())
    )
    private val endLine = formatNightLogLine(NightLogEvent(endedAt, "night_end", mapOf("summary" to "total=PT7H stretches=2")))

    private fun ratingLine(moment: RatingMoment, rating: SleepRating, at: Instant) = formatNightLogLine(sleepRatingEvent(moment, rating, at))

    @Test
    fun `a night started before the rating existed cannot be rated`() {
        val oldStart = """{"at":"2026-09-18T04:11:05.057916Z","type":"night_start","fields":{"pickedCycles":"5","deadline":"none"}}"""
        assertNull(parsePastNightLog(listOf(oldStart, endLine)).ratings)
    }

    @Test
    fun `a night started since the rating existed can be rated, and starts unrated`() {
        assertEquals(NightRatings(afterEndNight = null, later = null, laterAsked = false), parsePastNightLog(listOf(rateableStartLine, endLine)).ratings)
    }

    @Test
    fun `the rating after End night and the later one are both kept, each with its own time`() {
        val lines = listOf(
            rateableStartLine, endLine,
            ratingLine(RatingMoment.AFTER_END_NIGHT, SleepRating.GOOD, morning),
            ratingLine(RatingMoment.LATER, SleepRating.OKAY, afternoon),
        )
        val ratings = parsePastNightLog(lines).ratings

        assertEquals(RecordedRating(SleepRating.GOOD, morning), ratings?.afterEndNight)
        assertEquals(RecordedRating(SleepRating.OKAY, afternoon), ratings?.later)
    }

    @Test
    fun `changing a rating replaces only that one, the other keeps its value and time`() {
        val changedAt = Instant.parse("2026-10-04T09:00:00Z")
        val lines = listOf(
            rateableStartLine, endLine,
            ratingLine(RatingMoment.AFTER_END_NIGHT, SleepRating.GOOD, morning),
            ratingLine(RatingMoment.LATER, SleepRating.OKAY, afternoon),
            ratingLine(RatingMoment.LATER, SleepRating.BAD, changedAt),
        )
        val ratings = parsePastNightLog(lines).ratings

        assertEquals(RecordedRating(SleepRating.GOOD, morning), ratings?.afterEndNight)
        assertEquals(RecordedRating(SleepRating.BAD, changedAt), ratings?.later)
    }

    @Test
    fun `a rating line with a value this build does not know is skipped`() {
        val unknown = """{"at":"2026-10-03T04:53:00Z","type":"sleep_rating","fields":{"moment":"after_end_night","rating":"amazing"}}"""
        assertNull(parsePastNightLog(listOf(rateableStartLine, endLine, unknown)).ratings?.afterEndNight)
    }

    @Test
    fun `the later question having been asked is remembered`() {
        val lines = listOf(rateableStartLine, endLine, formatNightLogLine(laterRatingAskedEvent(afternoon)))
        assertEquals(true, parsePastNightLog(lines).ratings?.laterAsked)
    }
}
