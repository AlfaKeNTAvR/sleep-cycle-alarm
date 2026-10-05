package com.nikita.sleepcycle.ui.state

// File purpose: the morning report's "How did you sleep?" card - when it shows, and the symptoms ticked for an
// Okay or Bad morning rating under its faces (owner spec, 2026-10-04).

import com.nikita.sleepcycle.night.NightRatings
import com.nikita.sleepcycle.night.RatingSymptom
import com.nikita.sleepcycle.night.RecordedRating
import com.nikita.sleepcycle.night.SleepRating
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class MorningRatingCardTest {
    private val at = Instant.parse("2026-10-03T04:53:00Z")

    @Test
    fun `an Okay morning rating shows the symptoms ticked for it, and can edit them`() {
        val ratings = NightRatings(
            afterEndNight = RecordedRating(SleepRating.OKAY, at, setOf(RatingSymptom.SLOW_TO_FALL_ASLEEP, RatingSymptom.STILL_SLEEPY)),
            later = null,
            laterAsked = false,
        )

        assertEquals(
            MorningRatingCard(
                selected = SleepRating.OKAY,
                symptoms = listOf(RatingSymptom.STILL_SLEEPY, RatingSymptom.SLOW_TO_FALL_ASLEEP),
                symptomsEditable = true,
            ),
            buildMorningRatingCard(ratings, ratingEnabled = true),
        )
    }

    @Test
    fun `an unrated morning shows the faces alone`() {
        assertEquals(MorningRatingCard(selected = null), buildMorningRatingCard(NightRatings(null, null, false), ratingEnabled = true))
    }

    @Test
    fun `no card when the rating is switched off or the night cannot be rated`() {
        assertNull(buildMorningRatingCard(NightRatings(null, null, false), ratingEnabled = false))
        assertNull(buildMorningRatingCard(ratings = null, ratingEnabled = true))
    }
}
