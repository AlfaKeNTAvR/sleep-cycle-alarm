package com.nikita.sleepcycle.ui.state

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class SetupCheckRecencyTest {
    private val now: Instant = Instant.parse("2026-09-17T22:00:00Z")

    @Test
    fun `never passed is not recent`() {
        assertFalse(setupCheckIsRecent(null, now))
    }

    @Test
    fun `passed 25 hours ago is not recent`() {
        val passedAt = now.minus(Duration.ofHours(25))
        assertFalse(setupCheckIsRecent(passedAt, now))
    }

    @Test
    fun `passed 1 hour ago is recent`() {
        val passedAt = now.minus(Duration.ofHours(1))
        assertTrue(setupCheckIsRecent(passedAt, now))
    }

    @Test
    fun `passed exactly at the validity window boundary is still recent`() {
        val passedAt = now.minus(SETUP_CHECK_VALIDITY_WINDOW)
        assertTrue(setupCheckIsRecent(passedAt, now))
    }

    @Test
    fun `a timestamp in the future (clock skew) is not recent`() {
        val passedAt = now.plus(Duration.ofMinutes(5))
        assertFalse(setupCheckIsRecent(passedAt, now))
    }
}

class StartNightSetupCheckGatingTest {
    private val now: Instant = Instant.parse("2026-09-17T22:00:00Z")

    @Test
    fun `start night is blocked when the checklist is complete but no setup check has ever passed`() {
        val gate = startNightGate(checklistComplete = true, lastSetupCheckPassedAt = null, now = now)

        assertFalse(gate.enabled)
        assertTrue(gate.blockedBySetupCheck)
    }

    @Test
    fun `start night is blocked when the last passing setup check is over 24 hours old`() {
        val gate = startNightGate(checklistComplete = true, lastSetupCheckPassedAt = now.minus(Duration.ofHours(25)), now = now)

        assertFalse(gate.enabled)
        assertTrue(gate.blockedBySetupCheck)
    }

    @Test
    fun `start night is enabled when the checklist is complete and the setup check passed within 24 hours`() {
        val gate = startNightGate(checklistComplete = true, lastSetupCheckPassedAt = now.minus(Duration.ofHours(1)), now = now)

        assertTrue(gate.enabled)
        assertFalse(gate.blockedBySetupCheck)
    }

    @Test
    fun `changing the device MAC after a pass blocks start night again`() {
        val passed = testAppSettings(lastSetupCheckPassedAt = now.minus(Duration.ofHours(1)))
        val afterMacChange = com.nikita.sleepcycle.night.withDeviceMac(passed, "11:22:33:44:55:66")
        val gate = startNightGate(checklistComplete = true, lastSetupCheckPassedAt = afterMacChange.lastSetupCheckPassedAt, now = now)

        assertFalse(gate.enabled)
        assertTrue(gate.blockedBySetupCheck)
    }

    @Test
    fun `an incomplete checklist blocks start night regardless of the setup check`() {
        val gate = startNightGate(checklistComplete = false, lastSetupCheckPassedAt = now.minus(Duration.ofMinutes(5)), now = now)

        assertFalse(gate.enabled)
        assertFalse(gate.blockedBySetupCheck, "an incomplete checklist is its own, different blocker")
    }
}
