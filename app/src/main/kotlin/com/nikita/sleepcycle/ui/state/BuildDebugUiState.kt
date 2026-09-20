package com.nikita.sleepcycle.ui.state

// File purpose: pure derivation of the Debug screen's state from the currently effective debug options and
// the persisted simulated sleep event timeline.

import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.SimulatedSleepEvent
import com.nikita.sleepcycle.night.SimulatedSleepEventKind
import com.nikita.sleepcycle.night.buildSimulatedSegments
import com.nikita.sleepcycle.night.isDebugTestAlarmAllowed
import com.nikita.sleepcycle.night.isSimulatedSleepEventAllowed
import com.nikita.sleepcycle.ui.format.formatClockTime
import java.time.Instant
import java.time.ZoneId

/** Builds the Debug screen's state. [debugOptions] must already have passed through [com.nikita.sleepcycle.night.resolveDebugOptions]. [nightActive] gates the test alarm button (D1). */
fun buildDebugUiState(debugOptions: DebugOptions, simulatedEvents: List<SimulatedSleepEvent>, now: Instant, zone: ZoneId, nightActive: Boolean): DebugUiState {
    val segments = buildSimulatedSegments(simulatedEvents, now)
    val timeline = segments.map { segment ->
        SimulatedSleepLine(kindLabel = segment.kind.name, fromTimeLabel = formatClockTime(segment.start, zone), toTimeLabel = formatClockTime(segment.end, zone))
    }
    return DebugUiState(
        simulatedBandData = debugOptions.simulatedBandData,
        fastNight = debugOptions.fastNight,
        simulatedTimeline = timeline,
        canFallAsleep = isSimulatedSleepEventAllowed(simulatedEvents, SimulatedSleepEventKind.FELL_ASLEEP),
        canWakeUp = isSimulatedSleepEventAllowed(simulatedEvents, SimulatedSleepEventKind.WOKE_UP),
        canFallBackAsleep = isSimulatedSleepEventAllowed(simulatedEvents, SimulatedSleepEventKind.FELL_BACK_ASLEEP),
        canClearSimulatedSleep = simulatedEvents.isNotEmpty(),
        canRingTestAlarm = isDebugTestAlarmAllowed(nightActive),
    )
}
