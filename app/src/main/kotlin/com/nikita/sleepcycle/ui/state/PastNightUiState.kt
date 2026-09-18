package com.nikita.sleepcycle.ui.state

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
)
