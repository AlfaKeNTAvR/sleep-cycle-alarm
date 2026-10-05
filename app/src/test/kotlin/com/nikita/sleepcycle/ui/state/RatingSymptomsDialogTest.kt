package com.nikita.sleepcycle.ui.state

// File purpose: the "why Okay or Bad" dialog (owner spec, 2026-10-04) - when it is asked, which items it
// offers for each moment, and ticking items on it.

import com.nikita.sleepcycle.night.RatingMoment
import com.nikita.sleepcycle.night.RatingSymptom
import com.nikita.sleepcycle.night.RecordedRating
import com.nikita.sleepcycle.night.SleepRating
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class RatingSymptomsDialogTest {
    private val at = Instant.parse("2026-10-03T04:53:00Z")

    @Test
    fun `Okay or Bad picked over nothing or Good asks what felt off, Good never asks, Okay to Bad keeps the ticks`() {
        assertTrue(shouldAskSymptoms(previous = null, picked = SleepRating.OKAY))
        assertTrue(shouldAskSymptoms(previous = SleepRating.GOOD, picked = SleepRating.BAD))
        assertFalse(shouldAskSymptoms(previous = null, picked = SleepRating.GOOD))
        assertFalse(shouldAskSymptoms(previous = SleepRating.OKAY, picked = SleepRating.BAD))
        assertFalse(shouldAskSymptoms(previous = SleepRating.BAD, picked = SleepRating.BAD))
    }

    @Test
    fun `the morning dialog offers the four morning items in order, prefilled with what was ticked`() {
        val dialog = symptomsDialogFor(RatingMoment.AFTER_END_NIGHT, RecordedRating(SleepRating.OKAY, at, setOf(RatingSymptom.HEADACHE)))

        assertEquals(
            listOf(RatingSymptom.STILL_SLEEPY, RatingSymptom.WOKE_BEFORE_ALARM, RatingSymptom.SLOW_TO_FALL_ASLEEP, RatingSymptom.HEADACHE),
            dialog?.options,
        )
        assertEquals(SleepRating.OKAY, dialog?.rating)
        assertEquals(setOf(RatingSymptom.HEADACHE), dialog?.selected)
    }

    @Test
    fun `the tiles sit in balanced rows of at most three - the morning's four as two by two, the afternoon's three in one row`() {
        val morning = symptomsDialogFor(RatingMoment.AFTER_END_NIGHT, RecordedRating(SleepRating.OKAY, at))
        val afternoon = symptomsDialogFor(RatingMoment.LATER, RecordedRating(SleepRating.OKAY, at))

        assertEquals(2, morning?.tilesPerRow)
        assertEquals(3, afternoon?.tilesPerRow)
    }

    @Test
    fun `the 15 00 dialog offers the three afternoon items`() {
        val dialog = symptomsDialogFor(RatingMoment.LATER, RecordedRating(SleepRating.BAD, at))

        assertEquals(listOf(RatingSymptom.SLEEPY_IN_AFTERNOON, RatingSymptom.LOW_ENERGY, RatingSymptom.HARD_TO_FOCUS), dialog?.options)
        assertEquals(emptySet<RatingSymptom>(), dialog?.selected)
    }

    @Test
    fun `there is no dialog for a Good rating or a missing one`() {
        assertNull(symptomsDialogFor(RatingMoment.AFTER_END_NIGHT, RecordedRating(SleepRating.GOOD, at)))
        assertNull(symptomsDialogFor(RatingMoment.LATER, null))
    }

    @Test
    fun `tapping a tile ticks it, tapping it again unticks it`() {
        val dialog = requireNotNull(symptomsDialogFor(RatingMoment.LATER, RecordedRating(SleepRating.BAD, at)))

        val ticked = dialog.toggled(RatingSymptom.LOW_ENERGY).toggled(RatingSymptom.HARD_TO_FOCUS)
        assertEquals(setOf(RatingSymptom.LOW_ENERGY, RatingSymptom.HARD_TO_FOCUS), ticked.selected)
        assertEquals(setOf(RatingSymptom.HARD_TO_FOCUS), ticked.toggled(RatingSymptom.LOW_ENERGY).selected)
    }
}
