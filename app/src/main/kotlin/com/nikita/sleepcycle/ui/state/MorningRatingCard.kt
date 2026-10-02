package com.nikita.sleepcycle.ui.state

import com.nikita.sleepcycle.night.SleepRating

/** The morning report's "How did you sleep?" card (owner spec, 2026-10-02): [selected] is the rating given so far, null until one is tapped. */
data class MorningRatingCard(val selected: SleepRating?)
