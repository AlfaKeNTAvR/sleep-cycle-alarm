package com.nikita.sleepcycle.ui.state

// File purpose: 09/21 review's should-fix 11 - every other test in this package calls
// buildGoingToBedContent/buildNapAsleepContent/buildWokeUpContent directly, so none of them exercise
// buildNightUiState's own job of forwarding NightState.morningAlarmAt into those builders. That wiring is the
// single most important thing this review's must-fix 1 depends on: if buildNightUiState silently passed null
// instead of state.morningAlarmAt, every content-builder test would still pass, and the "no alarm armed" /
// already-rang cases would never actually reach the screen. This file goes through buildNightUiState itself.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.SleepState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildNightUiStateTest {
    @Test fun `forwards the night's latched morning-alarm time into state A's already-rang case`() {
        // H8: the morning alarm has already rung (wakeAt null) while the band still reads ASLEEP.
        // NightState.morningAlarmAt is the only place that rung time is latched - if buildNightUiState failed
        // to forward it, this would silently fall back to the plain MISSING_TIME_LABEL dash instead.
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = null, referenceOnset = "2026-09-17T00:00", cycles = 5)
        val state = testNightState(lastPlan = plan, morningAlarmAt = "2026-09-17T07:00")
        val view = testEngineView(SleepState.ASLEEP)

        val uiState = buildNightUiState(
            nightState = state,
            engineView = view,
            now = instant("2026-09-17T07:05"),
            zone = testZone,
            showingMorningReport = false,
            morningReportEndedAt = null,
            confirmingEndNight = false,
        )

        val content = uiState?.content
        check(content is NightScreenContent.GoingToBedOrAsleep) { "expected state A, got $content" }
        assertTrue(content.alarmAlreadyRang)
        assertEquals("07:00", content.alarmTimeLabel)
    }

    @Test fun `forwards the night's latched morning-alarm time into state C's no-alarm-armed case`() {
        // F5 (must-fix 1 of the 09/21 review): rule 7's sliding nap has nothing left to arm once the wake
        // alarm has fired. Reached here through the same AWAKE/NAP branch buildNightUiState itself picks.
        val plan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = null)
        val state = testNightState(lastPlan = plan, morningAlarmAt = "2026-09-17T07:00")
        val view = testEngineView(SleepState.AWAKE)

        val uiState = buildNightUiState(
            nightState = state,
            engineView = view,
            now = instant("2026-09-17T07:05"),
            zone = testZone,
            showingMorningReport = false,
            morningReportEndedAt = null,
            confirmingEndNight = false,
        )

        val content = uiState?.content
        check(content is NightScreenContent.WokeUp) { "expected state C (WokeUp/napOnly), got $content" }
        assertEquals(null, content.modeLabel)
    }
}
