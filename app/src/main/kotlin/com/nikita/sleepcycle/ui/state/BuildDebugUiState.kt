package com.nikita.sleepcycle.ui.state

// File purpose: pure derivation of the Debug screen's state from the currently effective debug options and
// the persisted simulated sleep event timeline. T9-T11: also resolves the "Asleep" toggle's current state and
// every disabled-with-reason gate (U1's speed chips, T10's sleep controls) through the same pure
// predicates the controller's own second guards use, so the two can never disagree about what is enabled.

import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.SimulatedSleepEvent
import com.nikita.sleepcycle.night.SimulatedSleepEventKind
import com.nikita.sleepcycle.night.SimulationSpeed
import com.nikita.sleepcycle.night.SpeedChoice
import com.nikita.sleepcycle.night.isDebugTestAlarmAllowed
import com.nikita.sleepcycle.night.isSimulatedBandDataToggleAllowed
import com.nikita.sleepcycle.night.isSimulatedSleepControlAllowed
import com.nikita.sleepcycle.night.isSpeedSelectorAllowed
import com.nikita.sleepcycle.night.speedChoiceOf

/** Builds the Debug screen's state. [debugOptions] must already have passed through [com.nikita.sleepcycle.night.resolveDebugOptions]. [nightActive] gates the test alarm button (D1) and the simulated band data switch (V4). */
fun buildDebugUiState(debugOptions: DebugOptions, simulatedEvents: List<SimulatedSleepEvent>, nightActive: Boolean): DebugUiState {
    val currentSimulatedState = simulatedEvents.lastOrNull()?.kind ?: SimulatedSleepEventKind.AWAKE
    val selectedSpeed = speedChoiceOf(SimulationSpeed(debugOptions.warp, debugOptions.autoSpeed))
    return DebugUiState(
        simulatedBandData = debugOptions.simulatedBandData,
        simulatedBandDataControlEnabled = isSimulatedBandDataToggleAllowed(nightActive),
        speedChoices = SpeedChoice.entries,
        selectedSpeed = selectedSpeed,
        autoRunningSpeed = debugOptions.speed.takeIf { selectedSpeed == SpeedChoice.AUTO },
        simulatedTimeControlEnabled = isSpeedSelectorAllowed(debugOptions.simulatedBandData),
        simulatedAsleep = currentSimulatedState == SimulatedSleepEventKind.ASLEEP,
        sleepControlEnabled = isSimulatedSleepControlAllowed(debugOptions.simulatedBandData),
        canRingTestAlarm = isDebugTestAlarmAllowed(nightActive),
    )
}
