package com.nikita.sleepcycle.night

// File purpose: C5 - DISMISS_PENDING (the expected one-tick lag while alternating titles or tearing down) is
// logged as info (band_alarm_waiting), never error; every other outcome's log type is pinned too.

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class OutcomeLogEventTest {
    private val now = Instant.parse("2026-09-17T08:00:00Z")

    private fun decision(outcome: BandAlarmOutcome, pendingDismissTitles: Set<String> = emptySet()): BandAlarmDecision =
        BandAlarmDecision(commands = emptyList(), requestedBandAlarm = null, confirmedBandAlarm = null, pendingDismissTitles = pendingDismissTitles, outcome = outcome)

    @Test
    fun `DISMISS_PENDING logs as info band_alarm_waiting, not error (C5)`() {
        val event = outcomeLogEvent(decision(BandAlarmOutcome.DISMISS_PENDING, pendingDismissTitles = setOf(BAND_ALARM_TITLE_A)), now)

        assertEquals("band_alarm_waiting", event?.type)
        assertNull(event?.fields?.get("step"), "must not carry the 'error' event shape (step/cause)")
    }

    @Test
    fun `BOTH_SLOTS_OCCUPIED still logs as an actual error`() {
        val event = outcomeLogEvent(decision(BandAlarmOutcome.BOTH_SLOTS_OCCUPIED), now)

        assertEquals("error", event?.type)
    }

    @Test
    fun `REQUESTED, RESENT, BLIND, UNCHANGED and TOO_SOON log nothing extra here`() {
        // C1: TOO_SOON is logged separately, at info with both times, from NightOrchestrator.resolveBandAlarmState -
        // the only call site with both the target and the refreshed clock on hand - never as an error here.
        assertNull(outcomeLogEvent(decision(BandAlarmOutcome.REQUESTED), now))
        assertNull(outcomeLogEvent(decision(BandAlarmOutcome.RESENT), now))
        assertNull(outcomeLogEvent(decision(BandAlarmOutcome.BLIND), now))
        assertNull(outcomeLogEvent(decision(BandAlarmOutcome.UNCHANGED), now))
        assertNull(outcomeLogEvent(decision(BandAlarmOutcome.TOO_SOON), now))
    }
}
