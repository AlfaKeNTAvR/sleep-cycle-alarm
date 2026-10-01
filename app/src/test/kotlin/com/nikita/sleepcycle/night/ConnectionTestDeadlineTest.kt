package com.nikita.sleepcycle.night

// File purpose: P2 - one connection-test attempt always ends within its deadline, even when the work behind it
// never answers (Gadgetbridge not connected to the band) or blocks a thread outright. Uses short real-time
// deadlines; the app itself uses CONNECTION_TEST_DEADLINE.

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

private val SHORT_DEADLINE: Duration = Duration.ofMillis(200)

class ConnectionTestDeadlineTest {
    @Test
    fun `an attempt that never answers ends as a failed report at the deadline`() {
        val startedNanos = System.nanoTime()

        val report = runBlocking { runConnectionTestWithin(SHORT_DEADLINE) { awaitCancellation() } }

        val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000
        assertFalse(report.isReady)
        assertTrue(elapsedMs < 2_000, "took $elapsedMs ms")
        val line = report.lines.single()
        assertEquals(SetupCheckLineSeverity.ACTION_NEEDED, line.severity)
        assertEquals(
            "In Gadgetbridge, disconnect and reconnect the band, then test again.",
            line.text
        )
    }

    @Test
    fun `an attempt stuck in blocking work still ends at the deadline`() {
        val startedNanos = System.nanoTime()

        val report = runBlocking { runConnectionTestWithin(SHORT_DEADLINE) { Thread.sleep(10_000); PASSING_REPORT } }

        val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000
        assertFalse(report.isReady)
        assertTrue(elapsedMs < 2_000, "took $elapsedMs ms")
    }

    @Test
    fun `an attempt that answers in time passes its own report through unchanged`() {
        val report = runBlocking { runConnectionTestWithin(Duration.ofSeconds(5)) { PASSING_REPORT } }

        assertEquals(PASSING_REPORT, report)
    }

    @Test
    fun `the app's deadline is 20 seconds and the failure line names only the fix`() {
        assertEquals(Duration.ofSeconds(20), CONNECTION_TEST_DEADLINE)
        assertEquals(
            "In Gadgetbridge, disconnect and reconnect the band, then test again.",
            connectionTestTimedOutReport().lines.single().text
        )
    }
}

/** P2: the automatic test tries once more after a short pause before it reports a failure; a first pass is final. */
class ConnectionTestRetryTest {
    private val noPause: Duration = Duration.ZERO

    @Test
    fun `a first pass is returned without a second attempt`() {
        val attempts = mutableListOf<Int>()

        val report = runBlocking { runWithOneRetry(noPause) { number -> attempts += number; PASSING_REPORT } }

        assertEquals(PASSING_REPORT, report)
        assertEquals(listOf(1), attempts)
    }

    @Test
    fun `a first failure followed by a pass returns the pass`() {
        val attempts = mutableListOf<Int>()

        val report = runBlocking {
            runWithOneRetry(noPause) { number -> attempts += number; if (number == 1) FAILING_REPORT else PASSING_REPORT }
        }

        assertEquals(PASSING_REPORT, report)
        assertEquals(listOf(1, 2), attempts)
    }

    @Test
    fun `two failures return the second failure and stop there`() {
        val secondFailure = SetupCheckReport(isReady = false, lines = listOf(SetupCheckLine("second", SetupCheckLineSeverity.ACTION_NEEDED)))
        val attempts = mutableListOf<Int>()

        val report = runBlocking {
            runWithOneRetry(noPause) { number -> attempts += number; if (number == 1) FAILING_REPORT else secondFailure }
        }

        assertEquals(secondFailure, report)
        assertEquals(listOf(1, 2), attempts)
    }

    @Test
    fun `the app pauses 5 seconds before the retry`() {
        assertEquals(Duration.ofSeconds(5), AUTO_CONNECTION_TEST_RETRY_PAUSE)
    }
}

private val PASSING_REPORT = SetupCheckReport(isReady = true, lines = listOf(SetupCheckLine("Connected.", SetupCheckLineSeverity.INFO)))
private val FAILING_REPORT = SetupCheckReport(isReady = false, lines = listOf(SetupCheckLine("first", SetupCheckLineSeverity.ACTION_NEEDED)))
