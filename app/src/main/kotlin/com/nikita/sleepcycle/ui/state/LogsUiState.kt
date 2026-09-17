package com.nikita.sleepcycle.ui.state

import java.io.File

/** One saved night log, ready to list and share. [isSimulated] is true for a night run with any debug option on (see NightLog.kt's `night-sim-` file prefix), shown as a "simulated" tag so it is never mistaken for a real night. */
data class NightLogSummary(
    val file: File,
    val displayName: String,
    val sizeLabel: String,
    val isSimulated: Boolean = false,
)

/** Everything the Logs screen shows. */
data class LogsUiState(
    val logs: List<NightLogSummary>,
)
