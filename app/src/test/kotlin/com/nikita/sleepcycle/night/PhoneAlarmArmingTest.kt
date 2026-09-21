package com.nikita.sleepcycle.night

// File purpose: D2 - the one pure guard shared by armPhoneAlarmIfNeeded (every tick), BootReceiver.handleBoot
// (after a reboot) and startNight (the first arm), so a reboot can never re-arm an already-fired or past
// phone alarm just because the persisted plan still names one.

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class PhoneAlarmArmingTest {
    private val now = Instant.parse("2026-09-17T08:00:00Z")

    @Test
    fun `a null phone alarm is never armed`() {
        assertFalse(shouldArmPhoneAlarm(null, now, phoneAlarmFiredFor = null))
    }

    @Test
    fun `a future phone alarm that never fired is armed`() {
        assertTrue(shouldArmPhoneAlarm(now.plusSeconds(60), now, phoneAlarmFiredFor = null))
    }

    @Test
    fun `a phone alarm exactly at now is not armed - Android fires a past exact alarm immediately`() {
        assertFalse(shouldArmPhoneAlarm(now, now, phoneAlarmFiredFor = null))
    }

    @Test
    fun `a phone alarm already in the past is not armed`() {
        assertFalse(shouldArmPhoneAlarm(now.minusSeconds(60), now, phoneAlarmFiredFor = null))
    }

    @Test
    fun `a future phone alarm that already fired is not re-armed`() {
        val alarm = now.plusSeconds(60)
        assertFalse(shouldArmPhoneAlarm(alarm, now, phoneAlarmFiredFor = alarm))
    }

    @Test
    fun `a future phone alarm different from the one that fired is armed`() {
        val firedFor = now.minusSeconds(600)
        val alarm = now.plusSeconds(60)
        assertTrue(shouldArmPhoneAlarm(alarm, now, phoneAlarmFiredFor = firedFor))
    }

    // J5 (owner-approved revert, 2026-09-21): four tests for J4's own second guard, `shouldRefuseStaleRearm`,
    // stood here and were REMOVED with the guard itself - they existed only for it. See the J5 record above
    // `shouldArmPhoneAlarm` in NightOrchestrator.kt for the two permanent-silence counterexamples that revert
    // rests on. `shouldArmPhoneAlarm` above is once again the ONE pure arming guard, as this file's own header
    // says.
}
