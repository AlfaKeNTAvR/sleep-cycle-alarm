package com.nikita.sleepcycle.ui.state

// File purpose: the Debug screen's state shape - the two switches, the simulated sleep timeline as plain
// text, and which simulator buttons currently do something.

/** One line of the simulated sleep timeline: kind, from, to - exactly as the task spec asks for it. */
data class SimulatedSleepLine(val kindLabel: String, val fromTimeLabel: String, val toTimeLabel: String)

/** Everything the Debug screen shows and can act on. */
data class DebugUiState(
    val simulatedBandData: Boolean,
    val fastNight: Boolean,
    val simulatedTimeline: List<SimulatedSleepLine>,
    val canFallAsleep: Boolean,
    val canWakeUp: Boolean,
    val canFallBackAsleep: Boolean,
    val canClearSimulatedSleep: Boolean,
    /** D1: false while a night is active - the test alarm must never be schedulable then. */
    val canRingTestAlarm: Boolean,
)
