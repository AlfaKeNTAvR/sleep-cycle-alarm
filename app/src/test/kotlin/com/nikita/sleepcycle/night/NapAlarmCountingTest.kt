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

    // ---- J1.2: H8's exact-equality test widened to a window, since a NAP plan carrying the still-pending
    // morning alarm as its wakeAt (rule 7's awakeNapTarget) does not always fire at EXACTLY morningAlarmAt -
    // J1.1's own bug (now fixed) could rearm it a minute or two later, and ordinary AlarmManager delivery
    // jitter can do the same regardless. The window is minAlarmLead (2 min) plus one more minute of slack. ----

    @Test
    fun `J1_2 a NAP firing one minute after the latched morning alarm is still the wake alarm`() {
        // This is the exact shape of the J1.1 bug before it was fixed: rule 7's AWAKE branch hands back the
        // pending morningAlarmAt, pullForwardIfTooSoon used to shift it one minute later, and the alarm fired
        // NAP-mode at morningAlarmAt + 1 min - which the old firedFor == morningAlarmAt check missed entirely.
        assertTrue(firedAlarmIsWakeAlarm(AlarmMode.NAP, morningAlarmAt.plusSeconds(60), morningAlarmAt))
    }

    @Test
    fun `J1_2 a NAP firing exactly at the tolerance boundary is still the wake alarm`() {
        assertTrue(firedAlarmIsWakeAlarm(AlarmMode.NAP, morningAlarmAt.plusSeconds(180), morningAlarmAt))
    }

    @Test
    fun `J1_2 a NAP firing just past the tolerance window is a genuine nap, not the wake alarm`() {
        // A real rule 7 nap is never less than napLength (20 min) past whatever it is measured from, so a
        // firing this close to morningAlarmAt but past the window is never mistaken for one.
        assertFalse(firedAlarmIsWakeAlarm(AlarmMode.NAP, morningAlarmAt.plusSeconds(181), morningAlarmAt))
    }

    @Test
    fun `J1_2 alarmLabelFor rings a NAP armed one minute after the morning alarm as MORNING`() {
        assertEquals(AlarmLabel.MORNING, alarmLabelFor(AlarmMode.NAP, morningAlarmAt.plusSeconds(60), morningAlarmAt))
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
