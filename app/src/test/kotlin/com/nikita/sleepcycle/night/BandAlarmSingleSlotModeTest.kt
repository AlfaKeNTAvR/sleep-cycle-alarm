package com.nikita.sleepcycle.night

// File purpose: the single-slot protocol (BandAlarmSingleSlotMode.kt) - the owner's real band has exactly one
// slot this app can use, so a move means DISMISS our own title then SET it again in the same tick. Covers a
// whole sequence of moves, the self-healing re-send after a SET that silently never landed, the per-night
// re-send bound, switching modes mid-night as slots free up and fill again, and the guarantee that the
// two-slot protocol still never dismisses anything before its replacement is confirmed.

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

class BandAlarmSingleSlotModeTest {

    // ---- A whole sequence of moves in single-slot mode -------------------------------------------------------

    @Test
    fun `every move is exactly one DISMISS then one SET under the same title, confirmed on the next tick`() {
        var requested: BandAlarmCommitment? = null
        var confirmed: BandAlarmCommitment? = null

        // Tick 1: nothing of ours on the band yet -> a plain first SET, no dismissal.
        val first = decide(instantAt(8, 0), requested, confirmed, singleSlotTable(ours = null), NOW)
        assertEquals(BandAlarmSlotMode.SINGLE_SLOT, first.slotMode)
        assertEquals(BandAlarmOutcome.REQUESTED, first.outcome)
        assertEquals(listOf(BandAlarmCommand.Set(BAND_ALARM_TITLE_A, 8, 0)), first.commands)
        requested = first.requestedBandAlarm; confirmed = first.confirmedBandAlarm

        // Tick 2: the table shows it, so it is confirmed.
        val landed = decide(instantAt(8, 0), requested, confirmed, singleSlotTable(ours = 8 to 0), NOW.plusSeconds(300))
        assertEquals(BandAlarmOutcome.CONFIRMED, landed.outcome)
        assertTrue(landed.commands.isEmpty())
        requested = landed.requestedBandAlarm; confirmed = landed.confirmedBandAlarm

        // Ticks 3-8: the wake time moves three times, each move dismissing and re-setting the same title.
        var confirmedTime = 8 to 0
        listOf(8 to 20, 7 to 50, 8 to 5).forEachIndexed { index, target ->
            val moveTick = NOW.plusSeconds(600L + index * 600L)
            val move = decide(instantAt(target.first, target.second), requested, confirmed, singleSlotTable(ours = confirmedTime), moveTick)

            assertEquals(BandAlarmSlotMode.SINGLE_SLOT, move.slotMode, "move $index")
            assertEquals(BandAlarmOutcome.MOVED_IN_ONE_SLOT, move.outcome, "move $index")
            assertEquals(
                listOf(
                    BandAlarmCommand.Dismiss(BAND_ALARM_TITLE_A),
                    BandAlarmCommand.Set(BAND_ALARM_TITLE_A, target.first, target.second)
                ),
                move.commands,
                "move $index: exactly one DISMISS then one SET, in that order, under the same title"
            )
            assertNull(move.confirmedBandAlarm, "move $index: nothing is confirmed until the table shows the new time")
            assertTrue(move.pendingDismissTitles.isEmpty(), "move $index: the title is re-set at once, so it is not tracked as a dismissal")
            requested = move.requestedBandAlarm; confirmed = move.confirmedBandAlarm

            val confirmTick = decide(instantAt(target.first, target.second), requested, confirmed, singleSlotTable(ours = target), moveTick.plusSeconds(300))
            assertEquals(BandAlarmOutcome.CONFIRMED, confirmTick.outcome, "move $index: confirmed on the next tick")
            assertEquals(BAND_ALARM_TITLE_A, confirmTick.confirmedBandAlarm?.title, "move $index")
            assertEquals(target.first, confirmTick.confirmedBandAlarm?.hour, "move $index")
            assertEquals(target.second, confirmTick.confirmedBandAlarm?.minute, "move $index")
            assertTrue(confirmTick.commands.isEmpty(), "move $index: nothing more to send once it matches")
            requested = confirmTick.requestedBandAlarm; confirmed = confirmTick.confirmedBandAlarm
            confirmedTime = target
        }
    }

    @Test
    fun `a target that has not moved sends nothing at all`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 0, NOW.minusSeconds(900))

        val decision = decide(instantAt(8, 0), null, confirmed, singleSlotTable(ours = 8 to 0), NOW)

        assertEquals(BandAlarmOutcome.UNCHANGED, decision.outcome)
        assertTrue(decision.commands.isEmpty())
        assertEquals(confirmed, decision.confirmedBandAlarm)
    }

    // ---- Self-healing: a SET that silently never landed ------------------------------------------------------

    @Test
    fun `a SET the table never shows is re-sent on the next tick without waiting for the target to move`() {
        // The move's DISMISS landed but its SET silently did not, so the band now has no alarm of ours at all.
        val requested = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 20, NOW.minusSeconds(300))

        val decision = decide(instantAt(8, 20), requested, confirmed = null, singleSlotTable(ours = null), NOW)

        assertEquals(BandAlarmOutcome.MISSING_RESENT, decision.outcome)
        assertEquals(listOf(BandAlarmCommand.Set(BAND_ALARM_TITLE_A, 8, 20)), decision.commands)
        assertEquals(1, decision.singleSlotResendsUsed, "the re-send is counted against tonight's bound")
        assertTrue(decision.commands.none { it is BandAlarmCommand.Dismiss }, "there is nothing on the band to dismiss")
    }

    @Test
    fun `a move whose DISMISS was lost is re-sent whole and charged against the bound, never mistaken for landed`() {
        // A is confirmed for 08:00 and the target moves to 08:30: DISMISS then SET, both silently lost. The
        // next table still shows A at 08:00 - the OLD alarm, not the requested one. Reading that as "the
        // request landed" was what let this repeat every tick forever without ever spending a re-send.
        var requested: BandAlarmCommitment? = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 30, NOW.minusSeconds(300))
        var resendsUsed = 0

        repeat(3) { index ->
            val tick = decide(
                instantAt(8, 30), requested, confirmed = null, singleSlotTable(ours = 8 to 0),
                NOW.plusSeconds(index * 300L), resendsUsed
            )

            assertEquals(BandAlarmOutcome.MISSING_RESENT, tick.outcome, "tick $index: the old alarm at the old time is not a confirmation")
            assertNull(tick.confirmedBandAlarm, "tick $index: nothing is confirmed while the band holds the wrong time")
            assertEquals(
                listOf(
                    BandAlarmCommand.Dismiss(BAND_ALARM_TITLE_A),
                    BandAlarmCommand.Set(BAND_ALARM_TITLE_A, 8, 30)
                ),
                tick.commands,
                "tick $index: the whole move goes out again, the stale alarm first"
            )
            assertEquals(index + 1, tick.singleSlotResendsUsed, "tick $index: every attempt costs one of tonight's re-sends")
            requested = tick.requestedBandAlarm
            resendsUsed = tick.singleSlotResendsUsed
        }
    }

    @Test
    fun `nothing at all is sent when the table shows no slot this app could use`() {
        // Slot 0 is the band's own smart alarm, the one free slot was taken by a foreign alarm, and our own
        // title is left on a switched-off slot Gadgetbridge's picker will never claim.
        val decision = decide(instantAt(8, 20), requested = null, confirmed = null, zeroSlotTable(), NOW)

        assertEquals(BandAlarmSlotMode.SINGLE_SLOT, decision.slotMode)
        assertEquals(BandAlarmOutcome.NO_FREE_SLOT, decision.outcome)
        assertTrue(decision.commands.isEmpty(), "a SET no slot can take is withheld, not guessed at")
        assertNull(decision.requestedBandAlarm, "nothing was asked for, so nothing is on file")
        assertEquals("error", outcomeLogEvent(decision, NOW)?.type, "the owner is told no slot could take the alarm")
    }

    @Test
    fun `a zero-slot table never spends the night's re-send bound`() {
        val requested = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 20, NOW.minusSeconds(300))

        val decision = decide(instantAt(8, 20), requested, confirmed = null, zeroSlotTable(), NOW, singleSlotResendsUsed = 2)

        assertEquals(BandAlarmOutcome.NO_FREE_SLOT, decision.outcome)
        assertTrue(decision.commands.isEmpty())
        assertEquals(2, decision.singleSlotResendsUsed, "re-sending into a band with no usable slot would burn the bound for nothing")
        assertEquals(requested, decision.requestedBandAlarm, "the pending request is kept, not dropped")
    }

    @Test
    fun `re-sends stop at the per-night bound and say so`() {
        var requested: BandAlarmCommitment? = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 20, NOW.minusSeconds(300))
        var resendsUsed = 0

        repeat(MAX_SINGLE_SLOT_RESENDS_PER_NIGHT) { index ->
            val tick = decide(instantAt(8, 20), requested, null, singleSlotTable(ours = null), NOW.plusSeconds(index * 300L), resendsUsed)
            assertEquals(BandAlarmOutcome.MISSING_RESENT, tick.outcome, "re-send $index")
            assertEquals(1, tick.commands.size, "re-send $index: one SET, nothing else")
            requested = tick.requestedBandAlarm
            resendsUsed = tick.singleSlotResendsUsed
        }
        assertEquals(MAX_SINGLE_SLOT_RESENDS_PER_NIGHT, resendsUsed)

        // Every tick from here on: nothing sent, the count frozen, the request kept on file, and the Night
        // screen's own amber line on (the night log is no longer the only place that says the app gave up).
        repeat(4) { index ->
            val boundHit = decide(
                instantAt(8, 20), requested, null, singleSlotTable(ours = null),
                NOW.plusSeconds(3600L + index * 300L), resendsUsed
            )

            assertEquals(BandAlarmOutcome.RESEND_LIMIT_REACHED, boundHit.outcome, "after the bound, tick $index")
            assertTrue(boundHit.commands.isEmpty(), "tick $index: nothing more is sent once the bound is spent")
            assertEquals(MAX_SINGLE_SLOT_RESENDS_PER_NIGHT, boundHit.singleSlotResendsUsed, "tick $index: the count stops at the bound")
            assertEquals(BAND_ALARM_TITLE_A, boundHit.requestedBandAlarm?.title, "tick $index: the request stays on file")
            assertNull(boundHit.confirmedBandAlarm, "tick $index: still nothing confirmed on the band")

            val logEvent = outcomeLogEvent(boundHit, NOW)
            assertEquals("error", logEvent?.type, "tick $index: the owner is told the band alarm gave up")
            assertTrue(
                logEvent?.fields?.get("cause")?.contains("not re-sending again") == true,
                "tick $index: the cause says the app stopped, without claiming a phone alarm that may not exist"
            )
            requested = boundHit.requestedBandAlarm
            resendsUsed = boundHit.singleSlotResendsUsed
        }
    }

    @Test
    fun `the bound is spent only by re-sends, never by ordinary moves`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 0, NOW.minusSeconds(900))

        val move = decide(instantAt(8, 30), null, confirmed, singleSlotTable(ours = 8 to 0), NOW, singleSlotResendsUsed = 2)

        assertEquals(BandAlarmOutcome.MOVED_IN_ONE_SLOT, move.outcome)
        assertEquals(2, move.singleSlotResendsUsed)
    }

    // ---- Switching modes mid-night as slots free up and fill again -------------------------------------------

    @Test
    fun `freeing a second alarm mid-night switches to the two-slot protocol, and filling it switches back`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 0, NOW.minusSeconds(900))

        val oneSlot = decide(instantAt(8, 30), null, confirmed, singleSlotTable(ours = 8 to 0), NOW)
        assertEquals(BandAlarmSlotMode.SINGLE_SLOT, oneSlot.slotMode)
        assertEquals(BandAlarmCommand.Dismiss(BAND_ALARM_TITLE_A), oneSlot.commands.first())

        // The owner clears another alarm's title, so a second slot is now free.
        val twoSlots = decide(instantAt(8, 30), null, confirmed, singleSlotTable(ours = 8 to 0) + freeSlot(4), NOW.plusSeconds(300))
        assertEquals(BandAlarmSlotMode.ALTERNATING_TITLES, twoSlots.slotMode)
        assertEquals(listOf(BandAlarmCommand.Set(BAND_ALARM_TITLE_B, 8, 30)), twoSlots.commands)
        assertEquals(confirmed, twoSlots.confirmedBandAlarm, "the old alarm stays until the new one is confirmed")

        // The owner sets that alarm again for something else: back to one usable slot.
        val filledAgain = decide(
            instantAt(8, 30), null, confirmed,
            singleSlotTable(ours = 8 to 0) + slot(4, enabled = true, hour = 6, minute = 0, title = "Gym"),
            NOW.plusSeconds(600)
        )
        assertEquals(BandAlarmSlotMode.SINGLE_SLOT, filledAgain.slotMode)
        assertEquals(BandAlarmOutcome.MOVED_IN_ONE_SLOT, filledAgain.outcome)
    }

    @Test
    fun `no table at all is blind mode, never single-slot - a blind tick still never dismisses`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 0, NOW.minusSeconds(900))

        val decision = decideBandAlarmCommands(instantAt(8, 30), null, confirmed, emptySet(), null, NOW, ZONE_UTC)

        assertEquals(BandAlarmSlotMode.BLIND, decision.slotMode)
        assertEquals(BandAlarmOutcome.BLIND_FROZEN, decision.outcome)
        assertTrue(decision.commands.isEmpty())
    }

    // ---- The two-slot protocol is untouched ------------------------------------------------------------------

    @Test
    fun `two-slot mode never dismisses before the replacement is confirmed`() {
        var confirmed: BandAlarmCommitment? = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 0, NOW.minusSeconds(900))
        val tableWithA = listOf(slot(0, enabled = true, hour = 8, minute = 0, title = BAND_ALARM_TITLE_A), freeSlot(3), freeSlot(4))

        val move = decide(instantAt(8, 30), null, confirmed, tableWithA, NOW)
        assertEquals(BandAlarmSlotMode.ALTERNATING_TITLES, move.slotMode)
        assertEquals(listOf(BandAlarmCommand.Set(BAND_ALARM_TITLE_B, 8, 30)), move.commands, "the replacement goes out under the other title, nothing is dismissed")
        assertEquals(confirmed, move.confirmedBandAlarm)

        // B lands: only now is A dismissed.
        val tableWithBoth = tableWithA.drop(1) +
            listOf(
                slot(0, enabled = true, hour = 8, minute = 0, title = BAND_ALARM_TITLE_A),
                slot(1, enabled = true, hour = 8, minute = 30, title = BAND_ALARM_TITLE_B)
            )
        val confirm = decide(instantAt(8, 30), move.requestedBandAlarm, move.confirmedBandAlarm, tableWithBoth, NOW.plusSeconds(300))
        confirmed = confirm.confirmedBandAlarm

        assertEquals(BandAlarmOutcome.CONFIRMED, confirm.outcome)
        assertEquals(BAND_ALARM_TITLE_B, confirmed?.title)
        assertEquals(listOf(BandAlarmCommand.Dismiss(BAND_ALARM_TITLE_A)), confirm.commands)
    }

    private fun decide(
        desired: Instant,
        requested: BandAlarmCommitment?,
        confirmed: BandAlarmCommitment?,
        slots: List<BandAlarmSlot>,
        now: Instant,
        singleSlotResendsUsed: Int = 0
    ): BandAlarmDecision = decideBandAlarmCommands(
        desiredBandAlarm = desired, requested = requested, confirmed = confirmed, pendingDismissTitles = emptySet(),
        slots = slots, now = now, zone = ZONE_UTC, singleSlotResendsUsed = singleSlotResendsUsed
    )

    /**
     * The owner's real band as the export shows it: slot 0 is the band's own smart alarm, parked (titled,
     * switched off) so Gadgetbridge's picker skips it, one foreign alarm is in use, and exactly one slot is
     * this app's to use - either holding our own alarm ([ours] as hour to minute) or genuinely free (disabled
     * AND untitled, the only shape Gadgetbridge's picker will claim). One usable slot either way: a table
     * where that slot is simply absent is a ZERO-slot band, which is a different case with its own test.
     */
    private fun singleSlotTable(ours: Pair<Int, Int>?): List<BandAlarmSlot> = listOf(
        slot(0, enabled = false, hour = 5, minute = 29, title = "Smart", smartWakeup = true),
        slot(1, enabled = true, hour = 7, minute = 0, title = "Work"),
        ours?.let { slot(2, enabled = true, hour = it.first, minute = it.second, title = BAND_ALARM_TITLE_A) } ?: freeSlot(2)
    )

    /** The same band with its one free slot taken by someone else, and one of our titles left behind on a switched-off slot: nothing this app can set an alarm into at all. */
    private fun zeroSlotTable(): List<BandAlarmSlot> = listOf(
        slot(0, enabled = false, hour = 5, minute = 29, title = "Smart", smartWakeup = true),
        slot(1, enabled = true, hour = 7, minute = 0, title = "Work"),
        slot(2, enabled = false, hour = 8, minute = 0, title = BAND_ALARM_TITLE_A)
    )

    private fun freeSlot(position: Int): BandAlarmSlot = slot(position, enabled = false, hour = 0, minute = 0, title = null)

    private fun instantAt(hour: Int, minute: Int): Instant =
        Instant.parse("2026-09-17T%02d:%02d:00Z".format(hour, minute))

    private fun slot(
        position: Int,
        enabled: Boolean,
        hour: Int,
        minute: Int,
        title: String?,
        smartWakeup: Boolean = false
    ): BandAlarmSlot =
        BandAlarmSlot(position = position, enabled = enabled, hour = hour, minute = minute, title = title, smartWakeup = smartWakeup, repetition = 0)
}
