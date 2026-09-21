package com.nikita.sleepcycle.night

// File purpose: J1.5 - the one pure guard runNightTickLocked uses to decide whether to keep the previous
// tick's plan untouched instead of re-planning stale segments (a genuinely dead band, or merely an export file
// that has not changed since the last successful sync - see S6/item 5 below). See shouldKeepPreviousPlan's
// own doc in NightOrchestrator.kt for the no-deadline-night-never-rings bug this closes, and for J2 must-fix
// 1's own correction (the freeze must not outlive its own alarm time - see the two tests below the halfway
// marker) of the freeze-forever regression J1.5 itself introduced.
//
// Renamed from DeadBandPlanTest (item 5, 2026-09-21): J3 SHOULD FIX 6 renamed this guard's own framing from
// "dead band" to STALENESS throughout NightOrchestrator.kt (the guard triggers on mere staleness, not only a
// genuine band fault - see shouldKeepPreviousPlan's own S6 doc), but deliberately left this file's own name
// untouched at the time as out of scope for that round's budget (see AUTONOMOUS_DECISIONS_09_21_2026.md,
// "SHOULD FIX 6"). Renamed here since nothing outside this file references the old name by identifier - a
// clean rename, unlike the broader "dead band" terminology rename that round explicitly declined (which would
// have cascaded into NightReplay.kt's own mirror and DeadBandDriftTest.kt, a different, legitimate test of the
// underlying drift bug this guard exists to interrupt, left untouched here too).

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.AlarmPlan
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class StaleSyncPlanTest {
    private val wakeAt: Instant = Instant.parse("2026-09-17T06:30:00Z")
    private val armedPlan = AlarmPlan(AlarmMode.FULL_CYCLES, wakeAt, 5, Instant.parse("2026-09-16T23:00:00Z"), false, "r")
    private val finishedPlan = AlarmPlan(AlarmMode.FINISHED, null, 0, null, false, "r")

    // now, throughout this file (except the J2 must-fix 1 section below), is well before wakeAt - every
    // pre-existing case here is about a STILL-PENDING alarm, never one whose own time has already passed.
    private val now: Instant = wakeAt.minusSeconds(600)

    private fun outcome(syncOk: Boolean) = SyncOutcome(emptyList(), null, syncOk, null, if (syncOk) null else "sync failed")

    @Test
    fun `a failed sync with a real armed alarm still pending keeps the previous plan`() {
        assertTrue(shouldKeepPreviousPlan(outcome(syncOk = false), armedPlan, phoneAlarmFiredFor = null, now = now))
    }

    @Test
    fun `a successful sync never keeps the previous plan, even with a real armed alarm`() {
        assertFalse(shouldKeepPreviousPlan(outcome(syncOk = true), armedPlan, phoneAlarmFiredFor = null, now = now))
    }

    @Test
    fun `a failed sync with no previous plan at all does not keep anything - there is nothing to keep`() {
        assertFalse(shouldKeepPreviousPlan(outcome(syncOk = false), previousPlan = null, phoneAlarmFiredFor = null, now = now))
    }

    @Test
    fun `a failed sync with a FINISHED previous plan (no real alarm) does not keep it - normal re-planning still runs`() {
        assertFalse(shouldKeepPreviousPlan(outcome(syncOk = false), finishedPlan, phoneAlarmFiredFor = null, now = now))
    }

    @Test
    fun `a failed sync stops keeping the plan once its own alarm has already fired`() {
        // The protected alarm already rang - PhoneAlarmReceiver fires independently of tick logic, so there
        // is nothing left here to protect by freezing further.
        assertFalse(shouldKeepPreviousPlan(outcome(syncOk = false), armedPlan, phoneAlarmFiredFor = wakeAt, now = now))
    }

    @Test
    fun `a failed sync still keeps the plan when a DIFFERENT, earlier alarm already fired`() {
        // phoneAlarmFiredFor belongs to an earlier stretch's own firing, not this plan's own wakeAt - the
        // CURRENT alarm is still pending and still needs protecting. Also folds what used to be a duplicate
        // case pinning the exact same assertion under the "stale data" name (S2, reviewer note) - stale data
        // reaches this guard as syncOk=false, identically to an outright failed sync, so there is nothing left
        // for a second copy of this same call to verify.
        assertTrue(
            shouldKeepPreviousPlan(outcome(syncOk = false), armedPlan, phoneAlarmFiredFor = wakeAt.minusSeconds(3600), now = now)
        )
    }

    // ---- J2 must-fix 1 (owner-reported, 2026-09-21): a frozen plan must not outlive its own alarm time - see
    // shouldKeepPreviousPlan's own doc for the sequence this closes (phone battery dies mid-night, boots hours
    // later past wakeAt with the band also dead, and the pre-fix guard froze forever with no now to compare
    // against). These two cases could not be expressed at all before this fix added the now parameter. -------

    @Test
    fun `J2 must-fix 1 - a failed sync no longer keeps a plan whose own alarm time has already passed`() {
        // now is AFTER wakeAt here, unlike every case above - the exact shape of the bug: a real alarm was
        // armed, the phone died before it could fire, and by the time a tick runs again the target is spent.
        // Freezing further would mean the night never re-plans and the alarm never rings, at all, for good.
        assertFalse(shouldKeepPreviousPlan(outcome(syncOk = false), armedPlan, phoneAlarmFiredFor = null, now = wakeAt.plusSeconds(3960)))
    }

    @Test
    fun `J2 must-fix 1 - a failed sync still keeps a plan one instant before its own alarm time`() {
        // The boundary: wakeAt strictly after now still counts as pending, right up to the last nanosecond.
        assertTrue(shouldKeepPreviousPlan(outcome(syncOk = false), armedPlan, phoneAlarmFiredFor = null, now = wakeAt.minusNanos(1)))
    }

    @Test
    fun `J2 must-fix 1 - a failed sync releases the freeze exactly AT the instant the alarm was due`() {
        // now == wakeAt is "come and gone", not merely pending - shouldArmPhoneAlarm treats the same boundary
        // the same way (wakeAt.isAfter(now), not isAfterOrEqual), so this guard must agree with it.
        assertFalse(shouldKeepPreviousPlan(outcome(syncOk = false), armedPlan, phoneAlarmFiredFor = null, now = wakeAt))
    }

    // ---- M3 (owner-reported, 2026-09-21): wakeAt != phoneAlarmFiredFor is now !sameAlarmInstant(wakeAt,
    // phoneAlarmFiredFor) - a one-second tolerance on top of M2's own truncation. -----------------------------

    @Test
    fun `M3 a failed sync stops keeping the plan once its own alarm has fired a fraction of a second off wakeAt`() {
        assertFalse(
            shouldKeepPreviousPlan(outcome(syncOk = false), armedPlan, phoneAlarmFiredFor = wakeAt.plusMillis(900), now = now)
        )
    }

    @Test
    fun `M3 a failed sync still keeps the plan when the fired marker is a full second off wakeAt - a different target`() {
        assertTrue(
            shouldKeepPreviousPlan(outcome(syncOk = false), armedPlan, phoneAlarmFiredFor = wakeAt.plusSeconds(1), now = now)
        )
    }
}
