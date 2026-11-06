package com.nikita.sleepcycle.night

// File purpose: P7 (2026-11-06) - when the app asks Gadgetbridge to turn the band's automatic heart rate back on:
// after a reconnect's reply, on a night tick that finds the band not measuring (at most once per 30 min), at the
// end of a connection test that found the band connected, and at Start night. The broadcast itself is not here.

import com.nikita.sleepcycle.bridge.BandReconnectResult
import com.nikita.sleepcycle.bridge.StaleReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

private val TICK_AT = Instant.parse("2026-11-06T01:00:00Z")

private fun tickOutcome(syncOk: Boolean, failureCause: String? = null, staleReason: StaleReason? = null) = SyncOutcome(
    segments = emptyList(),
    newestSampleAt = null,
    syncOk = syncOk,
    exportFileModifiedAt = null,
    failureCause = failureCause,
    staleReason = staleReason,
)

private val TICK_NOT_MEASURING = tickOutcome(syncOk = false, staleReason = StaleReason.NO_HEART_RATE_VALUES)
private val TICK_FRESH = tickOutcome(syncOk = true)
private val TICK_NOTHING_NEW = tickOutcome(syncOk = false, staleReason = StaleReason.NO_NEW_SAMPLES)
private val TICK_SYNC_FAILED = tickOutcome(syncOk = false, failureCause = "timed out after PT20S")

private val REPLY = BandReconnectResult(connectedReplyReceived = true, durationMs = 4_000)
private val NO_REPLY = BandReconnectResult(connectedReplyReceived = false, durationMs = 33_000)

private val PASSED = SetupCheckReport(isReady = true, lines = listOf(SetupCheckLine("Connected.", SetupCheckLineSeverity.INFO)))
private val PASSED_WITHOUT_READING = SetupCheckReport(
    isReady = true,
    lines = listOf(SetupCheckLine(NO_HEART_RATE_READING_YET_SETUP_LINE, SetupCheckLineSeverity.WARNING)),
    staleReason = StaleReason.NO_HEART_RATE_VALUES,
)
private val FAILED_NOT_MEASURING = SetupCheckReport(
    isReady = false,
    lines = listOf(
        SetupCheckLine(BAND_NOT_MEASURING_SETUP_LINE, SetupCheckLineSeverity.ACTION_NEEDED),
        SetupCheckLine("Notifications are enabled.", SetupCheckLineSeverity.INFO),
    ),
    staleReason = StaleReason.NO_HEART_RATE_VALUES,
)
private val FAILED_NOTHING_NEW = SetupCheckReport(
    isReady = false,
    lines = listOf(SetupCheckLine("stale", SetupCheckLineSeverity.ACTION_NEEDED)),
    staleReason = StaleReason.NO_NEW_SAMPLES,
)
private val FAILED_SYNC = connectionTestTimedOutReport()

class HeartRateSettingAfterReconnectTest {
    @Test fun `a reconnect that got Gadgetbridge's reply sends the setting`() {
        assertTrue(shouldSendHeartRateSettingAfterReconnect(REPLY))
    }

    // Without the reply the band may still be disconnected, and Gadgetbridge would only store the value.
    @Test fun `a reconnect with no reply sends nothing`() {
        assertFalse(shouldSendHeartRateSettingAfterReconnect(NO_REPLY))
    }
}

class HeartRateSettingForTickTest {
    // Sending is harmless, so it does not wait for P6's 30 min warning grace.
    @Test fun `the first tick that finds the band not measuring sends it`() {
        assertTrue(shouldSendHeartRateSettingForTick(TICK_NOT_MEASURING, lastSentAt = null, now = TICK_AT))
    }

    @Test fun `a send under 30 min ago holds the next one back`() {
        assertFalse(shouldSendHeartRateSettingForTick(TICK_NOT_MEASURING, lastSentAt = TICK_AT.minusSeconds(29 * 60), now = TICK_AT))
    }

    @Test fun `30 min after the last send it sends again`() {
        assertTrue(shouldSendHeartRateSettingForTick(TICK_NOT_MEASURING, lastSentAt = TICK_AT.minusSeconds(30 * 60), now = TICK_AT))
    }

    // The phone's clock was set back: a "last send" in the future must not block sends for the rest of the night.
    @Test fun `a last send in the future does not block`() {
        assertTrue(shouldSendHeartRateSettingForTick(TICK_NOT_MEASURING, lastSentAt = TICK_AT.plusSeconds(3_600), now = TICK_AT))
    }

    @Test fun `fresh data, nothing new, or a failed sync sends nothing`() {
        assertFalse(shouldSendHeartRateSettingForTick(TICK_FRESH, lastSentAt = null, now = TICK_AT))
        assertFalse(shouldSendHeartRateSettingForTick(TICK_NOTHING_NEW, lastSentAt = null, now = TICK_AT))
        assertFalse(shouldSendHeartRateSettingForTick(TICK_SYNC_FAILED, lastSentAt = null, now = TICK_AT))
    }
}

class HeartRateSettingAfterTestTest {
    @Test fun `a pass, with or without a heart-rate reading, sends it`() {
        assertTrue(shouldSendHeartRateSettingAfterTest(ConnectionTestRun(PASSED)))
        assertTrue(shouldSendHeartRateSettingAfterTest(ConnectionTestRun(PASSED_WITHOUT_READING)))
    }

    // Require a reading: the test fails, but the sync proved the band connected.
    @Test fun `a test failed only for not measuring sends it`() {
        assertTrue(shouldSendHeartRateSettingAfterTest(ConnectionTestRun(FAILED_NOT_MEASURING)))
    }

    @Test fun `a failed sync or nothing new sends nothing, since the band may be out of reach`() {
        assertFalse(shouldSendHeartRateSettingAfterTest(ConnectionTestRun(FAILED_SYNC)))
        assertFalse(shouldSendHeartRateSettingAfterTest(ConnectionTestRun(FAILED_NOTHING_NEW)))
    }

    // The test's reconnect already sent it right after the reply; once per test is enough.
    @Test fun `a test whose reconnect got its reply does not send it a second time, but did send it`() {
        val run = ConnectionTestRun(PASSED, ReconnectTrigger.SYNC_FAILED, REPLY)

        assertFalse(shouldSendHeartRateSettingAfterTest(run))
        assertTrue(heartRateSettingSentDuringTest(run))
    }

    @Test fun `a test whose reconnect got no reply sends it at the end when the retry passed`() {
        val run = ConnectionTestRun(PASSED, ReconnectTrigger.SYNC_FAILED, NO_REPLY)

        assertTrue(shouldSendHeartRateSettingAfterTest(run))
        assertTrue(heartRateSettingSentDuringTest(run))
    }

    @Test fun `a test that never reached the band sent nothing`() {
        assertFalse(heartRateSettingSentDuringTest(ConnectionTestRun(FAILED_SYNC, ReconnectTrigger.SYNC_FAILED, NO_REPLY)))
    }
}

class HeartRateSettingAtStartNightTest {
    @Test fun `a real-band night with a band address sends it`() {
        assertTrue(shouldSendHeartRateSettingAtStartNight(simulatedBandData = false, deviceMac = "AA:BB:CC:DD:EE:FF"))
    }

    @Test fun `a simulated night or no band address sends nothing`() {
        assertFalse(shouldSendHeartRateSettingAtStartNight(simulatedBandData = true, deviceMac = "AA:BB:CC:DD:EE:FF"))
        assertFalse(shouldSendHeartRateSettingAtStartNight(simulatedBandData = false, deviceMac = null))
        assertFalse(shouldSendHeartRateSettingAtStartNight(simulatedBandData = false, deviceMac = " "))
    }
}

class HeartRateSettingSentReportTest {
    @Test fun `the Warn only line says the app asked the band and readings should come in a few minutes`() {
        val report = withHeartRateSettingSent(PASSED_WITHOUT_READING)

        assertEquals(NO_HEART_RATE_READING_YET_RESENT_SETUP_LINE, report.lines.single().text)
        assertTrue(report.heartRateSettingSent)
        assertTrue(report.isReady)
    }

    // The manual toggle stays as the fallback.
    @Test fun `the Require a reading failure says so too, keeps the manual fix, and leaves other lines alone`() {
        val report = withHeartRateSettingSent(FAILED_NOT_MEASURING)

        assertEquals(BAND_NOT_MEASURING_RESENT_SETUP_LINE, report.lines.first().text)
        assertTrue(report.lines.first().text.contains("Enable automatic heart rate measuring"))
        assertEquals(SetupCheckLineSeverity.ACTION_NEEDED, report.lines.first().severity)
        assertEquals("Notifications are enabled.", report.lines[1].text)
        assertFalse(report.isReady)
    }

    @Test fun `a pass with a real reading keeps its lines`() {
        assertEquals(PASSED.lines, withHeartRateSettingSent(PASSED).lines)
    }
}
