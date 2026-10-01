package com.nikita.sleepcycle.ui.state

// File purpose: P2 - the connection test (automatic or tapped) shows on the Before-bed screen as "checking" or
// "failed, with its reason", in the screen's own text, never as a dialog. Goes through buildUiState, the same hop
// NightViewModel uses, so a dropped connectionTest argument would fail here.

import com.nikita.sleepcycle.night.SetupCheckLine
import com.nikita.sleepcycle.night.SetupCheckLineSeverity
import com.nikita.sleepcycle.night.SetupCheckReport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private fun beforeBedWith(connectionTest: ConnectionTestState): BeforeBedUiState = buildUiState(
    appSettings = testAppSettings(),
    nightState = null,
    engineView = null,
    now = instant("2026-09-30T23:00"),
    zone = testZone,
    permissionStatus = testPermissionStatus(),
    gadgetbridgeInstalled = true,
    screen = Screen.BeforeBed,
    connectionTest = connectionTest,
    nightLogFiles = emptyList(),
    confirmingEndNight = false,
    showingMorningReport = false,
    morningReportEndedAt = null,
    pendingOutOfBedNudgeAt = null,
    errorMessage = null,
).beforeBed

class BeforeBedBandCheckTest {
    @Test fun `no test run yet shows nothing`() {
        assertEquals(BandCheckStatus.None, beforeBedWith(ConnectionTestState.Idle).bandCheck)
    }

    @Test fun `a running test shows as checking`() {
        assertEquals(BandCheckStatus.Checking, beforeBedWith(ConnectionTestState.Running).bandCheck)
    }

    @Test fun `a passed test shows nothing`() {
        val passed = SetupCheckReport(isReady = true, lines = listOf(SetupCheckLine("Connected. Found 3 sleep segments.", SetupCheckLineSeverity.INFO)))
        assertEquals(BandCheckStatus.None, beforeBedWith(ConnectionTestState.Done(passed)).bandCheck)
    }

    @Test fun `a failed test shows its first action-needed line as the reason`() {
        val failed = SetupCheckReport(
            isReady = false,
            lines = listOf(
                SetupCheckLine("Notifications are enabled.", SetupCheckLineSeverity.INFO),
                SetupCheckLine("The band did not finish a sync within 20 s. In Gadgetbridge, disconnect and reconnect the band, then test again.", SetupCheckLineSeverity.ACTION_NEEDED),
                SetupCheckLine("Notifications are disabled; the alarm will not show its stop screen.", SetupCheckLineSeverity.ACTION_NEEDED),
            ),
        )
        assertEquals(
            BandCheckStatus.Failed("The band did not finish a sync within 20 s. In Gadgetbridge, disconnect and reconnect the band, then test again."),
            beforeBedWith(ConnectionTestState.Done(failed)).bandCheck,
        )
    }
}
