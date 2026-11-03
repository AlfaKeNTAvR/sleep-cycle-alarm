package com.nikita.sleepcycle.night

// File purpose: P5 (PER-130 part 2) - when the app reconnects the band through Gadgetbridge on its own: which sync
// results call for it, the night's 30 min cooldown, and what a connection test does after a failed first attempt.
// The Bluetooth broadcasts themselves are stubbed; these are the pure rules the tick and the test both follow.

import com.nikita.sleepcycle.bridge.BandReconnectResult
import com.nikita.sleepcycle.bridge.StaleReason
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

private val NIGHT_TICK = Instant.parse("2026-11-03T05:00:00Z")

private fun syncOutcome(syncOk: Boolean, failureCause: String? = null, staleReason: StaleReason? = null) = SyncOutcome(
    segments = emptyList(),
    newestSampleAt = null,
    syncOk = syncOk,
    exportFileModifiedAt = null,
    failureCause = failureCause,
    staleReason = staleReason,
)

private val FRESH = syncOutcome(syncOk = true)
private val SYNC_TIMED_OUT = syncOutcome(syncOk = false, failureCause = "timed out after PT20S")
private val NOTHING_NEW = syncOutcome(syncOk = false, staleReason = StaleReason.NO_NEW_SAMPLES)
private val NOT_MEASURING = syncOutcome(syncOk = false, staleReason = StaleReason.NO_HEART_RATE_VALUES)

private val PASS = SetupCheckReport(isReady = true, lines = listOf(SetupCheckLine("Connected.", SetupCheckLineSeverity.INFO)))
private val TIMED_OUT_TEST = connectionTestTimedOutReport()
private val NOTHING_NEW_TEST = SetupCheckReport(isReady = false, lines = listOf(SetupCheckLine("stale", SetupCheckLineSeverity.ACTION_NEEDED)), staleReason = StaleReason.NO_NEW_SAMPLES)
private val NOT_MEASURING_TEST = SetupCheckReport(
    isReady = false,
    lines = listOf(SetupCheckLine(BAND_NOT_MEASURING_SETUP_LINE, SetupCheckLineSeverity.ACTION_NEEDED)),
    staleReason = StaleReason.NO_HEART_RATE_VALUES,
)
private val REPLIED = BandReconnectResult(connectedReplyReceived = true, durationMs = 4_000)
private val NO_REPLY = BandReconnectResult(connectedReplyReceived = false, durationMs = 33_000)

class ReconnectTriggerTest {
    @Test fun `a failed sync calls for a reconnect`() {
        assertEquals(ReconnectTrigger.SYNC_FAILED, reconnectTriggerFor(SYNC_TIMED_OUT))
    }

    @Test fun `a sync that brought nothing new calls for a reconnect`() {
        assertEquals(ReconnectTrigger.NO_NEW_SAMPLES, reconnectTriggerFor(NOTHING_NEW))
    }

    // PER-130: the band was connected and syncing; Gadgetbridge sends the heart-rate setting only when it is
    // toggled, so a reconnect would only drop a working link.
    @Test fun `a band that syncs but does not measure is never reconnected`() {
        assertNull(reconnectTriggerFor(NOT_MEASURING))
    }

    @Test fun `fresh data calls for nothing`() {
        assertNull(reconnectTriggerFor(FRESH))
    }

    @Test fun `a connection test follows the same rule`() {
        assertNull(reconnectTriggerFor(PASS))
        assertEquals(ReconnectTrigger.SYNC_FAILED, reconnectTriggerFor(TIMED_OUT_TEST))
        assertEquals(ReconnectTrigger.NO_NEW_SAMPLES, reconnectTriggerFor(NOTHING_NEW_TEST))
        assertNull(reconnectTriggerFor(NOT_MEASURING_TEST))
    }
}

class ReconnectCooldownTest {
    @Test fun `the cooldown is 30 minutes`() {
        assertEquals(Duration.ofMinutes(30), BAND_RECONNECT_COOLDOWN)
    }

    @Test fun `the first failure of the night reconnects`() {
        assertEquals(TickReconnectDecision.Reconnect(ReconnectTrigger.SYNC_FAILED), decideTickReconnect(SYNC_TIMED_OUT, lastAttemptAt = null, now = NIGHT_TICK))
    }

    @Test fun `a failure 10 minutes after a reconnect waits out the cooldown`() {
        val lastAttempt = NIGHT_TICK.minus(Duration.ofMinutes(10))

        assertEquals(
            TickReconnectDecision.InCooldown(ReconnectTrigger.NO_NEW_SAMPLES, lastAttempt),
            decideTickReconnect(NOTHING_NEW, lastAttempt, NIGHT_TICK),
        )
    }

    @Test fun `a failure 30 minutes after a reconnect reconnects again`() {
        assertEquals(
            TickReconnectDecision.Reconnect(ReconnectTrigger.SYNC_FAILED),
            decideTickReconnect(SYNC_TIMED_OUT, NIGHT_TICK.minus(Duration.ofMinutes(30)), NIGHT_TICK),
        )
    }

    @Test fun `a last attempt in the future after the clock was set back does not block reconnects`() {
        assertTrue(reconnectCooldownAllows(NIGHT_TICK.plus(Duration.ofHours(1)), NIGHT_TICK))
    }

    @Test fun `a fresh tick or a not-measuring tick needs nothing, cooldown or not`() {
        assertEquals(TickReconnectDecision.NotNeeded, decideTickReconnect(FRESH, null, NIGHT_TICK))
        assertEquals(TickReconnectDecision.NotNeeded, decideTickReconnect(NOT_MEASURING, null, NIGHT_TICK))
    }

    // A band out of range fails every tick. Ticks every 10 min for 2 h: the band is disconnected at most once per
    // 30 min, never every tick, and the attempt time the state keeps moves only when a reconnect is tried.
    @Test fun `a band failing every tick all night is reconnected once per 30 minutes`() {
        var lastAttemptAt: Instant? = null
        val reconnectedAt = mutableListOf<Instant>()
        (0..12).map { NIGHT_TICK.plus(Duration.ofMinutes(10L * it)) }.forEach { tickAt ->
            val decision = decideTickReconnect(SYNC_TIMED_OUT, lastAttemptAt, tickAt)
            val attemptedNow = if (decision is TickReconnectDecision.Reconnect) tickAt else null
            attemptedNow?.let(reconnectedAt::add)
            lastAttemptAt = lastReconnectAttemptAfterTick(lastAttemptAt, attemptedNow)
        }

        assertEquals(listOf(0L, 30L, 60L, 90L, 120L).map { NIGHT_TICK.plus(Duration.ofMinutes(it)) }, reconnectedAt)
    }
}

class ConnectionTestAttemptsTest {
    private val noPause: Duration = Duration.ZERO

    /** Runs [runConnectionTestAttempts] with scripted reports, recording each step in order. */
    private fun runScripted(automatic: Boolean, vararg reports: SetupCheckReport, reconnectResult: BandReconnectResult = REPLIED): Pair<ConnectionTestRun, List<String>> {
        val steps = mutableListOf<String>()
        val run = runBlocking {
            runConnectionTestAttempts(
                automatic,
                noPause,
                attempt = { number -> steps += "attempt $number"; reports[number - 1] },
                reconnect = { trigger -> steps += "reconnect ${trigger.logValue}"; reconnectResult },
            )
        }
        return run to steps
    }

    @Test fun `a first pass is final, with no reconnect`() {
        val (run, steps) = runScripted(automatic = true, PASS)

        assertEquals(PASS, run.report)
        assertEquals(listOf("attempt 1"), steps)
        assertNull(run.reconnectTrigger)
    }

    @Test fun `an automatic test that times out reconnects before its retry`() {
        val (run, steps) = runScripted(automatic = true, TIMED_OUT_TEST, PASS)

        assertEquals(listOf("attempt 1", "reconnect sync_failed", "attempt 2"), steps)
        assertEquals(PASS, run.report)
        assertEquals(ReconnectTrigger.SYNC_FAILED, run.reconnectTrigger)
        assertEquals(REPLIED, run.reconnectResult)
    }

    @Test fun `a tapped Test again also reconnects once on failure`() {
        val (run, steps) = runScripted(automatic = false, NOTHING_NEW_TEST, PASS)

        assertEquals(listOf("attempt 1", "reconnect no_new_samples", "attempt 2"), steps)
        assertEquals(PASS, run.report)
    }

    @Test fun `a reconnect that does not help leaves the second failure, and stops there`() {
        val (run, steps) = runScripted(automatic = true, TIMED_OUT_TEST, TIMED_OUT_TEST, reconnectResult = NO_REPLY)

        assertEquals(listOf("attempt 1", "reconnect sync_failed", "attempt 2"), steps)
        assertFalse(run.report.isReady)
        assertEquals(NO_REPLY, run.reconnectResult)
    }

    // P2's quiet retry stays for the one failure a reconnect cannot fix.
    @Test fun `an automatic test failed for not measuring retries without a reconnect`() {
        val (run, steps) = runScripted(automatic = true, NOT_MEASURING_TEST, NOT_MEASURING_TEST)

        assertEquals(listOf("attempt 1", "attempt 2"), steps)
        assertNull(run.reconnectTrigger)
    }

    @Test fun `a tapped test failed for not measuring runs once`() {
        val (run, steps) = runScripted(automatic = false, NOT_MEASURING_TEST)

        assertEquals(listOf("attempt 1"), steps)
        assertEquals(NOT_MEASURING_TEST, run.report)
    }

    @Test fun `the automatic retry pause stays 5 seconds`() {
        assertEquals(Duration.ofSeconds(5), AUTO_CONNECTION_TEST_RETRY_PAUSE)
    }
}

class IntentApiCheckTest {
    @Test fun `a pass is checked for the Intent API`() {
        assertTrue(shouldCheckIntentApi(ConnectionTestRun(PASS)))
    }

    @Test fun `a failed test is not checked, since a missing reply could just mean the band is out of range`() {
        assertFalse(shouldCheckIntentApi(ConnectionTestRun(TIMED_OUT_TEST)))
    }

    @Test fun `a pass after a reconnect that got its reply needs no check`() {
        assertFalse(shouldCheckIntentApi(ConnectionTestRun(PASS, ReconnectTrigger.SYNC_FAILED, REPLIED)))
    }

    @Test fun `a pass after a reconnect with no reply is still checked`() {
        assertTrue(shouldCheckIntentApi(ConnectionTestRun(PASS, ReconnectTrigger.SYNC_FAILED, NO_REPLY)))
    }

    @Test fun `the hint is a warning line that keeps the pass`() {
        val hinted = withBluetoothIntentApiHint(PASS)

        assertTrue(hinted.isReady)
        assertTrue(hinted.bluetoothIntentApiLooksOff)
        assertEquals(
            SetupCheckLine(
                "Turn on Gadgetbridge Settings > Developer options > Intent API > Bluetooth so the app can reconnect the band.",
                SetupCheckLineSeverity.WARNING,
            ),
            hinted.lines.last(),
        )
    }
}

class ReconnectResultLogTest {
    @Test fun `the result event names the trigger, the reply, the duration and what the re-sync found, never the MAC`() {
        val fields = reconnectResultFields(ReconnectTrigger.SYNC_FAILED, REPLIED, NOT_MEASURING)

        assertEquals(
            mapOf(
                "trigger" to "sync_failed",
                "connectedReply" to "true",
                "durationMs" to "4000",
                "resyncFresh" to "false",
                "resyncResult" to "no_heart_rate_values",
            ),
            fields,
        )
    }

    @Test fun `a fresh re-sync is logged as fresh`() {
        assertEquals("fresh", reconnectResultFields(ReconnectTrigger.NO_NEW_SAMPLES, NO_REPLY, FRESH)["resyncResult"])
    }
}
