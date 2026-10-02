package com.nikita.sleepcycle.night

// File purpose: when the later "Still feel the same about last night?" notification is due (owner spec,
// 2026-10-02), and which question it asks.

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class LaterRatingAskTest {
    private val zone = ZoneId.of("America/New_York")
    private val endedAt = Instant.parse("2026-10-03T11:52:00Z") // 07:52 local
    private val beforeTheAsk = Instant.parse("2026-10-03T12:00:00Z")
    private val threePmLocal = Instant.parse("2026-10-03T19:00:00Z")

    private fun night(ratings: NightRatings? = NightRatings(null, null, laterAsked = false), ended: Instant? = endedAt) = PastNightLog(
        summary = PastNightSummary.Missing, startedAt = Instant.parse("2026-10-03T03:30:00Z"), endedAt = ended,
        deadline = null, pickedCycles = 5, speed = 1, ratings = ratings,
    )

    @Test
    fun `asked at the chosen time on the day the night ended`() {
        assertEquals(threePmLocal, laterRatingAskAt(night(), SleepRatingSettings(), zone, beforeTheAsk))
    }

    @Test
    fun `asked at whatever time Settings says`() {
        val settings = SleepRatingSettings(askAt = LocalTime.of(12, 30))
        assertEquals(Instant.parse("2026-10-03T16:30:00Z"), laterRatingAskAt(night(), settings, zone, beforeTheAsk))
    }

    @Test
    fun `asked even when the morning rating was skipped`() {
        assertEquals(threePmLocal, laterRatingAskAt(night(NightRatings(null, null, false)), SleepRatingSettings(), zone, beforeTheAsk))
    }

    @Test
    fun `not asked when the rating or the later question is switched off`() {
        assertNull(laterRatingAskAt(night(), SleepRatingSettings(enabled = false), zone, beforeTheAsk))
        assertNull(laterRatingAskAt(night(), SleepRatingSettings(askAgainLater = false), zone, beforeTheAsk))
    }

    @Test
    fun `not asked for a night that cannot be rated or never ended`() {
        assertNull(laterRatingAskAt(night(ratings = null), SleepRatingSettings(), zone, beforeTheAsk))
        assertNull(laterRatingAskAt(night(ended = null), SleepRatingSettings(), zone, beforeTheAsk))
    }

    @Test
    fun `asked only once, and not after it was already answered`() {
        assertNull(laterRatingAskAt(night(NightRatings(null, null, laterAsked = true)), SleepRatingSettings(), zone, beforeTheAsk))
        val answered = NightRatings(null, RecordedRating(SleepRating.OKAY, beforeTheAsk), laterAsked = false)
        assertNull(laterRatingAskAt(night(answered), SleepRatingSettings(), zone, beforeTheAsk))
    }

    @Test
    fun `not asked when the night ended after the chosen time, or the time already passed`() {
        val endedAtFivePm = Instant.parse("2026-10-03T21:00:00Z")
        assertNull(laterRatingAskAt(night(ended = endedAtFivePm), SleepRatingSettings(), zone, endedAtFivePm))
        assertNull(laterRatingAskAt(night(), SleepRatingSettings(), zone, threePmLocal.plusSeconds(60)))
    }

    @Test
    fun `the question recalls the morning rating when there is one`() {
        val morning = RecordedRating(SleepRating.GOOD, endedAt)
        assertEquals(LaterRatingQuestion.StillFeelTheSame(SleepRating.GOOD), laterRatingQuestion(NightRatings(morning, null, false)))
        assertEquals(LaterRatingQuestion.HowDoYouFeel, laterRatingQuestion(NightRatings(null, null, false)))
    }
}
