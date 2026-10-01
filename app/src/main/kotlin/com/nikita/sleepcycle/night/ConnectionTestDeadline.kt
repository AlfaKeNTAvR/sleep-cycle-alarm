package com.nikita.sleepcycle.night

// File purpose: P2 - one hard real-time deadline around a whole "Test connection" attempt (sync, export, read),
// so the button can never sit on "Testing..." longer than CONNECTION_TEST_DEADLINE. The per-step 60 s timeouts in
// bridge/BandDataSync.kt stay as they are for night ticks; only the connection test is bounded tighter here.

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration

/**
 * P2: how long one connection-test attempt may take, end to end. Every healthy test on record took 2.5-4.8 s
 * (setup.jsonl, 11 passes, 2026-09-20..29) and night syncs 2.5-5.3 s (~200 syncs), so 20 s is about 4x the
 * slowest healthy round trip. Every failed test on record was Gadgetbridge never answering the sync at all
 * (the old 60 s step timeout, 4 times) - waiting longer than this only delays the same failure.
 */
val CONNECTION_TEST_DEADLINE: Duration = Duration.ofSeconds(20)

/**
 * P2: runs [attempt] and returns its report, or [connectionTestTimedOutReport] once [deadline] (REAL time) has
 * passed. The attempt runs detached on [Dispatchers.IO], so this returns on time even if the attempt is stuck in
 * blocking I/O that ignores cancellation (copying the export file); it is cancelled and left to finish on its own.
 * An exception from [attempt] is rethrown to the caller.
 */
suspend fun runConnectionTestWithin(deadline: Duration, attempt: suspend () -> SetupCheckReport): SetupCheckReport {
    val running = CoroutineScope(SupervisorJob() + Dispatchers.IO).async { attempt() }
    val report = withTimeoutOrNull(deadline.toMillis()) { running.await() }
    if (report != null) return report
    running.cancel()
    return connectionTestTimedOutReport()
}

/**
 * P2: the automatic test's pause before its one retry. In 3 of the 4 failed tests on record, a manual retry soon
 * after passed - the first sync request seems to nudge Gadgetbridge - so one quiet retry hides most one-off
 * failures. Short, because the owner is waiting at bedtime: worst case is 20 s + 5 s + 20 s.
 */
val AUTO_CONNECTION_TEST_RETRY_PAUSE: Duration = Duration.ofSeconds(5)

/**
 * P2: runs [attempt] (numbered 1, then 2) and, if the first report is not ready, waits [pause] and tries exactly
 * once more, returning the second report whatever it is. Used for the automatic test only; a tapped test runs once.
 */
suspend fun runWithOneRetry(pause: Duration, attempt: suspend (attemptNumber: Int) -> SetupCheckReport): SetupCheckReport {
    val first = attempt(1)
    if (first.isReady) return first
    delay(pause.toMillis())
    return attempt(2)
}

/**
 * P2: the failed report a timed-out attempt shows. It names the fix, not just the symptom: on 2026-09-30
 * Gadgetbridge showed the band as connected (but with no battery level) while no sync ever finished, all night;
 * disconnecting and reconnecting the band in Gadgetbridge fixed it. Gadgetbridge's own "connected" is therefore
 * never trusted - only a finished sync round trip counts. The owner cut the wording to the fix alone
 * (2026-09-30 prototype review): how long the app waited is not something he acts on.
 */
fun connectionTestTimedOutReport(): SetupCheckReport = SetupCheckReport(
    isReady = false,
    lines = listOf(
        SetupCheckLine(
            "In Gadgetbridge, disconnect and reconnect the band, then test again.",
            SetupCheckLineSeverity.ACTION_NEEDED
        )
    )
)
