package com.nikita.sleepcycle.ui.state

// File purpose: the Debug screen's state shape - the two switches, the simulated sleep timeline as plain
// text, and which controls currently do something.
//
// T7: `fastNight: Boolean` is gone, replaced by `speed: Int` plus the fixed list of speeds the segmented
// selector offers (T12: a proper segmented control, reusing SleepLengthChip - see DebugScreen.kt).
//
// T9 SUPERSEDES the old three-button design: one "Asleep" toggle ([simulatedAsleep]) replaces
// canFallAsleep/canWakeUp/canFallBackAsleep. U1/T10: [simulatedTimeControlEnabled] and [sleepControlEnabled]
// both gate on simulated band data being on - a warped clock or a simulated sleep timeline is meaningless
// without it (see DebugOptions.kt/BandDataSimulator.kt). T11: [jumpAllowedNow] additionally requires no active
// night.

import java.time.LocalTime

/** One line of the simulated sleep timeline: kind, from, to - exactly as the task spec asks for it. */
/** [durationLabel] (W5) is how long that segment lasted - two clock times alone make the reader subtract, and the last segment's grows on every refresh, which is the clearest sign the simulated clock is actually running. */
data class SimulatedSleepLine(val kindLabel: String, val fromTimeLabel: String, val toTimeLabel: String, val durationLabel: String)

/** Everything the Debug screen shows and can act on. */
data class DebugUiState(
    val simulatedBandData: Boolean,
    /** V4: whether the simulated band data toggle may be used right now - see [com.nikita.sleepcycle.night.isSimulatedBandDataToggleAllowed]. False while a night is active: turning it off mid-night would clear an active warp out from under the running night's own frozen debugOptions snapshot. */
    val simulatedBandDataControlEnabled: Boolean,
    /** T7: the currently selected simulated-clock speed - 1 is real time. */
    val speed: Int,
    /** T7: the speeds the segmented selector offers, in order - see [com.nikita.sleepcycle.night.SIMULATION_SPEEDS]. */
    val availableSpeeds: List<Int>,
    /** U1: whether the speed selector and the jump action's own "requires simulated band data" precondition are currently met - see [com.nikita.sleepcycle.night.isSpeedSelectorAllowed]. */
    val simulatedTimeControlEnabled: Boolean,
    val simulatedTimeline: List<SimulatedSleepLine>,
    /** T9: the current simulated sleep state - true when the last simulated event was ASLEEP, false (including no events yet) when AWAKE. */
    val simulatedAsleep: Boolean,
    /** T10: whether the "Asleep" toggle may be used right now - see [com.nikita.sleepcycle.night.isSimulatedSleepControlAllowed]. */
    val sleepControlEnabled: Boolean,
    /** W2: simply whether there is something to clear. Unlike the "Asleep" toggle this does NOT require simulated band data, so a timeline left over from an earlier session can always be emptied. */
    val canClearSimulatedSleep: Boolean,
    /** D1: false while a night is active - the test alarm must never be schedulable then. */
    val canRingTestAlarm: Boolean,
    /** T11/U1: whether "Set simulated time" may be applied right now - see [com.nikita.sleepcycle.night.isJumpToTimeAllowed]. */
    val jumpAllowedNow: Boolean,
    /** W2: whether "Reset to real time" may be used right now - only a night in progress blocks it, never the simulated band data switch. */
    val resetToRealTimeAllowedNow: Boolean,
    /** T11 (amended): the time picker's initial value is the CURRENT virtual time, not the real time. */
    val currentVirtualTime: LocalTime,
)
