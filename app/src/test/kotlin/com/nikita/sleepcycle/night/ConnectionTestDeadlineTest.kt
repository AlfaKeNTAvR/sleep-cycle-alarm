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

private val PASSING_REPORT = SetupCheckReport(isReady = true, lines = listOf(SetupCheckLine("Connected.", SetupCheckLineSeverity.INFO)))

