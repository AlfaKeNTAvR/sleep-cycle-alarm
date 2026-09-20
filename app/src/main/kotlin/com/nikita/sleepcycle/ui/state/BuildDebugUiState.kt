package com.nikita.sleepcycle.ui.state

// File purpose: pure derivation of the Debug screen's state from the currently effective debug options and
// the persisted simulated sleep event timeline. T9-T11: also resolves the "Asleep" toggle's current state and
// every disabled-with-reason gate (U1's speed selector/jump, T10's sleep controls) through the same pure
// predicates the controller's own second guards use, so the two can never disagree about what is enabled.

import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.SIMULATION_SPEEDS
import com.nikita.sleepcycle.night.SimulatedSleepEvent
import com.nikita.sleepcycle.night.SimulatedSleepEventKind
import com.nikita.sleepcycle.night.buildSimulatedSegments
import com.nikita.sleepcycle.night.isDebugTestAlarmAllowed
import com.nikita.sleepcycle.night.isJumpToTimeAllowed
import com.nikita.sleepcycle.night.isResetToRealTimeAllowed
import com.nikita.sleepcycle.night.isSimulatedBandDataToggleAllowed
import com.nikita.sleepcycle.night.isSimulatedSleepControlAllowed
import com.nikita.sleepcycle.night.isSpeedSelectorAllowed
import com.nikita.sleepcycle.ui.format.formatClockTime
import java.time.Instant
import java.time.ZoneId

/** Builds the Debug screen's state. [debugOptions] must already have passed through [com.nikita.sleepcycle.night.resolveDebugOptions]. [now] is virtual (T4) - it both draws the simulated timeline up to the present and seeds the jump picker's initial value (T11 amendment). [nightActive] gates the test alarm button (D1) and the jump action (T11). */
fun buildDebugUiState(debugOptions: DebugOptions, simulatedEvents: List<SimulatedSleepEvent>, now: Instant, zone: ZoneId, nightActive: Boolean): DebugUiState {
    val segments = buildSimulatedSegments(simulatedEvents, now)
    val timeline = segments.map { segment ->
        SimulatedSleepLine(kindLabel = segment.kind.name, fromTimeLabel = formatClockTime(segment.start, zone), toTimeLabel = formatClockTime(segment.end, zone))
    }
    val simulatedTimeControlEnabled = isSpeedSelectorAllowed(debugOptions.simulatedBandData)
    val sleepControlEnabled = isSimulatedSleepControlAllowed(debugOptions.simulatedBandData)
    val currentSimulatedState = simulatedEvents.lastOrNull()?.kind ?: SimulatedSleepEventKind.AWAKE
    return DebugUiState(
        simulatedBandData = debugOptions.simulatedBandData,
        simulatedBandDataControlEnabled = isSimulatedBandDataToggleAllowed(nightActive),
        speed = debugOptions.speed,
        availableSpeeds = SIMULATION_SPEEDS,
        simulatedTimeControlEnabled = simulatedTimeControlEnabled,
        simulatedTimeline = timeline,
        simulatedAsleep = currentSimulatedState == SimulatedSleepEventKind.ASLEEP,
        sleepControlEnabled = sleepControlEnabled,
        // W2: clearing needs only something to clear - never the simulated band data switch, which a
        // leftover timeline outlives.
        canClearSimulatedSleep = simulatedEvents.isNotEmpty(),
        canRingTestAlarm = isDebugTestAlarmAllowed(nightActive),
        jumpAllowedNow = isJumpToTimeAllowed(debugOptions.simulatedBandData, nightActive),
        resetToRealTimeAllowedNow = isResetToRealTimeAllowed(nightActive),
        currentVirtualTime = now.atZone(zone).toLocalTime(),
    )
}
