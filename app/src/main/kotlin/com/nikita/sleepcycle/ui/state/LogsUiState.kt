package com.nikita.sleepcycle.ui.state

import com.nikita.sleepcycle.night.SleepRating
import java.io.File

/** One saved night log, ready to list and share. [isSimulated] is true for a night run with any debug option on (see NightLog.kt's `night-sim-` file prefix), shown as a "simulated" tag so it is never mistaken for a real night. */
data class NightLogSummary(
    val file: File,
    val displayName: String,
    val sizeLabel: String,
    val isSimulated: Boolean = false,
    /** The night's ratings as chips: the one after End night first, then the later one; empty when it has none. */
    val ratingChips: List<SleepRating> = emptyList(),
)

/** Everything the Logs screen shows. */
data class LogsUiState(
    val logs: List<NightLogSummary>,
)
