package com.nikita.sleepcycle.ui.state

// File purpose: owner spec, 2026-10-21 (PER-126) - under 1 h after Start night, the Night screen's End night and
// I'm up confirmations say the night will be discarded ("Discard this night? It is under 1 hour, so nothing is
// saved."), because confirming either one discards it instead of ending it. At 1 h or more nothing changes.
// Goes through buildNightUiState, the screen's own seam.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.SleepState
import com.nikita.sleepcycle.night.NightState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NightScreenShortNightTest {
    private val asleep = testNightState(
        startedAt = "2026-09-17T23:00",
        lastPlan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-18T06:30", cycles = 5),
        morningAlarmAt = "2026-09-18T06:30",
    )

    private fun screenAt(now: String, state: NightState = asleep, showingMorningReport: Boolean = false): NightUiState =
        checkNotNull(
            buildNightUiState(
                nightState = state,
                engineView = testEngineView(SleepState.AWAKE),
                now = instant(now),
                zone = testZone,
                showingMorningReport = showingMorningReport,
                morningReportEndedAt = null,
                confirmingEndNight = false,
                pendingFollowUp = null,
            )
        )

    @Test fun `ten minutes after Start night, I'm up discards the night`() {
        val screen = screenAt("2026-09-17T23:10")

        assertTrue(screen.showImUpButton)
        assertTrue(screen.discardsNight)
    }

    @Test fun `a finished night under an hour old discards on End night`() {
        val finished = testNightState(
            startedAt = "2026-09-17T23:00",
            lastPlan = testAlarmPlan(mode = AlarmMode.FINISHED, reason = "Night finished, deadline was 23:30."),
        )

        val screen = screenAt("2026-09-17T23:40", finished)

        assertTrue(screen.showEndNightButton)
        assertTrue(screen.discardsNight)
    }

    @Test fun `an hour after Start night, ending the night is unchanged`() {
        assertFalse(screenAt("2026-09-18T00:00").discardsNight)
    }

    @Test fun `the morning report never offers a discard`() {
        assertFalse(screenAt("2026-09-17T23:10", showingMorningReport = true).discardsNight)
    }
}
