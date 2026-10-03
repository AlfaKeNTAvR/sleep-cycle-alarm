package com.nikita.sleepcycle.ui.state

// File purpose: owner request, 2026-10-02 - on Before bed a simulated night's banner looks like the Night
// screen's simulation block: "SIMULATED" on the left, the clock on the right, without the controls.

import com.nikita.sleepcycle.night.DebugOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private fun beforeBedWith(debugOptions: DebugOptions): BeforeBedUiState = buildUiState(
    appSettings = testAppSettings(),
    nightState = null,
    engineView = null,
    now = instant("2026-09-30T23:07"),
    zone = testZone,
    permissionStatus = testPermissionStatus(),
    gadgetbridgeInstalled = true,
    screen = Screen.BeforeBed,
    connectionTest = ConnectionTestState.Idle,
    nightLogFiles = emptyList(),
    nightLogRatings = emptyMap(),
    confirmingEndNight = false,
    showingMorningReport = false,
    morningReportEndedAt = null,
    pendingFollowUp = null,
    errorMessage = null,
    debugOptions = debugOptions,
).beforeBed

class BeforeBedSimulatedHeaderTest {
    @Test fun `a simulated night shows the clock in the banner before it starts`() {
        assertEquals("23:07", beforeBedWith(DebugOptions(simulatedBandData = true)).simulatedTimeValue)
    }

    @Test fun `a real night shows no clock`() {
        assertNull(beforeBedWith(DebugOptions()).simulatedTimeValue)
    }
}
