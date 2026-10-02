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
//
// Owner spec, 2026-10-02: the speed chips are 1x, 60x and Auto ([speedChoices]); the jump, "Reset to real
// time", the Clear button and the timeline are gone from the Debug screen, and so are their fields here.

import com.nikita.sleepcycle.night.SpeedChoice

/** Everything the Debug screen and the Night screen's Simulation card show and can act on. */
data class DebugUiState(
    val simulatedBandData: Boolean,
    /** V4: whether the simulated band data toggle may be used right now - see [com.nikita.sleepcycle.night.isSimulatedBandDataToggleAllowed]. False while a night is active: turning it off mid-night would clear an active warp out from under the running night's own frozen debugOptions snapshot. */
    val simulatedBandDataControlEnabled: Boolean,
    /** The speed chips, in order. */
    val speedChoices: List<SpeedChoice>,
    /** The selected chip. */
    val selectedSpeed: SpeedChoice,
    /** Under Auto, the speed it is running at right now (600 or 10), shown on the Auto chip; null outside Auto. */
    val autoRunningSpeed: Int?,
    /** U1: whether the speed chips are usable - see [com.nikita.sleepcycle.night.isSpeedSelectorAllowed]. */
    val simulatedTimeControlEnabled: Boolean,
    /** T9: the current simulated sleep state - true when the last simulated event was ASLEEP, false (including no events yet) when AWAKE. */
    val simulatedAsleep: Boolean,
    /** T10: whether the "Asleep" toggle may be used right now - see [com.nikita.sleepcycle.night.isSimulatedSleepControlAllowed]. */
    val sleepControlEnabled: Boolean,
    /** D1: false while a night is active - the test alarm must never be schedulable then. */
    val canRingTestAlarm: Boolean,
)
