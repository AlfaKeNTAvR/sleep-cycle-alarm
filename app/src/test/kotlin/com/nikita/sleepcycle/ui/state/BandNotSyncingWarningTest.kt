package com.nikita.sleepcycle.ui.state

// File purpose: P1 - the Night screen's prominent "band not syncing, the alarm is only an estimate" warning.
// On the night of 2026-09-30 every sync timed out and the only sign of it was the small status line; the alarm
// still rang off the fallback estimate. The warning is shown exactly while the last sync did not succeed.
// PER-130: a sync that succeeded but whose heart-rate rows all carried -1 (2026-10-30 to 2026-11-03) shows the
// "not measuring heart rate" warning instead, with its own fix.

import com.nikita.sleepcycle.bridge.StaleReason
import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.SleepState
import com.nikita.sleepcycle.night.NightState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BandNotSyncingWarningTest {
    /** Mirrors the real night at 08:33: alarm 08:38 from a projected onset, the last sync at 08:28. */
    private val estimatedPlan = testAlarmPlan(
        mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T08:38", cycles = 5,
        referenceOnset = "2026-09-17T01:08", onsetIsProjected = true,
    )

    private fun liveWarning(state: NightState): BandDataWarning? = checkNotNull(
        buildNightUiState(
            nightState = state,
            engineView = testEngineView(SleepState.NOT_YET_ASLEEP),
            now = instant("2026-09-17T08:33"),
            zone = testZone,
            showingMorningReport = false,
            morningReportEndedAt = null,
            confirmingEndNight = false,
            pendingFollowUp = null,
        )
    ).bandDataWarning

    @Test fun `P1 a night whose last sync timed out shows the band-not-syncing warning`() {
        val state = testNightState(lastPlan = estimatedPlan, lastSyncAt = "2026-09-17T08:28", lastSyncOk = false, lastSyncFailureCause = "timed out after PT1M")

        assertEquals(BandDataWarning.NOT_SYNCING, liveWarning(state))
    }

    @Test fun `P1 a night whose last sync returned only stale data shows the warning too`() {
        val state = testNightState(lastPlan = estimatedPlan, lastSyncAt = "2026-09-17T08:28", lastSyncOk = false, lastSyncFailureCause = null)
            .copy(lastStaleReason = StaleReason.NO_NEW_SAMPLES)

        assertEquals(BandDataWarning.NOT_SYNCING, liveWarning(state))
    }

    @Test fun `PER-130 a night whose last sync brought only -1 heart-rate rows shows the not-measuring warning`() {
        val state = testNightState(lastPlan = estimatedPlan, lastSyncAt = "2026-09-17T08:28", lastSyncOk = false, lastSyncFailureCause = null)
            .copy(lastStaleReason = StaleReason.NO_HEART_RATE_VALUES)

        assertEquals(BandDataWarning.NOT_MEASURING_HEART_RATE, liveWarning(state))
    }

    @Test fun `P1 a night that is syncing fine shows no warning`() {
        val state = testNightState(lastPlan = estimatedPlan, lastSyncAt = "2026-09-17T08:28", lastSyncOk = true)

        assertNull(liveWarning(state))
    }

    @Test fun `P1 a night whose first sync has not finished yet shows no warning`() {
        val state = testNightState(lastPlan = estimatedPlan, lastSyncAt = null, lastSyncOk = null)

        assertNull(liveWarning(state))
    }

    @Test fun `P1 the morning report never shows the warning`() {
        val uiState = checkNotNull(
            buildNightUiState(
                nightState = null,
                engineView = testEngineView(SleepState.NOT_YET_ASLEEP),
                now = instant("2026-09-17T08:38"),
                zone = testZone,
                showingMorningReport = true,
                morningReportEndedAt = instant("2026-09-17T08:38"),
                confirmingEndNight = false,
                pendingFollowUp = null,
            )
        )

        assertNull(uiState.bandDataWarning)
    }
}
