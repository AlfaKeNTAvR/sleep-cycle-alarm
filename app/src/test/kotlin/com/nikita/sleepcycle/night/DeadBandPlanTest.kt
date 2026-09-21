package com.nikita.sleepcycle.night

// File purpose: J1.5 - the one pure guard runNightTickLocked uses to decide whether to keep the previous
// tick's plan untouched instead of re-planning a dead band's own stale segments. See shouldKeepPreviousPlan's
// own doc in NightOrchestrator.kt for the no-deadline-night-never-rings bug this closes.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.AlarmPlan
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class DeadBandPlanTest {
    private val wakeAt: Instant = Instant.parse("2026-09-17T06:30:00Z")
    private val armedPlan = AlarmPlan(AlarmMode.FULL_CYCLES, wakeAt, 5, Instant.parse("2026-09-16T23:00:00Z"), false, "r")
    private val finishedPlan = AlarmPlan(AlarmMode.FINISHED, null, 0, null, false, "r")

    private fun outcome(syncOk: Boolean) = SyncOutcome(emptyList(), null, syncOk, null, if (syncOk) null else "sync failed")

    @Test
    fun `a failed sync with a real armed alarm keeps the previous plan`() {
        assertTrue(shouldKeepPreviousPlan(outcome(syncOk = false), armedPlan, phoneAlarmFiredFor = null))
    }

    @Test
    fun `a successful sync never keeps the previous plan, even with a real armed alarm`() {
        assertFalse(shouldKeepPreviousPlan(outcome(syncOk = true), armedPlan, phoneAlarmFiredFor = null))
    }

    @Test
    fun `a failed sync with no previous plan at all does not keep anything - there is nothing to keep`() {
        assertFalse(shouldKeepPreviousPlan(outcome(syncOk = false), previousPlan = null, phoneAlarmFiredFor = null))
    }

    @Test
    fun `a failed sync with a FINISHED previous plan (no real alarm) does not keep it - normal re-planning still runs`() {
        assertFalse(shouldKeepPreviousPlan(outcome(syncOk = false), finishedPlan, phoneAlarmFiredFor = null))
    }

    @Test
    fun `a failed sync stops keeping the plan once its own alarm has already fired`() {
        // The protected alarm already rang - PhoneAlarmReceiver fires independently of tick logic, so there
        // is nothing left here to protect by freezing further.
        assertFalse(shouldKeepPreviousPlan(outcome(syncOk = false), armedPlan, phoneAlarmFiredFor = wakeAt))
    }

    @Test
    fun `a failed sync still keeps the plan when a DIFFERENT, earlier alarm already fired`() {
        // phoneAlarmFiredFor belongs to an earlier stretch's own firing, not this plan's own wakeAt - the
        // CURRENT alarm is still pending and still needs protecting.
        assertTrue(
            shouldKeepPreviousPlan(outcome(syncOk = false), armedPlan, phoneAlarmFiredFor = wakeAt.minusSeconds(3600))
        )
    }

    @Test
    fun `stale data (a sync that succeeded but returned old samples) is treated the same as a failed sync`() {
        // resolveSyncOutcome's own stale-data branch also sets syncOk=false - this guard does not need to
        // tell the two apart, only whether the sync counted as ok at all.
        assertTrue(shouldKeepPreviousPlan(outcome(syncOk = false), armedPlan, phoneAlarmFiredFor = null))
    }
}
