package com.nikita.sleepcycle.ui.state

import com.nikita.sleepcycle.night.NightRatings
import com.nikita.sleepcycle.night.RatingSymptom
import com.nikita.sleepcycle.night.SleepRating

/**
 * The morning report's "How did you sleep?" card (owner spec, 2026-10-02): [selected] is the rating given so
 * far, null until one is tapped. Under the faces, the symptoms ticked for an Okay or Bad rating and whether
 * that line opens the dialog to edit them (owner spec, 2026-10-04) - the same line Past night shows.
 */
data class MorningRatingCard(
    val selected: SleepRating?,
    val symptoms: List<RatingSymptom> = emptyList(),
    val symptomsEditable: Boolean = false,
)

/** The card for a night rated (or not) as [ratings] says; null hides it - the rating switched off, or a night started before the rating existed. */
fun buildMorningRatingCard(ratings: NightRatings?, ratingEnabled: Boolean): MorningRatingCard? {
    if (ratings == null || !ratingEnabled) return null
    val morning = ratings.afterEndNight
    return MorningRatingCard(selected = morning?.rating, symptoms = symptomsShown(morning), symptomsEditable = hasSymptoms(morning?.rating))
}
