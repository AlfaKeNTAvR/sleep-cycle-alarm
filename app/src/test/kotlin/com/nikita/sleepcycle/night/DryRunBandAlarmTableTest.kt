package com.nikita.sleepcycle.night

// File purpose: the dry-run band alarm table (simulatedDryRunBandAlarmSlots) confirms a pending SET, and a
// pending DISMISS, exactly one tick after it was requested - the same read-back-then-confirm flow
// decideBandAlarmCommands runs against a real table runs unchanged against this simulated one.

import com.nikita.sleepcycle.engine.NightSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

class DryRunBandAlarmTableTest {
    private val zone = ZoneOffset.UTC
    private val t0 = Instant.parse("2026-09-17T00:00:00Z")

    private fun state(requested: BandAlarmCommitment?, confirmed: BandAlarmCommitment?, pendingDismiss: Set<String> = emptySet()) = NightState(
        startedAt = t0,
        settings = NightSettings(null, 5, false),
        lastPlan = null,
        requestedBandAlarm = requested,
        confirmedBandAlarm = confirmed,
        lastSyncAt = null,
        lastSyncOk = null,
        lastSegments = emptyList(),
        lastExportFileModifiedAt = null,
        lastSyncFailureCause = null,
        pendingDismissTitles = pendingDismiss,
        debugOptions = DebugOptions(bandCommandMode = BandCommandMode.DRY_RUN)
    )

    @Test
    fun `an empty state has an empty simulated table`() {
        assertTrue(simulatedDryRunBandAlarmSlots(state(requested = null, confirmed = null)).isEmpty())
    }

    @Test
    fun `a pending request appears as an enabled slot under its own title, hour and minute`() {
        val requested = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 30, t0)

        val slots = simulatedDryRunBandAlarmSlots(state(requested = requested, confirmed = null))

        val slot = slots.single()
        assertEquals(BAND_ALARM_TITLE_A, slot.title)
        assertEquals(8, slot.hour)
        assertEquals(30, slot.minute)
        assertTrue(slot.enabled)
    }

    @Test
    fun `a dry-run SET is confirmed exactly one tick after it was requested`() {
        val requested = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 30, t0)
        val nextTickNow = t0.plusSeconds(300)

        // The table as of the end of the tick that sent the SET - built from the still-pending request.
        val slots = simulatedDryRunBandAlarmSlots(state(requested = requested, confirmed = null))
        val decision = decideBandAlarmCommands(
            desiredBandAlarm = Instant.parse("2026-09-17T08:30:00Z"),
            requested = requested, confirmed = null, pendingDismissTitles = emptySet(),
            slots = slots, now = nextTickNow, zone = zone
        )

        assertEquals(BandAlarmOutcome.CONFIRMED, decision.outcome)
        assertEquals(BAND_ALARM_TITLE_A, decision.confirmedBandAlarm?.title)
        assertNull(decision.requestedBandAlarm)
    }

    @Test
    fun `a confirmed alarm with a pending dismissal is left out of the table, confirming the dismiss one tick later`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 0, t0)

        val slots = simulatedDryRunBandAlarmSlots(state(requested = null, confirmed = confirmed, pendingDismiss = setOf(BAND_ALARM_TITLE_A)))

        assertTrue(slots.isEmpty())
    }

    @Test
    fun `a confirmed alarm with no pending dismissal keeps appearing in the table`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 0, t0)

        val slots = simulatedDryRunBandAlarmSlots(state(requested = null, confirmed = confirmed))

        assertEquals(BAND_ALARM_TITLE_A, slots.single().title)
    }
}
