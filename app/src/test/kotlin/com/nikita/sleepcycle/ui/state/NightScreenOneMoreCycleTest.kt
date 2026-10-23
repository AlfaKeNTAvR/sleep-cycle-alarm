package com.nikita.sleepcycle.ui.state

// File purpose: PER-149 - what the Night screen shows around One more cycle: the button next to Nap and End night
// after a ring, hidden on a deadline night when tap + 90 min would ring after the deadline, and the pending
// cycle named in the N1 layout with I'm up underneath, like the owner's own nap. Through buildNightUiState itself.

import com.nikita.sleepcycle.alarm.AlarmLabel
import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.SleepState
import com.nikita.sleepcycle.night.FollowUpKind
import com.nikita.sleepcycle.night.NightState
import com.nikita.sleepcycle.night.PendingFollowUp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NightScreenOneMoreCycleTest {
    /** The morning alarm rang at 07:00; the engine arms nothing after it (P3), so the plan has no alarm of its own. */
    private val afterMorningAlarm = testNightState(
        lastPlan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = null),
        morningAlarmAt = "2026-09-17T07:00",
    ).copy(phoneAlarmFiredFor = instant("2026-09-17T07:00"))

    private fun withDeadline(deadline: String): NightState = afterMorningAlarm.copy(settings = NightSettings(instant(deadline), 5))

    private fun pendingCycle(text: String) = PendingFollowUp(FollowUpKind.ONE_MORE_CYCLE, instant(text))

    private fun screenAt(now: String, followUp: PendingFollowUp?, state: NightState = afterMorningAlarm): NightUiState =
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

    @Test fun `with the nudge pending after the morning alarm the screen offers Nap, One more cycle and End night`() {
        val screen = screenAt("2026-09-17T07:05", pendingNudge("2026-09-17T07:13"))

        assertEquals(20, screen.napButtonMinutes)
        assertTrue(screen.showOneMoreCycleButton)
        assertTrue(screen.showEndNightButton)
        assertFalse(screen.showImUpButton)
    }

    @Test fun `the moment the morning alarm fires, before the nudge record is read, One more cycle is offered too`() {
        assertTrue(screenAt("2026-09-17T07:00", followUp = null).showOneMoreCycleButton)
    }

    @Test fun `with a 08_35 deadline One more cycle is offered at 07_05, since it ends exactly at the deadline`() {
        assertTrue(screenAt("2026-09-17T07:05", pendingNudge("2026-09-17T07:13"), withDeadline("2026-09-17T08:35")).showOneMoreCycleButton)
    }

    @Test fun `with a 08_30 deadline One more cycle is hidden at 07_05 while Nap is still offered`() {
        val screen = screenAt("2026-09-17T07:05", pendingNudge("2026-09-17T07:13"), withDeadline("2026-09-17T08:30"))

        assertFalse(screen.showOneMoreCycleButton)
        assertEquals(20, screen.napButtonMinutes)
    }

    @Test fun `while One more cycle is pending the screen names it and offers only I'm up`() {
        val screen = screenAt("2026-09-17T07:05", pendingCycle("2026-09-17T08:35"))

        val content = screen.content
        check(content is NightScreenContent.NextAlarm) { "expected the live layout, got $content" }
        assertEquals(AlarmLabel.ONE_MORE_CYCLE, content.modeLabel)
        assertEquals("08:35", content.alarmTimeLabel)
        assertTrue(screen.showImUpButton)
        assertFalse(screen.showEndNightButton)
        assertEquals(null, screen.napButtonMinutes)
        assertFalse(screen.showOneMoreCycleButton)
    }

    @Test fun `before the morning alarm rings One more cycle is not offered`() {
        val beforeMorning = testNightState(lastPlan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T07:00"))

        assertFalse(screenAt("2026-09-17T05:00", followUp = null, state = beforeMorning).showOneMoreCycleButton)
    }

    @Test fun `the morning report never offers One more cycle`() {
        val screen = checkNotNull(
            buildNightUiState(
                nightState = null,
                engineView = testEngineView(SleepState.AWAKE),
                now = instant("2026-09-17T07:35"),
                zone = testZone,
                showingMorningReport = true,
                morningReportEndedAt = instant("2026-09-17T07:35"),
                confirmingEndNight = false,
                pendingFollowUp = pendingNudge("2026-09-17T07:45"),
            )
        )

        assertFalse(screen.showOneMoreCycleButton)
    }
}
