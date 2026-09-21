package com.nikita.sleepcycle.night

// File purpose: F6 - the one pure guard PhoneAlarmReceiver uses at FIRE time (never at arm time -
// armPhoneAlarmIfNeeded no longer touches this bookkeeping at all) to attribute a just-fired real alarm to
// the main wake alarm. Mirrors PhoneAlarmArmingTest's style for shouldArmPhoneAlarm (D1/D2's equivalent
// guard). G8 SUPERSEDES F2: the matching nap-side guard, firedAlarmIsPostWakeNap, is gone - a NAP firing
// always counts toward napAlarmsUsed now, mid-night (rule 7) or post-wake (D5) alike, so there is nothing
// left to test separately from "not the wake alarm" below.

import com.nikita.sleepcycle.alarm.AlarmLabel
import com.nikita.sleepcycle.alarm.alarmLabelFor
import com.nikita.sleepcycle.engine.AlarmMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class NapAlarmCountingTest {
    // ---- firedAlarmIsWakeAlarm (F6/H8) ---------------------------------------------------------------------

    private val morningAlarmAt: Instant = Instant.parse("2026-09-17T07:00:00Z")
    private val napFiredAt: Instant = Instant.parse("2026-09-17T07:30:00Z")

    @Test
    fun `F6 a FULL_CYCLES firing is the wake alarm`() {
        assertTrue(firedAlarmIsWakeAlarm(AlarmMode.FULL_CYCLES, morningAlarmAt, morningAlarmAt))
    }

    @Test
    fun `F6 a DEADLINE_ONLY firing is also the wake alarm`() {
        assertTrue(firedAlarmIsWakeAlarm(AlarmMode.DEADLINE_ONLY, morningAlarmAt, morningAlarmAt))
    }

    @Test
    fun `F6 a NAP firing at its own instant is never the wake alarm, mid-night or post-wake alike`() {
        assertFalse(firedAlarmIsWakeAlarm(AlarmMode.NAP, napFiredAt, morningAlarmAt))
    }

    @Test
    fun `H8 a NAP-mode firing AT the latched morning alarm is the wake alarm, not one of the two naps`() {
        // Since H8 a NAP plan can carry the still-pending morning alarm as its own wakeAt (lying awake in the
        // last minutes before it is rule 7) - the mode alone would spend a nap alarm on the night's real
        // wake-up and leave wakeAlarmFiredAt unrecorded. See WakeAlarm.kt's awakeNapTarget.
        assertTrue(firedAlarmIsWakeAlarm(AlarmMode.NAP, morningAlarmAt, morningAlarmAt))
    }

    @Test
    fun `H8 a NAP firing on a night with no latched morning alarm is still a nap`() {
        assertFalse(firedAlarmIsWakeAlarm(AlarmMode.NAP, napFiredAt, morningAlarmAt = null))
    }

    // ---- alarmLabelFor (W18/H8): the name an alarm rings under follows the same predicate ------------------

    @Test
    fun `H8 an alarm armed at the latched morning alarm rings as the morning alarm, whatever mode armed it`() {
        assertEquals(AlarmLabel.MORNING, alarmLabelFor(AlarmMode.NAP, morningAlarmAt, morningAlarmAt))
        assertEquals(AlarmLabel.MORNING, alarmLabelFor(AlarmMode.FULL_CYCLES, morningAlarmAt, morningAlarmAt))
    }

    @Test
    fun `W18 a nap alarm at its own instant still rings as a nap`() {
        assertEquals(AlarmLabel.NAP, alarmLabelFor(AlarmMode.NAP, napFiredAt, morningAlarmAt))
    }
}
