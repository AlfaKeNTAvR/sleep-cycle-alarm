package com.nikita.sleepcycle.ui.state

// File purpose: the Debug screen's simulated-data switch against setup gating - simulated band data alone
// relaxes the band/Gadgetbridge setup items (D2: no real sync happens then, so there is nothing for a real
// setup check to have verified), but never the phone-side setup items.

import com.nikita.sleepcycle.night.ClockWarp
import com.nikita.sleepcycle.night.DebugOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class DebugGatingTest {
    private val now = Instant.parse("2026-09-17T00:00:00Z")
    private val simulatedData = DebugOptions(simulatedBandData = true)
    private val simulatedTime = DebugOptions(warp = ClockWarp(60, now, now))

    @Test
    fun `debug options off relaxes nothing`() {
        assertFalse(debugOptionsRelaxSetup(DebugOptions()))
    }

    @Test
    fun `simulated time alone does not relax setup`() {
        assertFalse(debugOptionsRelaxSetup(simulatedTime))
    }

    @Test
    fun `simulated band data relaxes setup`() {
        assertTrue(debugOptionsRelaxSetup(simulatedData))
    }

    @Test
    fun `with setup relaxed, band-address and export-file items are not required`() {
        assertFalse(isSetupItemRequired(SetupItemKind.BAND_ADDRESS, simulatedData))
        assertFalse(isSetupItemRequired(SetupItemKind.EXPORT_FILE, simulatedData))
        assertFalse(isSetupItemRequired(SetupItemKind.GADGETBRIDGE_INSTALLED, simulatedData))
    }

    @Test
    fun `with setup relaxed, the phone-side items are still required`() {
        assertTrue(isSetupItemRequired(SetupItemKind.NOTIFICATIONS, simulatedData))
        assertTrue(isSetupItemRequired(SetupItemKind.FULL_SCREEN_INTENT, simulatedData))
        assertTrue(isSetupItemRequired(SetupItemKind.BATTERY_OPTIMIZATION, simulatedData))
        assertTrue(isSetupItemRequired(SetupItemKind.EXACT_ALARMS, simulatedData))
    }

    @Test
    fun `isSetupComplete ignores a missing band address and export file when setup is relaxed`() {
        val settings = testAppSettings(deviceMac = null)
        assertTrue(isSetupComplete(settings, testPermissionStatus(), gadgetbridgeInstalled = false, debugOptions = simulatedData))
    }

    @Test
    fun `isSetupComplete still requires notifications when setup is relaxed`() {
        val settings = testAppSettings(deviceMac = null)
        val permissions = PermissionStatus(notificationsGranted = false, fullScreenIntentAllowed = true, batteryOptimizationIgnored = true, exactAlarmsAllowed = true)
        assertFalse(isSetupComplete(settings, permissions, gadgetbridgeInstalled = false, debugOptions = simulatedData))
    }

    @Test
    fun `isSetupComplete is unaffected by debug options when not relaxed`() {
        val settings = testAppSettings(deviceMac = null)
        assertFalse(isSetupComplete(settings, testPermissionStatus(), gadgetbridgeInstalled = false, debugOptions = simulatedTime))
    }

    @Test
    fun `startNightGate relaxes the setup-check recency requirement when simulated data is on`() {
        val gate = startNightGate(checklistComplete = true, lastSetupCheckPassedAt = null, now = now, debugOptions = simulatedData)
        assertTrue(gate.enabled)
        assertFalse(gate.blockedBySetupCheck)
    }

    @Test
    fun `startNightGate still requires a recent setup check when only simulated time is on`() {
        val gate = startNightGate(
            checklistComplete = true, lastSetupCheckPassedAt = null, now = now,
            debugOptions = simulatedTime
        )
        assertFalse(gate.enabled)
        assertTrue(gate.blockedBySetupCheck)
    }

    @Test
    fun `startNightGate behaves exactly as before when debug options are the default`() {
        val relaxed = startNightGate(checklistComplete = true, lastSetupCheckPassedAt = null, now = now)
        val explicit = startNightGate(checklistComplete = true, lastSetupCheckPassedAt = null, now = now, debugOptions = DebugOptions())
        assertEquals(relaxed, explicit)
    }
}
