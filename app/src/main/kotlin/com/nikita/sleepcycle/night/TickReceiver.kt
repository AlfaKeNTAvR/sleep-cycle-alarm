package com.nikita.sleepcycle.night

// File purpose: wakes on the scheduled tick alarm and asks NightService to run one sync-plan cycle.
// Starting a foreground service can be refused by the OS (background start restrictions); that failure is
// logged and a retry tick is scheduled rather than silently losing the rest of the night.

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.Duration
import java.time.Instant

private const val LOG_TAG = "TickReceiver"
private val START_RETRY_DELAY: Duration = Duration.ofMinutes(1)

/** Entry point for the exact tick alarm: starts (or wakes) NightService to run the tick under a wake lock. */
class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val receivedAt = Instant.now()
        val scheduledForMillis = intent.getLongExtra(EXTRA_SCHEDULED_FOR_EPOCH_MILLI, -1L)
        val scheduledFor = if (scheduledForMillis >= 0) Instant.ofEpochMilli(scheduledForMillis) else null
        try {
            startNightServiceForTick(context, scheduledFor, receivedAt)
        } catch (error: IllegalStateException) {
            Log.e(LOG_TAG, "failed to start NightService for a tick, retrying in $START_RETRY_DELAY", error)
            scheduleTick(context, Instant.now().plus(START_RETRY_DELAY))
            logStartFailureIfNightActive(context, error)
        }
    }

    private fun logStartFailureIfNightActive(context: Context, error: IllegalStateException) {
        loadNightState(context)?.let { state ->
            appendNightLog(
                context, state.startedAt,
                NightLogEvent(Instant.now(), "error", mapOf("step" to "tick_service_start", "cause" to (error.message ?: error.toString()))),
                state.debugOptions.isAnyEnabled
            )
        }
    }
}
