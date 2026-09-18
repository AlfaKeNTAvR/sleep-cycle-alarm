package com.nikita.sleepcycle.night

// File purpose: single-tick behavior of decideBandAlarmCommands - the first request, read-back confirmation,
// a same-tick move, zone handling, a foreign alarm whose title merely contains ours, a confirmed alarm
// vanishing, and the physical send margin (C1: MIN_SEND_MARGIN, 45 s - independent of the engine's own
// minAlarmLead, which the engine itself already enforces). Multi-tick sequences (a moving plan, a silent SET
// failure, a lost dismissal, a FINISHED teardown, blind ticks and orphan-slot adoption, ...) live in
// BandAlarmDecisionSequenceTest.kt.

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
    fun `first request of the night sets our one title into a table with room in it`() {
        val desired = Instant.parse("2026-09-17T08:30:00Z")

        val decision = decideBandAlarmCommands(desired, requested = null, confirmed = null, pendingDismissTitles = emptySet(), slots = listOf(freeSlot(3), freeSlot(4)), now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.REQUESTED, decision.outcome)
        assertEquals(listOf(BandAlarmCommand.Set(BAND_ALARM_TITLE, 8, 30)), decision.commands)
        assertEquals(BandAlarmCommitment(BAND_ALARM_TITLE, 8, 30, NOW), decision.requestedBandAlarm)
        assertNull(decision.confirmedBandAlarm)
        assertTrue(decision.pendingDismissTitles.isEmpty())
    }

    @Test
    fun `a pending request present under its title is confirmed by title alone, using the slot's own hour and minute`() {
        // Core fix (Sol review #1): reconciliation promotes by TITLE, not by matching hour/minute against
        // today's target - the slot's own time is trusted, since that is what the band actually holds.
        val requested = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 15, NOW.minusSeconds(300))
        val slots = listOf(slot(0, enabled = true, hour = 8, minute = 15, title = BAND_ALARM_TITLE))

        val decision = decideBandAlarmCommands(Instant.parse("2026-09-17T08:15:00Z"), requested, confirmed = null, pendingDismissTitles = emptySet(), slots = slots, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.CONFIRMED, decision.outcome)
        assertEquals(BandAlarmCommitment(BAND_ALARM_TITLE, 8, 15, requested.at), decision.confirmedBandAlarm)
        assertNull(decision.requestedBandAlarm)
        assertTrue(decision.commands.isEmpty(), "today's target already matches; confirming does not by itself start a replacement")
    }

    @Test
    fun `a pending request confirmed against a target that already moved on starts the move in the same tick`() {
        val requested = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 15, NOW.minusSeconds(300))
        val slots = listOf(slot(0, enabled = true, hour = 8, minute = 15, title = BAND_ALARM_TITLE), freeSlot(3))

        val decision = decideBandAlarmCommands(Instant.parse("2026-09-17T08:45:00Z"), requested, confirmed = null, pendingDismissTitles = emptySet(), slots = slots, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmCommitment(BAND_ALARM_TITLE, 8, 45, NOW), decision.requestedBandAlarm)
        assertEquals(
            listOf(BandAlarmCommand.Dismiss(BAND_ALARM_TITLE), BandAlarmCommand.Set(BAND_ALARM_TITLE, 8, 45)),
            decision.commands,
            "the move reclaims the very slot it just freed: DISMISS then SET, in that order"
        )
        assertNull(decision.confirmedBandAlarm, "nothing counts as confirmed again until the table shows the new time")
    }

    @Test
    fun `a move never tracks the dismissed title as pending, because the SET reclaims it in the same tick`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 0, NOW.minusSeconds(900))
        val slots = listOf(slot(0, enabled = true, hour = 8, minute = 0, title = BAND_ALARM_TITLE), freeSlot(3))

        val decision = decideBandAlarmCommands(Instant.parse("2026-09-17T08:30:00Z"), requested = null, confirmed = confirmed, pendingDismissTitles = emptySet(), slots = slots, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.MOVED_IN_ONE_SLOT, decision.outcome)
        assertTrue(decision.pendingDismissTitles.isEmpty())
    }

    @Test
    fun `local hour and minute comparison uses the caller's zone, not UTC`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val earlierNow = Instant.parse("2026-09-16T12:00:00Z")
        val desired = Instant.parse("2026-09-16T23:30:00Z") // 2026-09-17T08:30 in Tokyo
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE, hour = 8, minute = 30, at = earlierNow.minusSeconds(900))
        val slots = listOf(slot(0, enabled = true, hour = 8, minute = 30, title = BAND_ALARM_TITLE))

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
        val requested = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 30, NOW.minusSeconds(300))
        val slots = listOf(slot(0, enabled = true, hour = 8, minute = 30, title = "Backup $BAND_ALARM_TITLE"), freeSlot(3), freeSlot(4))

        val decision = decideBandAlarmCommands(instantAt(8, 30), requested, confirmed = null, pendingDismissTitles = emptySet(), slots = slots, now = NOW, zone = ZONE_UTC)

        // Not confirmed: the foreign alarm's title is not equal to ours, only a superstring of it.
        assertEquals(BandAlarmOutcome.MISSING_RESENT, decision.outcome)
        assertEquals(BAND_ALARM_TITLE, decision.requestedBandAlarm?.title)
        assertNull(decision.confirmedBandAlarm)
    }

    @Test
    fun `a foreign alarm titled Backup SCA-A does not block a fresh SET under our own exact title`() {
        // A genuinely free slot alongside the foreign alarm: the question here is whether the superstring
        // title blocks OUR title, not whether the band has room.
        val slots = listOf(slot(0, enabled = true, hour = 7, minute = 0, title = "Backup $BAND_ALARM_TITLE"), freeSlot(3))

        val decision = decideBandAlarmCommands(instantAt(8, 30), requested = null, confirmed = null, pendingDismissTitles = emptySet(), slots = slots, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.REQUESTED, decision.outcome)
        assertEquals(listOf(BandAlarmCommand.Set(BAND_ALARM_TITLE, 8, 30)), decision.commands)
    }

    // ---- Rule: a confirmed alarm that vanished from the table ------------------------------------------------

    @Test
    fun `a confirmed alarm missing from the table is logged and no longer treated as confirmed`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 30, NOW.minusSeconds(900))

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
        assertEquals(listOf(BandAlarmCommand.Set(BAND_ALARM_TITLE, 0, 1)), decision.commands)
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
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE, hour = 8, minute = 30, at = earlierNow.minusSeconds(900))
        val slots = listOf(slot(0, enabled = true, hour = 8, minute = 30, title = BAND_ALARM_TITLE), freeSlot(3))

        val decision = decideBandAlarmCommands(desired, requested = null, confirmed = confirmed, pendingDismissTitles = emptySet(), slots = slots, now = earlierNow, zone = ZoneOffset.UTC)

        assertEquals(BandAlarmOutcome.MOVED_IN_ONE_SLOT, decision.outcome)
        assertEquals(BandAlarmCommand.Set(BAND_ALARM_TITLE, 23, 30), decision.commands.last())
    }

    // ---- Smart-wakeup warning: reported, never fixed, whenever OUR slot still carries the band's own flag ---

    @Test
    fun `a smart-wakeup flag on the slot holding our confirmed title is reported with its position and window`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 30, NOW.minusSeconds(900))
        val slots = listOf(slot(0, enabled = true, hour = 8, minute = 30, title = BAND_ALARM_TITLE, smartWakeup = true, smartWakeupWindowMinutes = 60))

        val decision = decideBandAlarmCommands(instantAt(8, 30), requested = null, confirmed = confirmed, pendingDismissTitles = emptySet(), slots = slots, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmSmartWakeupWarning(BAND_ALARM_TITLE, position = 0, windowMinutes = 60), decision.smartWakeupWarning)
    }

    @Test
    fun `a smart-wakeup flag on a slot under a foreign title is not reported - only our own title is checked`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 30, NOW.minusSeconds(900))
        val slots = listOf(
            slot(0, enabled = true, hour = 8, minute = 30, title = BAND_ALARM_TITLE),
            slot(1, enabled = true, hour = 6, minute = 0, title = "Alarm", smartWakeup = true)
        )

        val decision = decideBandAlarmCommands(instantAt(8, 30), requested = null, confirmed = confirmed, pendingDismissTitles = emptySet(), slots = slots, now = NOW, zone = ZONE_UTC)

        assertNull(decision.smartWakeupWarning)
    }

    @Test
    fun `no smart-wakeup flag on our title reports null`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 30, NOW.minusSeconds(900))
        val slots = listOf(slot(0, enabled = true, hour = 8, minute = 30, title = BAND_ALARM_TITLE))

        val decision = decideBandAlarmCommands(instantAt(8, 30), requested = null, confirmed = confirmed, pendingDismissTitles = emptySet(), slots = slots, now = NOW, zone = ZONE_UTC)

        assertNull(decision.smartWakeupWarning)
    }

    @Test
    fun `blind mode never reports a smart-wakeup warning - there is no table to check`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 30, NOW.minusSeconds(900))

        val decision = decideBandAlarmCommands(instantAt(8, 30), requested = null, confirmed = confirmed, pendingDismissTitles = emptySet(), slots = null, now = NOW, zone = ZONE_UTC)

        assertNull(decision.smartWakeupWarning)
    }

    private fun instantAt(hour: Int, minute: Int): Instant =
        Instant.parse("2026-09-17T%02d:%02d:00Z".format(hour, minute))

    /** A slot Gadgetbridge's own picker would claim (disabled, untitled, not smart): what makes a table count as usable at all. */
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
