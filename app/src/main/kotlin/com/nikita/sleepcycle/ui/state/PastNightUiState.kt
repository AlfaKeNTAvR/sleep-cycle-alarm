package com.nikita.sleepcycle.ui.state

import com.nikita.sleepcycle.night.RatingSymptom
import com.nikita.sleepcycle.night.SleepRating

/**
 * One past night reopened from the Logs screen: the same morning-report card that night ended on, plus the
 * settings it ran under. [report] is null when the log never recorded a summary at all (the night was never
 * ended), and [recordedStretchCount] is set only when the log kept the total but not the per-stretch detail -
 * the two ways a night can be less than fully renderable, each said out loud rather than shown as zeroes.
 */
data class PastNightUiState(
    val title: String,
    val isSimulated: Boolean,
    val report: NightScreenContent.MorningReport?,
    val deadlineTimeLabel: String?,
    val pickedLengthLabel: String?,
    val recordedStretchCount: Int?,
    /** The night's two ratings, each changeable here; null for a night started before the rating existed. */
    val ratings: PastNightRatings? = null,
)

/** Past night's "Your rating" card: the rating after End night and the later one, each its own row. */
data class PastNightRatings(val afterEndNight: PastNightRatingRow, val later: PastNightRatingRow)

/**
 * One rating row: the rating picked (null: not rated yet) and the clock time it was given, shown in the row's
 * title. Under it, the symptoms ticked for it in the dialog's order (owner spec, 2026-10-04), and whether
 * tapping that line opens the dialog to edit them - only an Okay or Bad rating has any.
 */
data class PastNightRatingRow(
    val rating: SleepRating?,
    val timeLabel: String?,
    val symptoms: List<RatingSymptom> = emptyList(),
    val showsSymptomsLine: Boolean = false,
)
