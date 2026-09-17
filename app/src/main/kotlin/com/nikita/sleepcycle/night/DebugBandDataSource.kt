package com.nikita.sleepcycle.night

// File purpose: the one function that chooses where a tick's band data comes from (NightOrchestrator.kt's
// own control flow never branches on debug options itself). Four combinations of the two switches that
// matter here (simulated band data, band command mode):
// - real data, real commands (debug off): unchanged real sync, exactly as before this feature existed.
// - simulated data + send-to-band: segments come from the simulator, but a real sync still runs to read the
//   REAL alarm table, since real commands are actually sent and need a real table to confirm against - the
//   existing blind/missing logic (resolveSyncOutcome, BandAlarmDecision.kt rule 4) applies unchanged if that
//   sync fails.
// - dry-run band commands (with or without simulated data): the alarm table used for confirmation is always
//   the simulated dry-run table (DryRunBandAlarmTable.kt), since dry run never touches the real band; a real
//   sync still runs when segments are not simulated, purely to keep the sleep data real.
// - simulated data + dry run: no real sync at all - the whole tick is self-contained, for testing with no
//   Gadgetbridge configured yet.

import android.content.Context
import java.time.Instant

/** Where this tick's sleep segments came from, for the night log's "data" event (`source=simulated`/`source=band`). */
enum class BandDataSource { SIMULATED, BAND }

/**
 * Resolves one tick's [SyncOutcome] from either the real band or the Debug screen's simulator, per
 * [debugOptions]. [simulatedEvents] is only consulted when [DebugOptions.simulatedBandData] is on.
 */
suspend fun readBandDataForTick(
    context: Context,
    debugOptions: DebugOptions,
    appSettings: AppSettings,
    state: NightState,
    simulatedEvents: List<SimulatedSleepEvent>,
    now: Instant
): SyncOutcome {
    val needsRealSync = !(debugOptions.simulatedBandData && debugOptions.bandCommandMode == BandCommandMode.DRY_RUN)
    val real = if (needsRealSync) resolveSyncOutcome(context, state, syncOrFail(context, appSettings, state, now), now) else null

    val simulateSegments = debugOptions.simulatedBandData
    val simulateBandAlarms = debugOptions.bandCommandMode == BandCommandMode.DRY_RUN

    // B (latent): on the real, undebugged path (neither segments nor the alarm table are simulated), every
    // field below would just copy `real`'s own value - so return it unchanged instead of reconstructing it
    // field by field, which would silently drop a future SyncOutcome field added here without a matching
    // update to this rebuild.
    if (real != null && !simulateSegments && !simulateBandAlarms) return real

    return SyncOutcome(
        segments = if (simulateSegments) buildSimulatedSegments(simulatedEvents, now) else checkNotNull(real).segments,
        newestSampleAt = real?.newestSampleAt,
        syncOk = real?.syncOk ?: true,
        exportFileModifiedAt = real?.exportFileModifiedAt,
        bandAlarms = if (simulateBandAlarms) simulatedDryRunBandAlarmSlots(state) else real?.bandAlarms,
        failureCause = real?.failureCause,
        source = if (simulateSegments) BandDataSource.SIMULATED else BandDataSource.BAND
    )
}
