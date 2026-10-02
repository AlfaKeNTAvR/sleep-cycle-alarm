package com.nikita.sleepcycle.ui.state

// File purpose: owner spec, 2026-10-02 - the Night screen's "I'm up" button, split out of "I'm up, end night".
// On 2026-10-02 he woke at 08:00, wanted a nap, and had to wait for the 08:19 alarm to ring before Nap was
// offered. "I'm up" stands in for that alarm: offered only while the morning alarm has not rung yet, and once
// pressed the screen offers Nap straight away. Goes through buildNightUiState, the screen's own seam.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.SleepState
import com.nikita.sleepcycle.night.NightState
import com.nikita.sleepcycle.night.PendingFollowUp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NightScreenImUpTest {
    private fun screenAt(now: String, state: NightState, followUp: PendingFollowUp? = null): NightUiState =
        checkNotNull(
            buildNightUiState(
                nightState = state,
                engineView = testEngineView(SleepState.AWAKE),
                now = instant(now),
                zone = testZone,
                showingMorningReport = false,
                morningReportEndedAt = null,
                confirmingEndNight = false,
                pendingFollowUp = followUp,
            )
        )

    @Test fun `in the middle of the night with the 08_19 alarm ahead the screen offers I'm up`() {
        val asleep = testNightState(
            lastPlan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T08:19", cycles = 2),
            morningAlarmAt = "2026-09-17T08:19",
        )

        assertTrue(screenAt("2026-09-17T03:00", asleep).showImUpButton)
    }

    @Test fun `awake at 08_04 in nap mode with the 08_19 alarm still pending, the screen offers I'm up`() {
        // The 2026-10-02 morning exactly: the band saw him awake, the plan switched to NAP, 08:19 still ahead.
        val awake = testNightState(
            lastPlan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = "2026-09-17T08:19"),
            morningAlarmAt = "2026-09-17T08:19",
        )

        assertTrue(screenAt("2026-09-17T08:04", awake).showImUpButton)
    }

    @Test fun `once the morning alarm has rung there is no I'm up button`() {
        val afterAlarm = testNightState(
            lastPlan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = null),
            morningAlarmAt = "2026-09-17T08:19",
        ).copy(wakeAlarmFiredAt = instant("2026-09-17T08:19"))

        assertFalse(screenAt("2026-09-17T08:20", afterAlarm, pendingNudge("2026-09-17T08:30")).showImUpButton)
    }

    @Test fun `after I'm up is pressed at 08_04 the screen offers Nap 20 min instead`() {
        // Pressing I'm up records 08:04 as the morning alarm's own stop and arms the nudge for 08:14.
        val afterImUp = testNightState(
            lastPlan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = null),
            morningAlarmAt = "2026-09-17T08:19",
        ).copy(wakeAlarmFiredAt = instant("2026-09-17T08:04"))

        val screen = screenAt("2026-09-17T08:05", afterImUp, pendingNudge("2026-09-17T08:14"))

        assertFalse(screen.showImUpButton)
        assertEquals(20, screen.napButtonMinutes)
    }

    @Test fun `after I'm up the end button says End night even while the plan is still full cycles`() {
        // Seen on the phone 2026-10-02: the plan stays FULL_CYCLES until the band reports him awake, so the
        // button kept the mid-night "Stop night" wording although he had just said he was up.
        val afterImUp = testNightState(
            lastPlan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = null, cycles = 5),
            morningAlarmAt = "2026-09-17T17:45",
        ).copy(wakeAlarmFiredAt = instant("2026-09-17T10:01"))

        assertEquals(EndNightAction.IM_UP, screenAt("2026-09-17T10:02", afterImUp, pendingNudge("2026-09-17T10:11")).endAction)
    }

    @Test fun `a finished night offers no I'm up`() {
        val finished = testNightState(lastPlan = testAlarmPlan(mode = AlarmMode.FINISHED, reason = "Night finished, deadline was 09:00."))

        assertFalse(screenAt("2026-09-17T09:01", finished).showImUpButton)
    }
}
