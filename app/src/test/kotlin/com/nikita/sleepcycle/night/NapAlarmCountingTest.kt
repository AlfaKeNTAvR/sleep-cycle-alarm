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

    // ---- J1.2 widened H8's exact-equality test to a window (minAlarmLead + 1 min after morningAlarmAt),
    // reasoning that a NAP plan carrying the still-pending morning alarm as its wakeAt (rule 7's
    // awakeNapTarget) might not always fire at EXACTLY morningAlarmAt. J2 must-fix 2 REVERTS this: verified
    // directly (PhoneAlarmReceiver.recordRealAlarmFired) that firedFor is always read from
    // EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI - the instant the alarm was ARMED for - never from when the intent
    // was actually delivered, so ordinary delivery jitter can never move it, and J1.1 already removed the one
    // thing (pullForwardIfTooSoon) that legitimately could. The window instead let a genuine, unrelated
    // mid-night nap (asleepNapTarget, nothing to do with awakeNapTarget's deferral) get misattributed as the
    // wake alarm purely by landing inside it by coincidence - see NightOrchestrator.kt's own doc for the full
    // re-ring trace this caused. These four cases now pin exact equality instead, so nobody re-introduces the
    // window later. -----------------------------------------------------------------------------------------

    @Test
    fun `J2 must-fix 2 - a NAP firing one minute after the latched morning alarm is a genuine nap, not the wake alarm`() {
        // Before J1.1, rule 7's AWAKE branch handing back a still-pending morningAlarmAt could be pulled
        // forward a minute or two by pullForwardIfTooSoon - J1.1 removed that trigger, so a NAP firing that
        // does not land exactly on morningAlarmAt is never the deferred morning alarm any more, only ever a
        // real, independent nap.
        assertFalse(firedAlarmIsWakeAlarm(AlarmMode.NAP, morningAlarmAt.plusSeconds(60), morningAlarmAt))
    }

    @Test
    fun `J2 must-fix 2 - a NAP firing at what used to be the J1_2 tolerance boundary is a genuine nap`() {
        assertFalse(firedAlarmIsWakeAlarm(AlarmMode.NAP, morningAlarmAt.plusSeconds(180), morningAlarmAt))
    }

    @Test
    fun `J2 must-fix 2 - a NAP firing one second after the latched morning alarm is already a genuine nap`() {
        // The boundary is now exact equality itself, not a window - even one second past morningAlarmAt is a
        // nap, never the wake alarm.
        assertFalse(firedAlarmIsWakeAlarm(AlarmMode.NAP, morningAlarmAt.plusSeconds(1), morningAlarmAt))
    }

    @Test
    fun `J2 must-fix 2 - alarmLabelFor rings a NAP armed one minute after the morning alarm as NAP, not MORNING`() {
        assertEquals(AlarmLabel.NAP, alarmLabelFor(AlarmMode.NAP, morningAlarmAt.plusSeconds(60), morningAlarmAt))
    }

    // ---- M3 (owner-reported, 2026-09-21): firedFor == morningAlarmAt is replaced with
    // sameAlarmInstant(firedFor, morningAlarmAt) (com.nikita.sleepcycle.engine.AlarmInstantTolerance.kt), a
    // one-second tolerance on top of M2's own truncation - never a reprise of J1.2's reverted window. The three
    // tests just above (one second, one minute, three minutes past the morning alarm, all still a genuine nap)
    // continue to pin that this is not a window: one second sits exactly on the tolerance's own boundary, which
    // this helper treats as a DIFFERENT instant. This section pins the other side of that boundary. -----------

    @Test
    fun `M3 a NAP-mode firing a fraction of a second after the latched morning alarm is still the wake alarm`() {
        // The representation-mismatch shape the tolerance exists for: a firing 900 ms after morningAlarmAt,
        // well inside the one-second tolerance, must still attribute to the wake alarm - not a genuine nap.
        assertTrue(firedAlarmIsWakeAlarm(AlarmMode.NAP, morningAlarmAt.plusMillis(900), morningAlarmAt))
    }

    @Test
    fun `M3 a NAP-mode firing exactly one second after the latched morning alarm is already a genuine nap`() {
        // Restates the existing J2 must-fix 2 "one second" test above in M3's own terms: the tolerance boundary
        // itself is excluded, so this must still be false, not true.
        assertFalse(firedAlarmIsWakeAlarm(AlarmMode.NAP, morningAlarmAt.plusSeconds(1), morningAlarmAt))
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
