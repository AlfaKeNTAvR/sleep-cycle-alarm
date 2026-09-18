package com.nikita.sleepcycle.night

// File purpose: what the band is still armed with once the night ends. A DISMISS never disarms the band
// (BandAlarmSingleSlotMode.kt), so the morning report names the last time this app actually SET - which means
// that value must survive every dismissal, including the FINISHED teardown that clears both commitments.

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

private val NOW = Instant.parse("2026-09-18T12:17:00Z")

class LastBandAlarmSetTest {

    @Test
    fun `a tick that sent a SET records that minute`() {
        val decision = decisionWith(listOf(BandAlarmCommand.Set(BAND_ALARM_TITLE, 8, 17)))

        assertEquals(BandAlarmCommitment(BAND_ALARM_TITLE, 8, 17, NOW), lastBandAlarmSetAfter(previous = null, decision, NOW))
    }

    @Test
    fun `a move records the SET, not the DISMISS that preceded it`() {
        val decision = decisionWith(
            listOf(BandAlarmCommand.Dismiss(BAND_ALARM_TITLE), BandAlarmCommand.Set(BAND_ALARM_TITLE, 8, 20))
        )
        val previous = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 17, NOW.minusSeconds(900))

        assertEquals(BandAlarmCommitment(BAND_ALARM_TITLE, 8, 20, NOW), lastBandAlarmSetAfter(previous, decision, NOW))
    }

    @Test
    fun `a teardown tick that only dismisses keeps the last time the band was given`() {
        val previous = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 20, NOW.minusSeconds(900))
        val decision = decisionWith(listOf(BandAlarmCommand.Dismiss(BAND_ALARM_TITLE)))

        assertEquals(previous, lastBandAlarmSetAfter(previous, decision, NOW), "the band stays armed at 08:20 regardless")
    }

    @Test
    fun `a tick that sent nothing changes nothing`() {
        val previous = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 17, NOW.minusSeconds(900))

        assertEquals(previous, lastBandAlarmSetAfter(previous, decisionWith(emptyList()), NOW))
        assertNull(lastBandAlarmSetAfter(previous = null, decisionWith(emptyList()), NOW))
    }

    private fun decisionWith(commands: List<BandAlarmCommand>): BandAlarmDecision = BandAlarmDecision(
        commands = commands,
        requestedBandAlarm = null,
        confirmedBandAlarm = null,
        pendingDismissTitles = emptySet(),
        outcome = BandAlarmOutcome.UNCHANGED
    )
}
