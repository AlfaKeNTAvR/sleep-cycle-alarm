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

    private fun symptomsLine(moment: RatingMoment, symptoms: Set<RatingSymptom>, at: Instant) =
        formatNightLogLine(ratingSymptomsEvent(moment, symptoms, at))

    @Test
    fun `the symptoms ticked for a rating are read back with that rating, each moment its own`() {
        val lines = listOf(
            rateableStartLine, endLine,
            ratingLine(RatingMoment.AFTER_END_NIGHT, SleepRating.OKAY, morning),
            symptomsLine(RatingMoment.AFTER_END_NIGHT, setOf(RatingSymptom.STILL_SLEEPY, RatingSymptom.HEADACHE), morning.plusSeconds(20)),
            ratingLine(RatingMoment.LATER, SleepRating.BAD, afternoon),
            symptomsLine(RatingMoment.LATER, setOf(RatingSymptom.LOW_ENERGY), afternoon.plusSeconds(20)),
        )
        val ratings = parsePastNightLog(lines).ratings

        assertEquals(setOf(RatingSymptom.STILL_SLEEPY, RatingSymptom.HEADACHE), ratings?.afterEndNight?.symptoms)
        assertEquals(setOf(RatingSymptom.LOW_ENERGY), ratings?.later?.symptoms)
    }

    @Test
    fun `changing a rating to Good clears that moment's symptoms, and a later Okay starts from none`() {
        val laterSymptoms = symptomsLine(RatingMoment.LATER, setOf(RatingSymptom.HARD_TO_FOCUS), afternoon.plusSeconds(20))
        val toGood = listOf(
            rateableStartLine, endLine,
            ratingLine(RatingMoment.AFTER_END_NIGHT, SleepRating.BAD, morning),
            symptomsLine(RatingMoment.AFTER_END_NIGHT, setOf(RatingSymptom.HEADACHE), morning.plusSeconds(20)),
            ratingLine(RatingMoment.LATER, SleepRating.OKAY, afternoon),
            laterSymptoms,
            ratingLine(RatingMoment.AFTER_END_NIGHT, SleepRating.GOOD, afternoon.plusSeconds(60)),
        )
        val backToOkay = toGood + ratingLine(RatingMoment.AFTER_END_NIGHT, SleepRating.OKAY, afternoon.plusSeconds(120))

        assertEquals(emptySet<RatingSymptom>(), parsePastNightLog(toGood).ratings?.afterEndNight?.symptoms)
        assertEquals(emptySet<RatingSymptom>(), parsePastNightLog(backToOkay).ratings?.afterEndNight?.symptoms)
        assertEquals(setOf(RatingSymptom.HARD_TO_FOCUS), parsePastNightLog(backToOkay).ratings?.later?.symptoms, "the other moment keeps its own")
    }

    @Test
    fun `switching between Okay and Bad keeps the ticks`() {
        // Owner decision, 2026-10-05: only Good clears them; the dialog reopens prefilled.
        val lines = listOf(
            rateableStartLine, endLine,
            ratingLine(RatingMoment.AFTER_END_NIGHT, SleepRating.OKAY, morning),
            symptomsLine(RatingMoment.AFTER_END_NIGHT, setOf(RatingSymptom.HEADACHE), morning.plusSeconds(20)),
            ratingLine(RatingMoment.AFTER_END_NIGHT, SleepRating.BAD, morning.plusSeconds(60)),
        )

        assertEquals(setOf(RatingSymptom.HEADACHE), parsePastNightLog(lines).ratings?.afterEndNight?.symptoms)
    }

    @Test
    fun `saving no symptoms clears the ones ticked before, and a name this build does not know is skipped`() {
        val unknownName = """{"at":"2026-10-03T04:54:00Z","type":"sleep_symptoms","fields":{"moment":"after_end_night","symptoms":"still_sleepy,itchy"}}"""
        val lines = listOf(rateableStartLine, endLine, ratingLine(RatingMoment.AFTER_END_NIGHT, SleepRating.OKAY, morning), unknownName)

        assertEquals(setOf(RatingSymptom.STILL_SLEEPY), parsePastNightLog(lines).ratings?.afterEndNight?.symptoms)
        val cleared = lines + symptomsLine(RatingMoment.AFTER_END_NIGHT, emptySet(), afternoon)
        assertEquals(emptySet<RatingSymptom>(), parsePastNightLog(cleared).ratings?.afterEndNight?.symptoms)
    }
}
