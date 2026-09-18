package com.nikita.sleepcycle.night

// File purpose: item 4 - night 1 had no deadline and phone backup off, so plan.phoneAlarm was empty and
// nothing wakes the owner if the band fails, but Before bed showed nothing about it.

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoPhoneAlarmWarningTest {
    @Test
    fun `no deadline and no phone backup means nothing on the phone will ring`() {
        assertTrue(noPhoneAlarmTonight(deadlineEnabled = false, phoneBackupEnabled = false))
    }

    @Test
    fun `a deadline alone is a phone alarm, no warning`() {
        assertFalse(noPhoneAlarmTonight(deadlineEnabled = true, phoneBackupEnabled = false))
    }

    @Test
    fun `phone backup alone is a phone alarm, no warning`() {
        assertFalse(noPhoneAlarmTonight(deadlineEnabled = false, phoneBackupEnabled = true))
    }

    @Test
    fun `both on is still a phone alarm, no warning`() {
        assertFalse(noPhoneAlarmTonight(deadlineEnabled = true, phoneBackupEnabled = true))
    }
}
