package com.nikita.sleepcycle.night

// File purpose: single-tick behavior of decideBandAlarmCommands - the first request, read-back confirmation
// (by title alone, per Sol review #1), a same-tick replacement start, zone handling, a foreign alarm whose
// title merely contains ours, a confirmed alarm vanishing, and the physical send margin (C1: MIN_SEND_MARGIN,
// 45 s - independent of the engine's own minAlarmLead, which the engine itself already enforces). Multi-tick
// sequences (a moving plan, a silent SET failure, a lost dismissal, a FINISHED teardown, the no-table blind
// alternation and orphan-slot adoption, ...) live in BandAlarmDecisionSequenceTest.kt.

import com.nikita.sleepcycle.bridge.BandAlarmSlot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private val ZONE_UTC: ZoneId = ZoneOffset.UTC
private val NOW = Instant.parse("2026-09-17T00:00:00Z")

class BandAlarmDecisionTest {

    // ---- Basic shape: first request, confirmation, zone handling -----------------------------------------

    @Test
    fun `first request of the night sets under title A from a table with room in it`() {
        val desired = Instant.parse("2026-09-17T08:30:00Z")

        val decision = decideBandAlarmCommands(desired, requested = null, confirmed = null, pendingDismissTitles = emptySet(), slots = listOf(freeSlot(3), freeSlot(4)), now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.REQUESTED, decision.outcome)
        assertEquals(listOf(BandAlarmCommand.Set(BAND_ALARM_TITLE_A, 8, 30)), decision.commands)
        assertEquals(BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 30, NOW), decision.requestedBandAlarm)
        assertNull(decision.confirmedBandAlarm)
        assertTrue(decision.pendingDismissTitles.isEmpty())
    }

    @Test
    fun `a pending request present under its title is confirmed by title alone, using the slot's own hour and minute`() {
        // Core fix (Sol review #1): reconciliation promotes by TITLE, not by matching hour/minute against
        // today's target - the slot's own time is trusted, since that is what the band actually holds.
        val requested = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 15, NOW.minusSeconds(300))
        val slots = listOf(slot(0, enabled = true, hour = 8, minute = 15, title = BAND_ALARM_TITLE_A))

        val decision = decideBandAlarmCommands(Instant.parse("2026-09-17T08:15:00Z"), requested, confirmed = null, pendingDismissTitles = emptySet(), slots = slots, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.CONFIRMED, decision.outcome)
        assertEquals(BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 15, requested.at), decision.confirmedBandAlarm)
        assertNull(decision.requestedBandAlarm)
        assertTrue(decision.commands.isEmpty(), "today's target already matches; confirming does not by itself start a replacement")
    }

    @Test
    fun `a pending request is confirmed even when the target already moved on in the same tick, and a replacement starts under the other title`() {
        val requested = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 15, NOW.minusSeconds(300))
        // A free slot alongside ours: two usable slots, so this tick runs the alternating protocol.
        val slots = listOf(slot(0, enabled = true, hour = 8, minute = 15, title = BAND_ALARM_TITLE_A), freeSlot(3))

        val decision = decideBandAlarmCommands(Instant.parse("2026-09-17T08:45:00Z"), requested, confirmed = null, pendingDismissTitles = emptySet(), slots = slots, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 15, requested.at), decision.confirmedBandAlarm)
        assertEquals(BandAlarmCommitment(BAND_ALARM_TITLE_B, 8, 45, NOW), decision.requestedBandAlarm)
        assertEquals(listOf(BandAlarmCommand.Set(BAND_ALARM_TITLE_B, 8, 45)), decision.commands)
        assertTrue(decision.commands.none { it is BandAlarmCommand.Dismiss }, "A was not confirmed before this tick, so there is nothing to dismiss yet")
    }

    @Test
    fun `confirming a replacement dismisses the old confirmed title in the same tick`() {
        val requested = BandAlarmCommitment(BAND_ALARM_TITLE_B, 8, 30, NOW.minusSeconds(300))
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 0, NOW.minusSeconds(900))
        val slots = listOf(
            slot(0, enabled = true, hour = 8, minute = 0, title = BAND_ALARM_TITLE_A),
            slot(1, enabled = true, hour = 8, minute = 30, title = BAND_ALARM_TITLE_B),
        )

        val decision = decideBandAlarmCommands(Instant.parse("2026-09-17T08:30:00Z"), requested, confirmed, pendingDismissTitles = emptySet(), slots = slots, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.CONFIRMED, decision.outcome)
        assertEquals(listOf(BandAlarmCommand.Dismiss(BAND_ALARM_TITLE_A)), decision.commands)
        assertEquals(requested.title, decision.confirmedBandAlarm?.title)
        assertEquals(setOf(BAND_ALARM_TITLE_A), decision.pendingDismissTitles)
    }

    @Test
    fun `local hour and minute comparison uses the caller's zone, not UTC`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val earlierNow = Instant.parse("2026-09-16T12:00:00Z")
        val desired = Instant.parse("2026-09-16T23:30:00Z") // 2026-09-17T08:30 in Tokyo
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE_A, hour = 8, minute = 30, at = earlierNow.minusSeconds(900))
        val slots = listOf(slot(0, enabled = true, hour = 8, minute = 30, title = BAND_ALARM_TITLE_A))

        val decision = decideBandAlarmCommands(desired, requested = null, confirmed = confirmed, pendingDismissTitles = emptySet(), slots = slots, now = earlierNow, zone = tokyo)

        assertEquals(BandAlarmOutcome.UNCHANGED, decision.outcome)
        assertTrue(decision.commands.isEmpty())
    }

    @Test
    fun `FINISHED with nothing pending or confirmed sends nothing`() {
        val decision = decideBandAlarmCommands(null, requested = null, confirmed = null, pendingDismissTitles = emptySet(), slots = emptyList(), now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.UNCHANGED, decision.outcome)
        assertTrue(decision.commands.isEmpty())
    }

    // ---- A foreign alarm whose title merely contains ours ---------------------------------------------------

    @Test
    fun `a foreign alarm titled Backup SCA-A is never mistaken for our own title by exact-equality reconciliation`() {
        val requested = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 30, NOW.minusSeconds(300))
        // Two free slots alongside the foreign alarm, so this runs the alternating protocol and the outcome
        // is the plain re-send rather than single-slot self-healing.
        val slots = listOf(slot(0, enabled = true, hour = 8, minute = 30, title = "Backup $BAND_ALARM_TITLE_A"), freeSlot(3), freeSlot(4))

        val decision = decideBandAlarmCommands(instantAt(8, 30), requested, confirmed = null, pendingDismissTitles = emptySet(), slots = slots, now = NOW, zone = ZONE_UTC)

        // Not confirmed: the foreign alarm's title is not equal to ours, only a superstring of it.
        assertEquals(BandAlarmOutcome.RESENT, decision.outcome)
        assertEquals(BAND_ALARM_TITLE_A, decision.requestedBandAlarm?.title)
        assertNull(decision.confirmedBandAlarm)
    }

    @Test
    fun `a foreign alarm titled Backup SCA-A does not block a fresh SET under our own exact title`() {
        // A genuinely free slot alongside the foreign alarm: the question here is whether the superstring
        // title blocks OUR title, not whether the band has room.
        val slots = listOf(slot(0, enabled = true, hour = 7, minute = 0, title = "Backup $BAND_ALARM_TITLE_A"), freeSlot(3))

        val decision = decideBandAlarmCommands(instantAt(8, 30), requested = null, confirmed = null, pendingDismissTitles = emptySet(), slots = slots, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.REQUESTED, decision.outcome)
        assertEquals(listOf(BandAlarmCommand.Set(BAND_ALARM_TITLE_A, 8, 30)), decision.commands)
    }

    // ---- Rule: a confirmed alarm that vanished from the table ------------------------------------------------

    @Test
    fun `a confirmed alarm missing from the table is logged and no longer treated as confirmed`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 30, NOW.minusSeconds(900))

        val decision = decideBandAlarmCommands(instantAt(8, 30), requested = null, confirmed = confirmed, pendingDismissTitles = emptySet(), slots = emptyList(), now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.CONFIRMED_LOST, decision.outcome)
        assertNull(decision.confirmedBandAlarm)
    }

    // ---- C1: the physical send margin (45 s, independent of the engine's own minAlarmLead) -----------------

    @Test
    fun `a target whose local minute starts less than MIN_SEND_MARGIN ahead of the refreshed clock is not sent`() {
        // now=00:00:16, next minute boundary 00:01:00 is 44 s away - one second short of the 45 s margin.
        val now = Instant.parse("2026-09-17T00:00:16Z")
        val desired = Instant.parse("2026-09-17T00:01:05Z")

        val decision = decideBandAlarmCommands(desired, requested = null, confirmed = null, pendingDismissTitles = emptySet(), slots = emptyList(), now = now, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.TOO_SOON, decision.outcome)
        assertTrue(decision.commands.isEmpty())
    }

    @Test
    fun `a target whose local minute starts exactly MIN_SEND_MARGIN ahead is sent`() {
        // now=00:00:15, next minute boundary 00:01:00 is exactly 45 s away. slots is a PRESENT table with
        // room in it (not null), so this exercises the table-available path - REQUESTED, not BLIND.
        val now = Instant.parse("2026-09-17T00:00:15Z")
        val desired = Instant.parse("2026-09-17T00:01:05Z")

        val decision = decideBandAlarmCommands(desired, requested = null, confirmed = null, pendingDismissTitles = emptySet(), slots = listOf(freeSlot(3), freeSlot(4)), now = now, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.REQUESTED, decision.outcome)
        assertEquals(listOf(BandAlarmCommand.Set(BAND_ALARM_TITLE_A, 0, 1)), decision.commands)
    }

    @Test
    fun `a desired band alarm already in the past is never sent`() {
        val decision = decideBandAlarmCommands(NOW.minusSeconds(60), requested = null, confirmed = null, pendingDismissTitles = emptySet(), slots = emptyList(), now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.TOO_SOON, decision.outcome)
        assertTrue(decision.commands.isEmpty())
    }

    @Test
    fun `a timezone change that shifts the local hour requests a fresh alarm under the confirmed local time`() {
        val earlierNow = Instant.parse("2026-09-16T12:00:00Z")
        val desired = Instant.parse("2026-09-16T23:30:00Z")
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE_A, hour = 8, minute = 30, at = earlierNow.minusSeconds(900))
        // A free slot alongside ours: two usable slots, so this tick runs the alternating protocol.
        val slots = listOf(slot(0, enabled = true, hour = 8, minute = 30, title = BAND_ALARM_TITLE_A), freeSlot(3))

        val decision = decideBandAlarmCommands(desired, requested = null, confirmed = confirmed, pendingDismissTitles = emptySet(), slots = slots, now = earlierNow, zone = ZoneOffset.UTC)

        assertEquals(BandAlarmOutcome.REQUESTED, decision.outcome)
        assertEquals(BandAlarmCommand.Set(BAND_ALARM_TITLE_B, 23, 30), decision.commands.last())
    }

    // ---- Smart-wakeup warning: reported, never fixed, whenever OUR slot still carries the band's own flag ---

    @Test
    fun `a smart-wakeup flag on the slot holding our confirmed title is reported with its position and window`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 30, NOW.minusSeconds(900))
        val slots = listOf(slot(0, enabled = true, hour = 8, minute = 30, title = BAND_ALARM_TITLE_A, smartWakeup = true, smartWakeupWindowMinutes = 60))

        val decision = decideBandAlarmCommands(instantAt(8, 30), requested = null, confirmed = confirmed, pendingDismissTitles = emptySet(), slots = slots, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmSmartWakeupWarning(BAND_ALARM_TITLE_A, position = 0, windowMinutes = 60), decision.smartWakeupWarning)
    }

    @Test
    fun `a smart-wakeup flag on a slot under a foreign title is not reported - only our own titles are checked`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 30, NOW.minusSeconds(900))
        val slots = listOf(
            slot(0, enabled = true, hour = 8, minute = 30, title = BAND_ALARM_TITLE_A),
            slot(1, enabled = true, hour = 6, minute = 0, title = "Alarm", smartWakeup = true)
        )

        val decision = decideBandAlarmCommands(instantAt(8, 30), requested = null, confirmed = confirmed, pendingDismissTitles = emptySet(), slots = slots, now = NOW, zone = ZONE_UTC)

        assertNull(decision.smartWakeupWarning)
    }

    @Test
    fun `no smart-wakeup flag anywhere among our titles reports null`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 30, NOW.minusSeconds(900))
        val slots = listOf(slot(0, enabled = true, hour = 8, minute = 30, title = BAND_ALARM_TITLE_A))

        val decision = decideBandAlarmCommands(instantAt(8, 30), requested = null, confirmed = confirmed, pendingDismissTitles = emptySet(), slots = slots, now = NOW, zone = ZONE_UTC)

        assertNull(decision.smartWakeupWarning)
    }

    @Test
    fun `blind mode never reports a smart-wakeup warning - there is no table to check`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 30, NOW.minusSeconds(900))

        val decision = decideBandAlarmCommands(instantAt(8, 30), requested = null, confirmed = confirmed, pendingDismissTitles = emptySet(), slots = null, now = NOW, zone = ZONE_UTC)

        assertNull(decision.smartWakeupWarning)
    }

    private fun instantAt(hour: Int, minute: Int): Instant =
        Instant.parse("2026-09-17T%02d:%02d:00Z".format(hour, minute))

    /** A slot Gadgetbridge's own picker would claim (disabled, untitled, not smart): what makes a table count as having room to alternate titles. */
    private fun freeSlot(position: Int): BandAlarmSlot = slot(position, enabled = false, hour = 0, minute = 0, title = null)

    private fun slot(
        position: Int,
        enabled: Boolean,
        hour: Int,
        minute: Int,
        title: String?,
        smartWakeup: Boolean = false,
        smartWakeupWindowMinutes: Int? = null
    ): BandAlarmSlot =
        BandAlarmSlot(position = position, enabled = enabled, hour = hour, minute = minute, title = title, smartWakeup = smartWakeup, repetition = 0, smartWakeupWindowMinutes = smartWakeupWindowMinutes)
}
