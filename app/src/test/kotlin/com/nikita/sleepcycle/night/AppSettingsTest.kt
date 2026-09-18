package com.nikita.sleepcycle.night

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class AppSettingsTest {
    private val settingsWithAPass = AppSettings(
        deviceMac = "AA:BB:CC:DD:EE:FF",
        exportUri = null,
        lastDeadline = null,
        deadlineEnabled = false,
        pickedCycles = 5,
        phoneBackupEnabled = false,
        lastSetupCheckPassedAt = Instant.parse("2026-09-17T00:00:00Z"),
    )

    @Test
    fun `changing the device MAC clears a previously passing setup check`() {
        val updated = withDeviceMac(settingsWithAPass, "11:22:33:44:55:66")

        assertEquals("11:22:33:44:55:66", updated.deviceMac)
        assertNull(updated.lastSetupCheckPassedAt)
    }

    @Test
    fun `re-entering the same device MAC keeps a previously passing setup check`() {
        val updated = withDeviceMac(settingsWithAPass, settingsWithAPass.deviceMac!!)

        assertEquals(settingsWithAPass.lastSetupCheckPassedAt, updated.lastSetupCheckPassedAt)
    }
}
