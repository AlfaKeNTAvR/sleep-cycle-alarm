package com.nikita.sleepcycle.night

// File purpose: the one function that chooses where a tick's sleep segments come from (NightOrchestrator.kt's
// own control flow never branches on debug options itself). Two cases:
// - real data (simulatedBandData off): unchanged real sync, exactly as before this feature existed.
// - simulated data (simulatedBandData on): segments come from the Debug screen's simulator instead. D2: there
//   is no longer a band alarm table to keep real for, so a simulated night never needs a real sync at all.

import android.content.Context
import java.time.Instant

/** Where this tick's sleep segments came from, for the night log's "data" event (`source=simulated`/`source=band`). */
enum class BandDataSource { SIMULATED, BAND }

/**
 * Resolves one tick's [SyncOutcome] from either the real band or the Debug screen's simulator, per
 * [debugOptions.simulatedBandData]. [simulatedEvents] is only consulted when that switch is on.
 */
suspend fun readBandDataForTick(
    context: Context,
    debugOptions: DebugOptions,
    appSettings: AppSettings,
    state: NightState,
    simulatedEvents: List<SimulatedSleepEvent>,
    now: Instant
): SyncOutcome {
    if (!debugOptions.simulatedBandData) {
        return resolveSyncOutcome(context, state, syncOrFail(context, appSettings, state, now), now)
    }
    return SyncOutcome(
        segments = buildSimulatedSegments(simulatedEvents, now),
        newestSampleAt = null,
        syncOk = true,
        exportFileModifiedAt = null,
        failureCause = null,
        source = BandDataSource.SIMULATED
    )
}
