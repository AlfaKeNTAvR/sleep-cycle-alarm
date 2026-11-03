package com.nikita.sleepcycle.night

// File purpose: P5 (PER-130 part 2) - one night tick's real-band sync, with at most one automatic reconnect when
// the band looks disconnected or stuck: sync, and if decideTickReconnect says so, reconnect through Gadgetbridge
// and sync once more. Each reconnect is written to the night log, never with the band's MAC.

import android.content.Context
import com.nikita.sleepcycle.bridge.BandReconnectResult
import com.nikita.sleepcycle.bridge.reconnectBand
import java.time.Instant

/**
 * P5: [syncOrFail] then [resolveSyncOutcome], plus at most one reconnect per tick ([decideTickReconnect]: a failed
 * sync or no new samples, and none in the last [BAND_RECONNECT_COOLDOWN]). After a reconnect the tick uses the
 * second sync's result, fresh or not; when that is still not ok, the night's existing warnings (P1, P4) take over,
 * with "Open Gadgetbridge" and the manual reconnect as the fallback. [now] is the tick's decisionNow.
 */
internal suspend fun syncWithReconnect(context: Context, appSettings: AppSettings, state: NightState, now: Instant): SyncOutcome {
    val first = resolveSyncOutcome(context, state, syncOrFail(context, appSettings, state, now), now)
    // No address configured: syncOrFail already failed with "settings", and there is no band to address.
    val deviceMac = appSettings.deviceMac ?: return first
    return when (val decision = decideTickReconnect(first, state.lastReconnectAttemptAt, now)) {
        TickReconnectDecision.NotNeeded -> first
        is TickReconnectDecision.InCooldown -> {
            logReconnect(
                context, state, now, "band_reconnect_skipped",
                mapOf("trigger" to decision.trigger.logValue, "cause" to "cooldown", "lastAttemptAt" to decision.lastAttemptAt.toString())
            )
            first
        }
        is TickReconnectDecision.Reconnect -> {
            logReconnect(context, state, now, "band_reconnect_started", mapOf("trigger" to decision.trigger.logValue))
            val reconnect = reconnectBand(context, deviceMac)
            val second = resolveSyncOutcome(context, state, syncOrFail(context, appSettings, state, now), now)
            logReconnect(context, state, now, "band_reconnect_result", reconnectResultFields(decision.trigger, reconnect, second))
            second.copy(reconnectAttemptedAt = now)
        }
    }
}

/** P5: `band_reconnect_result`'s fields: what triggered it, whether Gadgetbridge replied, how long, and what the re-sync found. */
internal fun reconnectResultFields(trigger: ReconnectTrigger, reconnect: BandReconnectResult, resync: SyncOutcome): Map<String, String> = mapOf(
    "trigger" to trigger.logValue,
    "connectedReply" to reconnect.connectedReplyReceived.toString(),
    "durationMs" to reconnect.durationMs.toString(),
    "resyncFresh" to resync.syncOk.toString(),
    "resyncResult" to resyncResultLogValue(resync),
)

/** P5: `fresh`, `sync_failed`, or the stale reason's own log value. */
private fun resyncResultLogValue(resync: SyncOutcome): String = when {
    resync.syncOk -> "fresh"
    resync.failureCause != null -> "sync_failed"
    else -> resync.staleReason?.logValue ?: "stale"
}

private fun logReconnect(context: Context, state: NightState, now: Instant, type: String, fields: Map<String, String>) {
    appendNightLog(context, state.realStartedAt, NightLogEvent(now, type, fields), state.debugOptions.isAnyEnabled)
}
