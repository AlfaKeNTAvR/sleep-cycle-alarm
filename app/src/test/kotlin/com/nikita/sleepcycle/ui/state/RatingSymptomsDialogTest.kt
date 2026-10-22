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
    fun `every Okay or Bad pick asks what felt off, also Okay to Bad and the same rating again, Good never asks`() {
        // Owner decision, 2026-10-05: switching between Okay and Bad, or picking the same one again, reopens the
        // dialog with the ticks kept, so a skipped dialog can always be reached again.
        assertTrue(shouldAskSymptoms(SleepRating.OKAY))
        assertTrue(shouldAskSymptoms(SleepRating.BAD))
        assertFalse(shouldAskSymptoms(SleepRating.GOOD))
    }

    @Test
    fun `the morning dialog offers Still sleepy and Headache first, then Woke before alarm, Slow to fall asleep and Kept napping, prefilled with what was ticked`() {
        // Owner spec, 2026-10-21: "I kept resetting the nap alarm... because I wanted to keep sleeping more" -
        // Kept napping joins the morning list, last.
        val dialog = symptomsDialogFor(RatingMoment.AFTER_END_NIGHT, RecordedRating(SleepRating.OKAY, at, setOf(RatingSymptom.HEADACHE)))

        assertEquals(
            listOf(
                RatingSymptom.STILL_SLEEPY, RatingSymptom.HEADACHE, RatingSymptom.WOKE_BEFORE_ALARM,
                RatingSymptom.SLOW_TO_FALL_ASLEEP, RatingSymptom.KEPT_NAPPING,
            ),
            dialog?.options,
        )
        assertEquals(SleepRating.OKAY, dialog?.rating)
        assertEquals(setOf(RatingSymptom.HEADACHE), dialog?.selected)
    }

    @Test
    fun `the tiles sit in balanced rows of at most three - the morning's five as three over two, the afternoon's four as two by two`() {
        // Owner spec, 2026-10-21: with Kept napping and Heavy head added, the rows stay balanced on a phone.
        val morning = symptomsDialogFor(RatingMoment.AFTER_END_NIGHT, RecordedRating(SleepRating.OKAY, at))
        val afternoon = symptomsDialogFor(RatingMoment.LATER, RecordedRating(SleepRating.OKAY, at))

        assertEquals(3, morning?.tilesPerRow)
        assertEquals(2, afternoon?.tilesPerRow)
    }

    @Test
    fun `the 15 00 dialog offers the four afternoon items, Heavy head last`() {
        // Owner spec, 2026-10-21: "for the midday symptoms, I want to add headache also, or like heavy head".
        val dialog = symptomsDialogFor(RatingMoment.LATER, RecordedRating(SleepRating.BAD, at))

        assertEquals(
            listOf(RatingSymptom.SLEEPY_IN_AFTERNOON, RatingSymptom.LOW_ENERGY, RatingSymptom.HARD_TO_FOCUS, RatingSymptom.HEAVY_HEAD),
            dialog?.options,
        )
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
