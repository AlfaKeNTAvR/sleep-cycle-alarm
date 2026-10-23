package com.nikita.sleepcycle.ui.state

// File purpose: PER-150 - the Night screen of a No-alarm night. All night it says there is no alarm and offers
// I'm up; once I'm up is pressed it names the out-of-bed nudge and offers Nap and End night like any night.
// Goes through buildNightUiState, the screen's own seam.

import com.nikita.sleepcycle.alarm.AlarmLabel
import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.SleepState
import com.nikita.sleepcycle.night.NightState
import com.nikita.sleepcycle.night.PendingFollowUp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NightScreenNoAlarmTest {
    private val noAlarmNight = testNightState(
        settings = NightSettings(deadline = null, pickedCycles = 5, noAlarm = true),
        lastPlan = testAlarmPlan(mode = AlarmMode.NO_ALARM, wakeAt = null, referenceOnset = "2026-09-17T00:30"),
    )

    private fun screenAt(now: String, state: NightState, followUp: PendingFollowUp? = null): NightUiState =
        checkNotNull(
            buildNightUiState(
                nightState = state,
                engineView = testEngineView(SleepState.ASLEEP),
                now = instant(now),
                zone = testZone,
                showingMorningReport = false,
                morningReportEndedAt = null,
                confirmingEndNight = false,
                pendingFollowUp = followUp,
            )
        )

    @Test fun `all night the screen says there is no alarm and names no alarm time`() {
        val screen = screenAt("2026-09-17T03:00", noAlarmNight)

        assertEquals(NightScreenContent.NoAlarm, screen.content)
    }

    @Test fun `all night the main button is I'm up, with no End night or Nap beside it`() {
        val screen = screenAt("2026-09-17T11:30", noAlarmNight)

        assertTrue(screen.showImUpButton)
        assertFalse(screen.showEndNightButton)
        assertNull(screen.napButtonMinutes)
    }

    @Test fun `after I'm up the screen names the out-of-bed nudge and offers Nap and End night`() {
        val up = noAlarmNight.copy(wakeAlarmFiredAt = instant("2026-09-17T10:00"))

        val screen = screenAt("2026-09-17T10:01", up, pendingNudge("2026-09-17T10:10"))

        assertFalse(screen.showImUpButton)
        assertTrue(screen.showEndNightButton)
        assertEquals(20, screen.napButtonMinutes)
        val content = screen.content as NightScreenContent.NextAlarm
        assertEquals(AlarmLabel.OUT_OF_BED, content.modeLabel)
        assertEquals("10:10", content.alarmTimeLabel)
    }

    @Test fun `once the nudge chain has ended the screen goes back to saying there is no alarm, with End night`() {
        val up = noAlarmNight.copy(wakeAlarmFiredAt = instant("2026-09-17T10:00"))

        val screen = screenAt("2026-09-17T10:30", up)

        assertEquals(NightScreenContent.NoAlarm, screen.content)
        assertTrue(screen.showEndNightButton)
    }
}
