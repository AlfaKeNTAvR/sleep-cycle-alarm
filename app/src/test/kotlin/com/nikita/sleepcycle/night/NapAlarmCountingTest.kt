package com.nikita.sleepcycle.night

// File purpose: F6 - the one pure guard PhoneAlarmReceiver uses at FIRE time (never at arm time -
// armPhoneAlarmIfNeeded no longer touches this bookkeeping at all) to attribute a just-fired real alarm to
// the main wake alarm. Mirrors PhoneAlarmArmingTest's style for shouldArmPhoneAlarm (D1/D2's equivalent
// guard). G8 SUPERSEDES F2: the matching nap-side guard, firedAlarmIsPostWakeNap, is gone - a NAP firing
// always counts toward napAlarmsUsed now, mid-night (rule 7) or post-wake (D5) alike, so there is nothing
// left to test separately from "not the wake alarm" below.

import com.nikita.sleepcycle.engine.AlarmMode
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NapAlarmCountingTest {
    // ---- firedAlarmIsWakeAlarm (F6) ------------------------------------------------------------------------

    @Test
    fun `F6 a FULL_CYCLES firing is the wake alarm`() {
        assertTrue(firedAlarmIsWakeAlarm(AlarmMode.FULL_CYCLES))
    }

    @Test
    fun `F6 a DEADLINE_ONLY firing is also the wake alarm`() {
        assertTrue(firedAlarmIsWakeAlarm(AlarmMode.DEADLINE_ONLY))
    }

    @Test
    fun `F6 a NAP firing is never the wake alarm, mid-night or post-wake alike`() {
        assertFalse(firedAlarmIsWakeAlarm(AlarmMode.NAP))
    }
}
