package com.nikita.sleepcycle.bridge

// File purpose: orchestrates one sync-then-read cycle against Gadgetbridge: sync, export, read, each
// step's duration and outcome logged. Only one sync runs at a time.

import android.content.Context
import android.net.Uri
import com.nikita.sleepcycle.night.NightLogEvent
import com.nikita.sleepcycle.night.nowInstant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

private val syncMutex = Mutex()
// P2 (owner decision, 2026-09-30): 20 s per step, was 60 s. A stale band link (2026-09-30) never answers, so the
// first sync after Start night took a full minute to fail and show the "band not syncing" warning; 20 s is the
// same bound the connection test uses and about 4x the slowest healthy night sync on record (2.5-5.3 s, ~200).
private val SYNC_TIMEOUT: Duration = Duration.ofSeconds(20)
private val EXPORT_TIMEOUT: Duration = Duration.ofSeconds(20)

/**
 * Runs sync -> wait for finish -> export -> wait for success/fail -> read, one step at a time.
 * A [Mutex] guarantees only one of these runs at a time, since a second sync started mid-export
 * would race with the first one's file. Each step is logged through [log] with its duration and outcome.
 */
suspend fun syncAndReadBandData(
    context: Context,
    exportUri: Uri,
    deviceMac: String,
    since: Instant,
    log: (NightLogEvent) -> Unit
): BandDataResult = syncMutex.withLock {
    val startedAt = Instant.now()

    val syncOutcome = waitForActivitySync(context)
    if (syncOutcome != null) {
        log(syncFailureEvent(startedAt, "activity_sync", syncOutcome))
        return@withLock BandDataResult.Failure("activity_sync", syncOutcome)
    }

    val exportOutcome = waitForDatabaseExport(context)
    if (exportOutcome != null) {
        log(syncFailureEvent(startedAt, "database_export", exportOutcome))
        return@withLock BandDataResult.Failure("database_export", exportOutcome)
    }

    val result = readBandData(context, exportUri, deviceMac, since)
    val durationMs = Duration.between(startedAt, Instant.now()).toMillis()
    log(
        when (result) {
            is BandDataResult.Success -> syncEvent(ok = true, durationMs = durationMs, failedStep = null, cause = null)
            is BandDataResult.Failure -> syncEvent(ok = false, durationMs = durationMs, failedStep = result.step, cause = result.cause)
        }
    )
    result
}

/** Returns null on success, or a plain-English failure cause. */
private suspend fun waitForActivitySync(context: Context): String? {
    val action = awaitGadgetbridgeBroadcast(context, setOf(ACTION_ACTIVITY_SYNC_FINISH), SYNC_TIMEOUT) {
        sendActivitySync(context)
    }
    return if (action == null) "timed out after $SYNC_TIMEOUT" else null
}

/** Returns null on success, or a plain-English failure cause. */
private suspend fun waitForDatabaseExport(context: Context): String? {
    val action = awaitGadgetbridgeBroadcast(
        context,
        setOf(ACTION_DATABASE_EXPORT_SUCCESS, ACTION_DATABASE_EXPORT_FAIL),
        EXPORT_TIMEOUT
    ) { sendDatabaseExport(context) }
    return when (action) {
        null -> "timed out after $EXPORT_TIMEOUT"
        ACTION_DATABASE_EXPORT_FAIL -> "Gadgetbridge reported export failure"
        else -> null
    }
}

private fun syncFailureEvent(startedAt: Instant, failedStep: String, cause: String): NightLogEvent =
    syncEvent(ok = false, durationMs = Duration.between(startedAt, Instant.now()).toMillis(), failedStep = failedStep, cause = cause)

private fun syncEvent(ok: Boolean, durationMs: Long, failedStep: String?, cause: String?): NightLogEvent {
    val fields = mutableMapOf("ok" to ok.toString(), "durationMs" to durationMs.toString())
    failedStep?.let { fields["step"] = it }
    cause?.let { fields["cause"] = it }
    // T4: virtual - this is a night-log event's own `at`, unlike startedAt/durationMs above (T4's named
    // real-time exception), which measure how long the real sync work itself took.
    return NightLogEvent(at = nowInstant(), type = "sync", fields = fields)
}
