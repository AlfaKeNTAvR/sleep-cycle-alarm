package com.nikita.sleepcycle.ui.state

// File purpose: every combination of the Debug screen's two data/command switches against setup gating -
// only simulated band data AND dry-run band commands together relax anything, and even then never the
// phone-side setup items.

import com.nikita.sleepcycle.night.BandCommandMode
import com.nikita.sleepcycle.night.DebugOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class DebugGatingTest {
    private val now = Instant.parse("2026-09-17T00:00:00Z")
    private val allDebugRelaxed = DebugOptions(simulatedBandData = true, bandCommandMode = BandCommandMode.DRY_RUN)

    @Test
    fun `debug options off relaxes nothing`() {
        assertFalse(debugOptionsRelaxSetup(DebugOptions()))
    }

    @Test
    fun `simulated band data alone does not relax setup`() {
        assertFalse(debugOptionsRelaxSetup(DebugOptions(simulatedBandData = true, bandCommandMode = BandCommandMode.SEND_TO_BAND)))
    }

    @Test
    fun `dry-run band commands alone does not relax setup`() {
        assertFalse(debugOptionsRelaxSetup(DebugOptions(simulatedBandData = false, bandCommandMode = BandCommandMode.DRY_RUN)))
    }

    @Test
    fun `fast night alone does not relax setup`() {
        assertFalse(debugOptionsRelaxSetup(DebugOptions(fastNight = true)))
    }

    @Test
    fun `simulated band data AND dry run together relax setup`() {
        assertTrue(debugOptionsRelaxSetup(allDebugRelaxed))
    }

    @Test
    fun `with setup relaxed, band-address and export-file items are not required`() {
        assertFalse(isSetupItemRequired(SetupItemKind.BAND_ADDRESS, allDebugRelaxed))
        assertFalse(isSetupItemRequired(SetupItemKind.EXPORT_FILE, allDebugRelaxed))
        assertFalse(isSetupItemRequired(SetupItemKind.GADGETBRIDGE_INSTALLED, allDebugRelaxed))
    }

    @Test
    fun `with setup relaxed, the phone-side items are still required`() {
        assertTrue(isSetupItemRequired(SetupItemKind.NOTIFICATIONS, allDebugRelaxed))
        assertTrue(isSetupItemRequired(SetupItemKind.FULL_SCREEN_INTENT, allDebugRelaxed))
        assertTrue(isSetupItemRequired(SetupItemKind.BATTERY_OPTIMIZATION, allDebugRelaxed))
        assertTrue(isSetupItemRequired(SetupItemKind.EXACT_ALARMS, allDebugRelaxed))
    }

    @Test
    fun `isSetupComplete ignores a missing band address and export file when setup is relaxed`() {
        val settings = testAppSettings(deviceMac = null)
        assertTrue(isSetupComplete(settings, testPermissionStatus(), gadgetbridgeInstalled = false, debugOptions = allDebugRelaxed))
    }

    @Test
    fun `isSetupComplete still requires notifications when setup is relaxed`() {
        val settings = testAppSettings(deviceMac = null)
        val permissions = PermissionStatus(notificationsGranted = false, fullScreenIntentAllowed = true, batteryOptimizationIgnored = true, exactAlarmsAllowed = true)
        assertFalse(isSetupComplete(settings, permissions, gadgetbridgeInstalled = false, debugOptions = allDebugRelaxed))
    }

    @Test
    fun `isSetupComplete is unaffected by debug options when not relaxed`() {
        val settings = testAppSettings(deviceMac = null)
        assertFalse(isSetupComplete(settings, testPermissionStatus(), gadgetbridgeInstalled = false, debugOptions = DebugOptions(fastNight = true)))
    }

    @Test
    fun `startNightGate relaxes the setup-check recency requirement only when both switches are on`() {
        val gate = startNightGate(checklistComplete = true, lastSetupCheckPassedAt = null, now = now, debugOptions = allDebugRelaxed)
        assertTrue(gate.enabled)
        assertFalse(gate.blockedBySetupCheck)
    }

    @Test
    fun `startNightGate still requires a recent setup check when only one debug switch is on`() {
        val gate = startNightGate(
            checklistComplete = true, lastSetupCheckPassedAt = null, now = now,
            debugOptions = DebugOptions(simulatedBandData = true, bandCommandMode = BandCommandMode.SEND_TO_BAND)
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
