package com.nikita.sleepcycle.night

// File purpose: P5 (PER-130 part 2) - one night tick's real-band sync, with at most one automatic reconnect when
// the band looks disconnected or stuck: sync, and if decideTickReconnect says so, reconnect through Gadgetbridge
// and sync once more. P7: also asks Gadgetbridge to turn the band's automatic heart rate back on, after a
// reconnect's reply and when the sync finds the band not measuring. Each step is written to the night log, never
// with the band's MAC.

import android.content.Context
import com.nikita.sleepcycle.bridge.BandReconnectResult
import com.nikita.sleepcycle.bridge.reconnectBand
import com.nikita.sleepcycle.bridge.sendAutomaticHeartRateOn
import java.time.Instant

/**
 * P5: [syncOrFail] then [resolveSyncOutcome], plus at most one reconnect per tick ([decideTickReconnect]: a failed
 * sync or no new samples, and none in the last [BAND_RECONNECT_COOLDOWN]). After a reconnect the tick uses the
 * second sync's result, fresh or not; when that is still not ok, the night's existing warnings (P1, P4) take over,
 * with "Open Gadgetbridge" and the manual reconnect as the fallback. [now] is the tick's decisionNow.
 * P7: at most one heart-rate setting send per tick: after a reconnect's reply, or else when the result is "not
 * measuring" and none was sent in the last [HEART_RATE_SETTING_RESEND_INTERVAL]. Recorded in
 * [SyncOutcome.heartRateSettingSentAt].
 */
internal suspend fun syncWithReconnect(context: Context, appSettings: AppSettings, state: NightState, now: Instant): SyncOutcome {
    val outcome = syncWithOneReconnect(context, appSettings, state, now)
    val deviceMac = appSettings.deviceMac ?: return outcome
    val lastSentAt = outcome.heartRateSettingSentAt ?: state.lastHeartRateSettingSentAt
    if (!shouldSendHeartRateSettingForTick(outcome, lastSentAt, now)) return outcome
    sendHeartRateSettingAtNight(context, deviceMac, state, now, HeartRateSettingTrigger.NOT_MEASURING)
    return outcome.copy(heartRateSettingSentAt = now)
}

private suspend fun syncWithOneReconnect(context: Context, appSettings: AppSettings, state: NightState, now: Instant): SyncOutcome {
    val first = resolveSyncOutcome(context, state, syncOrFail(context, appSettings, state, now), now)
    // No address configured: syncOrFail already failed with "settings", and there is no band to address.
    val deviceMac = appSettings.deviceMac ?: return first
    return when (val decision = decideTickReconnect(first, state.lastReconnectAttemptAt, now)) {
        TickReconnectDecision.NotNeeded -> first
        is TickReconnectDecision.InCooldown -> {
            logBandRecovery(
                context, state, now, "band_reconnect_skipped",
                mapOf("trigger" to decision.trigger.logValue, "cause" to "cooldown", "lastAttemptAt" to decision.lastAttemptAt.toString())
            )
            first
        }
        is TickReconnectDecision.Reconnect -> {
            logBandRecovery(context, state, now, "band_reconnect_started", mapOf("trigger" to decision.trigger.logValue))
            val reconnect = reconnectBand(context, deviceMac)
            // P7: the band is connected again, so the setting reaches it now.
            val heartRateSettingSentAt = if (shouldSendHeartRateSettingAfterReconnect(reconnect)) {
                sendHeartRateSettingAtNight(context, deviceMac, state, now, HeartRateSettingTrigger.AFTER_RECONNECT)
                now
            } else {
                null
            }
            val second = resolveSyncOutcome(context, state, syncOrFail(context, appSettings, state, now), now)
            logBandRecovery(context, state, now, "band_reconnect_result", reconnectResultFields(decision.trigger, reconnect, second))
            second.copy(reconnectAttemptedAt = now, heartRateSettingSentAt = heartRateSettingSentAt)
        }
    }
}

/** P7: sends the automatic heart rate setting and writes `heart_rate_setting_sent` (`trigger`) to the night log. */
internal fun sendHeartRateSettingAtNight(context: Context, deviceMac: String, state: NightState, now: Instant, trigger: HeartRateSettingTrigger) {
    sendAutomaticHeartRateOn(context, deviceMac)
    logBandRecovery(context, state, now, "heart_rate_setting_sent", mapOf("trigger" to trigger.logValue))
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

private fun logBandRecovery(context: Context, state: NightState, now: Instant, type: String, fields: Map<String, String>) {
    appendNightLog(context, state.realStartedAt, NightLogEvent(now, type, fields), state.debugOptions.isAnyEnabled)
}
