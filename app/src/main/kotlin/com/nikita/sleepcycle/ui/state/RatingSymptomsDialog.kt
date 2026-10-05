package com.nikita.sleepcycle.ui.state

// File purpose: the "why Okay or Bad" dialog (owner spec, 2026-10-04) as plain state. After an Okay or Bad
// rating a dialog of tappable tiles asks which symptoms applied: the morning's four after the morning report's
// rating, the afternoon's three after the 15:00 one. The same dialog edits them later from Past night.

import com.nikita.sleepcycle.night.RatingMoment
import com.nikita.sleepcycle.night.RatingSymptom
import com.nikita.sleepcycle.night.RecordedRating
import com.nikita.sleepcycle.night.SleepRating

/** The open dialog: which [moment]'s rating it is about, that [rating] (for the subtitle and the tile color), the tiles in order, and the ones ticked. */
data class SymptomsDialogState(
    val moment: RatingMoment,
    val rating: SleepRating,
    val options: List<RatingSymptom>,
    val selected: Set<RatingSymptom>,
) {
    /**
     * How many tiles share a row: as few rows as three-wide allows, then spread evenly over them. With the
     * morning list cut to four (owner change, 2026-10-04, the layout left to this run), 3 + 1 would leave one
     * tile alone on its row, so four sit two by two; the afternoon's three stay one row of three.
     */
    val tilesPerRow: Int
        get() {
            val rows = (options.size + MAX_TILES_PER_ROW - 1) / MAX_TILES_PER_ROW
            return if (rows == 0) MAX_TILES_PER_ROW else (options.size + rows - 1) / rows
        }
}

private const val MAX_TILES_PER_ROW = 3

/**
 * Whether picking [picked] opens the dialog. Owner decision, 2026-10-05 (superseding 2026-10-04's "only from
 * nothing or Good"): Good never asks; every Okay or Bad pick asks, Okay to Bad and the same rating again
 * included, with the ticks kept - so a skipped dialog is always one tap away. Only Good clears them.
 */
fun shouldAskSymptoms(picked: SleepRating): Boolean = hasSymptoms(picked)

/** Whether a [rating] can have symptoms ticked for it: only Okay and Bad. */
fun hasSymptoms(rating: SleepRating?): Boolean = rating == SleepRating.OKAY || rating == SleepRating.BAD

/** The dialog for [moment]'s rating [recorded], prefilled with what was ticked; null when there is nothing to ask about (missing, or Good). */
fun symptomsDialogFor(moment: RatingMoment, recorded: RecordedRating?): SymptomsDialogState? {
    if (recorded == null || !hasSymptoms(recorded.rating)) return null
    return SymptomsDialogState(moment, recorded.rating, symptomsOffered(moment), recorded.symptoms)
}

/** The symptoms ticked for [recorded], in the dialog's order, for the small line under a rating. */
fun symptomsShown(recorded: RecordedRating?): List<RatingSymptom> =
    recorded?.let { rating -> RatingSymptom.entries.filter { it in rating.symptoms } } ?: emptyList()

/**
 * Whether the small line of ticked symptoms shows under [recorded]. Owner decision, 2026-10-05: only for an Okay
 * or Bad with something ticked - no "Tick what felt off" prompt; picking Okay or Bad again reopens the dialog.
 */
fun showsSymptomsLine(recorded: RecordedRating?): Boolean = hasSymptoms(recorded?.rating) && recorded?.symptoms?.isNotEmpty() == true

/** One tile tapped: ticks it, or unticks it if it was ticked. */
fun SymptomsDialogState.toggled(symptom: RatingSymptom): SymptomsDialogState =
    copy(selected = if (symptom in selected) selected - symptom else selected + symptom)

/** The items [moment]'s dialog offers, in the design's order. */
private fun symptomsOffered(moment: RatingMoment): List<RatingSymptom> = RatingSymptom.entries.filter { it.moment == moment }
