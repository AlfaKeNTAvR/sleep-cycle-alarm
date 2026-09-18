package com.nikita.sleepcycle.ui.state

import com.nikita.sleepcycle.night.BandCommandMode
import com.nikita.sleepcycle.night.DebugOptions
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
        val afterMacChange = com.nikita.sleepcycle.night.withDeviceMac(passed, "AA:BB:CC:DD:EE:FF")
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

/**
 * A one-slot band with nothing on the phone is the one combination that can end the night with no alarm at
 * all: the band alarm is cleared and re-set on every move, a lost SET is never reported, and the bounded
 * re-sends run out. One of the two phone-side alarms must be on before such a night may start.
 */
class SingleSlotPhoneAlarmGatingTest {
    @Test
    fun `one usable slot with no deadline and no phone backup blocks the night`() {
        assertTrue(singleSlotNightNeedsPhoneAlarm(usableBandAlarmSlots = 1, deadlineEnabled = false, phoneBackupEnabled = false))
    }

    @Test
    fun `one usable slot with the deadline on is allowed`() {
        assertFalse(singleSlotNightNeedsPhoneAlarm(usableBandAlarmSlots = 1, deadlineEnabled = true, phoneBackupEnabled = false))
    }

    @Test
    fun `one usable slot with the phone backup on is allowed`() {
        assertFalse(singleSlotNightNeedsPhoneAlarm(usableBandAlarmSlots = 1, deadlineEnabled = false, phoneBackupEnabled = true))
    }

    @Test
    fun `zero usable slots with no phone alarm blocks too - there is even less to fall back on`() {
        assertTrue(singleSlotNightNeedsPhoneAlarm(usableBandAlarmSlots = 0, deadlineEnabled = false, phoneBackupEnabled = false))
    }

    @Test
    fun `two usable slots never require a phone alarm - the band is never left without one`() {
        assertFalse(singleSlotNightNeedsPhoneAlarm(usableBandAlarmSlots = 2, deadlineEnabled = false, phoneBackupEnabled = false))
    }

    @Test
    fun `an unknown slot count never blocks - the stale-setup-check gate already covers that night`() {
        assertFalse(singleSlotNightNeedsPhoneAlarm(usableBandAlarmSlots = null, deadlineEnabled = false, phoneBackupEnabled = false))
    }

    @Test
    fun `a fully simulated night never requires a phone alarm - no band command is ever sent`() {
        val simulated = DebugOptions(simulatedBandData = true, bandCommandMode = BandCommandMode.DRY_RUN)

        assertFalse(singleSlotNightNeedsPhoneAlarm(usableBandAlarmSlots = 1, deadlineEnabled = false, phoneBackupEnabled = false, debugOptions = simulated))
    }

    @Test
    fun `a fast debug night against the real band still requires one`() {
        val fastOnly = DebugOptions(fastNight = true)

        assertTrue(singleSlotNightNeedsPhoneAlarm(usableBandAlarmSlots = 1, deadlineEnabled = false, phoneBackupEnabled = false, debugOptions = fastOnly))
    }
}
