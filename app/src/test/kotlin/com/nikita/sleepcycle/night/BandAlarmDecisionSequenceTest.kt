package com.nikita.sleepcycle.night

// File purpose: multi-tick sequence tests for decideBandAlarmCommands, split out of BandAlarmDecisionTest.kt
// to keep each file a manageable size. Each test threads the previous tick's returned
// requested/confirmed/pendingDismissTitles into the next call, the same way NightOrchestrator does -
// because the bug class this function guards against (dropping a confirmed alarm, leaving a stale time armed
// on the band, losing a dismissal) only shows up across ticks.

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

class BandAlarmDecisionSequenceTest {

    // ---- Rule 1: moving pre-sleep plans every tick --------------------------------------------------------

    @Test
    fun `a plan that moves every tick only ever occupies one title, so only one time is ever armed`() {
        var requested: BandAlarmCommitment? = null
        var confirmed: BandAlarmCommitment? = null
        var pendingDismiss: Set<String> = emptySet()

        val tick1 = decideBandAlarmCommands(instantAt(8, 0), requested, confirmed, pendingDismiss, slots = freeSlots(), now = NOW, zone = ZONE_UTC)
        assertEquals(BandAlarmOutcome.REQUESTED, tick1.outcome)
        assertEquals(listOf(BandAlarmCommand.Set(BAND_ALARM_TITLE, 8, 0)), tick1.commands)
        requested = tick1.requestedBandAlarm; confirmed = tick1.confirmedBandAlarm; pendingDismiss = tick1.pendingDismissTitles

        // Tick 2: the export shows our alarm present (the request landed) AND the plan moved to 08:15 in the
        // same tick. The move must go out as DISMISS then SET under the SAME title, so the SET reclaims the
        // slot the DISMISS just freed - the only sequence that overwrites what the band is armed with.
        val tableAt800 = listOf(slot(0, enabled = true, hour = 8, minute = 0, title = BAND_ALARM_TITLE)) + freeSlots()
        val tick2 = decideBandAlarmCommands(instantAt(8, 15), requested, confirmed, pendingDismiss, slots = tableAt800, now = NOW.plusSeconds(300), zone = ZONE_UTC)
        assertEquals(
            listOf(BandAlarmCommand.Dismiss(BAND_ALARM_TITLE), BandAlarmCommand.Set(BAND_ALARM_TITLE, 8, 15)),
            tick2.commands
        )
        assertTrue(tick2.commands.none { it is BandAlarmCommand.Set && it.title != BAND_ALARM_TITLE }, "never a second title")
        requested = tick2.requestedBandAlarm; confirmed = tick2.confirmedBandAlarm; pendingDismiss = tick2.pendingDismissTitles

        // Tick 3: the target moves again (08:20) before the 08:15 SET ever showed up. The table still shows the
        // OLD time under our title, which is not a confirmation - the whole move goes out again at the new time.
        val tick3 = decideBandAlarmCommands(instantAt(8, 20), requested, confirmed, pendingDismiss, slots = tableAt800, now = NOW.plusSeconds(600), zone = ZONE_UTC)
        assertEquals(BandAlarmOutcome.MISSING_RESENT, tick3.outcome)
        assertEquals(
            listOf(BandAlarmCommand.Dismiss(BAND_ALARM_TITLE), BandAlarmCommand.Set(BAND_ALARM_TITLE, 8, 20)),
            tick3.commands
        )
        requested = tick3.requestedBandAlarm; confirmed = tick3.confirmedBandAlarm; pendingDismiss = tick3.pendingDismissTitles

        // Tick 4: the table finally shows 08:20 under our title. Confirmed, nothing more to send, nothing left
        // pending - and at no point did a second slot hold a second time.
        val tableAt820 = listOf(slot(0, enabled = true, hour = 8, minute = 20, title = BAND_ALARM_TITLE)) + freeSlots()
        val tick4 = decideBandAlarmCommands(instantAt(8, 20), requested, confirmed, pendingDismiss, slots = tableAt820, now = NOW.plusSeconds(900), zone = ZONE_UTC)
        assertEquals(BandAlarmOutcome.CONFIRMED, tick4.outcome)
        assertEquals(BandAlarmCommitment(BAND_ALARM_TITLE, 8, 20, tick3.requestedBandAlarm!!.at), tick4.confirmedBandAlarm)
        assertTrue(tick4.commands.isEmpty())
        assertTrue(tick4.pendingDismissTitles.isEmpty())
    }

    // ---- Rule 2: lost dismissal is re-sent until the table shows the title gone ----------------------------

    @Test
    fun `a dismissal that did not take is re-sent until the table shows the title gone`() {
        val tableStillShowingOurs = listOf(slot(0, enabled = true, hour = 8, minute = 0, title = BAND_ALARM_TITLE))

        val tick1 = decideBandAlarmCommands(null, requested = null, confirmed = null, pendingDismissTitles = setOf(BAND_ALARM_TITLE), slots = tableStillShowingOurs, now = NOW, zone = ZONE_UTC)
        assertEquals(listOf(BandAlarmCommand.Dismiss(BAND_ALARM_TITLE)), tick1.commands)
        assertEquals(setOf(BAND_ALARM_TITLE), tick1.pendingDismissTitles, "still present: the dismissal is kept pending, not dropped")

        val tick2 = decideBandAlarmCommands(null, requested = null, confirmed = null, pendingDismissTitles = tick1.pendingDismissTitles, slots = emptyList(), now = NOW.plusSeconds(300), zone = ZONE_UTC)
        assertEquals(BandAlarmOutcome.DISMISSED, tick2.outcome)
        assertTrue(tick2.pendingDismissTitles.isEmpty())
    }

    // ---- What blocks a fresh SET ---------------------------------------------------------------------------

    @Test
    fun `our title present DISABLED blocks a fresh SET - not an orphan, nothing to adopt`() {
        // A disabled, titled slot is not an active alarm (Gadgetbridge only frees a slot for SET when it is
        // both disabled AND titleless - DeviceAlarmReceiver.java), so C3's orphan adoption (which only looks
        // at ENABLED slots) never touches it, and nothing else can claim it either.
        val ourTitleParked = listOf(slot(0, enabled = false, hour = 7, minute = 0, title = BAND_ALARM_TITLE))

        val decision = decideBandAlarmCommands(instantAt(8, 0), requested = null, confirmed = null, pendingDismissTitles = emptySet(), slots = ourTitleParked, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.NO_FREE_SLOT, decision.outcome)
        assertTrue(decision.commands.isEmpty())
        assertNull(decision.requestedBandAlarm)
    }

    @Test
    fun `a title still waiting to be confirmed gone from a prior teardown is never re-claimed in the same tick`() {
        val tableStillShowingOurs = listOf(slot(0, enabled = true, hour = 7, minute = 0, title = BAND_ALARM_TITLE)) + freeSlots()

        val decision = decideBandAlarmCommands(
            instantAt(8, 30), requested = null, confirmed = null,
            pendingDismissTitles = setOf(BAND_ALARM_TITLE), slots = tableStillShowingOurs, now = NOW, zone = ZONE_UTC
        )

        assertTrue(decision.commands.contains(BandAlarmCommand.Dismiss(BAND_ALARM_TITLE)))
        assertNull(decision.requestedBandAlarm, "the title is mid-dismissal; a fresh SET waits for the next table")
        // C5: the expected one-tick lag - DISMISS_PENDING wins the headline outcome (reconciliation runs
        // first), which logs as info (band_alarm_waiting), not error.
        assertEquals(BandAlarmOutcome.DISMISS_PENDING, decision.outcome)
    }

    // ---- Rule 4: no table available -----------------------------------------------------------------------

    @Test
    fun `no table at all on the first tick still sends the SET blind`() {
        val decision = decideBandAlarmCommands(instantAt(8, 30), requested = null, confirmed = null, pendingDismissTitles = emptySet(), slots = null, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.BLIND, decision.outcome)
        assertTrue(decision.blind)
        assertEquals(listOf(BandAlarmCommand.Set(BAND_ALARM_TITLE, 8, 30)), decision.commands)
        assertEquals(BandAlarmCommitment(BAND_ALARM_TITLE, 8, 30, NOW), decision.requestedBandAlarm)
    }

    @Test
    fun `no table with an existing confirmed alarm freezes it rather than re-sending blind`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 30, NOW.minusSeconds(900))

        val decision = decideBandAlarmCommands(instantAt(8, 30), requested = null, confirmed = confirmed, pendingDismissTitles = emptySet(), slots = null, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.BLIND_FROZEN, decision.outcome)
        assertTrue(decision.commands.isEmpty())
        assertEquals(confirmed, decision.confirmedBandAlarm)
    }

    @Test
    fun `no table with an outstanding request for the SAME target sends nothing yet, just counts the tick (C2)`() {
        val requested = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 30, NOW.minusSeconds(120))

        val decision = decideBandAlarmCommands(instantAt(8, 30), requested = requested, confirmed = null, pendingDismissTitles = emptySet(), slots = null, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.BLIND_FROZEN, decision.outcome)
        assertTrue(decision.commands.isEmpty())
        assertEquals(requested.copy(blindResendCount = 1), decision.requestedBandAlarm, "the target did not change, so nothing is (re)sent yet - only the blind-resend tick count advances")
    }

    @Test
    fun `a requested but unconfirmed alarm with the target moving while blind is frozen at its original time, never dismissed`() {
        val requested = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 0, NOW.minusSeconds(60))

        val decision = decideBandAlarmCommands(instantAt(8, 30), requested, confirmed = null, pendingDismissTitles = emptySet(), slots = null, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.BLIND_FROZEN, decision.outcome)
        assertTrue(decision.commands.isEmpty(), "no DISMISS, no SET - the target moved but a blind tick carries no new information to act on")
        assertEquals(BAND_ALARM_TITLE, decision.requestedBandAlarm?.title)
        assertEquals(8, decision.requestedBandAlarm?.hour)
        assertEquals(0, decision.requestedBandAlarm?.minute, "held at the ORIGINAL committed minute, never retargeted while blind")
    }

    @Test
    fun `a pending dismissal is held untouched while blind and retried once the table is back`() {
        val pendingDismiss = setOf(BAND_ALARM_TITLE)

        val blindTick = decideBandAlarmCommands(null, requested = null, confirmed = null, pendingDismissTitles = pendingDismiss, slots = null, now = NOW, zone = ZONE_UTC)
        assertTrue(blindTick.commands.isEmpty(), "never re-sent while blind - there is nothing to verify it against")
        assertEquals(pendingDismiss, blindTick.pendingDismissTitles, "kept pending untouched, not dropped")

        val tableStillShowingOurs = listOf(slot(0, enabled = true, hour = 7, minute = 0, title = BAND_ALARM_TITLE))
        val tableReturns = decideBandAlarmCommands(
            null, blindTick.requestedBandAlarm, blindTick.confirmedBandAlarm, blindTick.pendingDismissTitles,
            slots = tableStillShowingOurs, now = NOW.plusSeconds(300), zone = ZONE_UTC
        )
        assertEquals(listOf(BandAlarmCommand.Dismiss(BAND_ALARM_TITLE)), tableReturns.commands, "retried now that a real table can verify it")
    }

    // ---- FINISHED teardown verified by read-back ----------------------------------------------------------

    @Test
    fun `FINISHED teardown is only reported done once the table confirms our title gone`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 0, NOW.minusSeconds(900))
        val tableShowingOurs = listOf(slot(0, enabled = true, hour = 8, minute = 0, title = BAND_ALARM_TITLE))

        val tick1 = decideBandAlarmCommands(null, requested = null, confirmed = confirmed, pendingDismissTitles = emptySet(), slots = tableShowingOurs, now = NOW, zone = ZONE_UTC)
        assertEquals(listOf(BandAlarmCommand.Dismiss(BAND_ALARM_TITLE)), tick1.commands)
        assertNull(tick1.requestedBandAlarm)
        assertNull(tick1.confirmedBandAlarm)
        assertEquals(setOf(BAND_ALARM_TITLE), tick1.pendingDismissTitles)

        val tick2 = decideBandAlarmCommands(null, null, null, tick1.pendingDismissTitles, tableShowingOurs, NOW.plusSeconds(300), ZONE_UTC)
        assertEquals(listOf(BandAlarmCommand.Dismiss(BAND_ALARM_TITLE)), tick2.commands, "still there, so it is sent again")

        val tick3 = decideBandAlarmCommands(null, null, null, tick2.pendingDismissTitles, emptyList(), NOW.plusSeconds(600), ZONE_UTC)
        assertEquals(BandAlarmOutcome.DISMISSED, tick3.outcome)
        assertTrue(tick3.commands.isEmpty())
        assertTrue(tick3.pendingDismissTitles.isEmpty())
    }

    @Test
    fun `FINISHED while blind still sends the teardown dismissal`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 0, NOW.minusSeconds(900))

        val decision = decideBandAlarmCommands(null, requested = null, confirmed = confirmed, pendingDismissTitles = emptySet(), slots = null, now = NOW, zone = ZONE_UTC)

        assertEquals(listOf(BandAlarmCommand.Dismiss(BAND_ALARM_TITLE)), decision.commands)
        assertNull(decision.requestedBandAlarm)
        assertNull(decision.confirmedBandAlarm)
        assertEquals(setOf(BAND_ALARM_TITLE), decision.pendingDismissTitles)
    }

    // ---- C2: export broken all night - freeze, never chase a moving target, exactly one bounded re-send ----

    @Test
    fun `blind from the first tick all night with the target moving every tick - exactly one SET, one re-send after 4 blind ticks, never a DISMISS, never a third SET`() {
        var requested: BandAlarmCommitment? = null
        var pendingDismiss: Set<String> = emptySet()
        val targets = (0 until 12).map { 8 to (it * 5) }
        var totalSetCommands = 0
        var sawResend = false

        targets.forEachIndexed { index, (hour, minute) ->
            val tick = decideBandAlarmCommands(
                instantAt(hour, minute), requested, confirmed = null, pendingDismissTitles = pendingDismiss,
                slots = null, now = NOW.plusSeconds(index * 300L), zone = ZONE_UTC
            )
            assertTrue(tick.commands.none { it is BandAlarmCommand.Dismiss }, "tick $index: blind mode must never send a DISMISS")
            assertTrue(tick.commands.size <= 1, "tick $index: at most one command per tick")
            totalSetCommands += tick.commands.size
            if (index > 0 && tick.commands.isNotEmpty()) sawResend = true
            requested = tick.requestedBandAlarm
            pendingDismiss = tick.pendingDismissTitles
        }

        assertEquals(2, totalSetCommands, "the initial blind SET plus exactly one bounded re-send over the whole night - never a third SET")
        assertTrue(sawResend, "the bounded re-send did happen once, after 4 blind ticks")
        assertEquals(BAND_ALARM_TITLE, requested?.title, "the frozen commitment always keeps its title")
        assertEquals(8, requested?.hour)
        assertEquals(0, requested?.minute, "frozen at the very first tick's target, never retargeted while blind")
        assertEquals(BLIND_RESEND_EXHAUSTED, requested?.blindResendCount, "the one re-send is spent for good, this blind episode")
    }

    @Test
    fun `a confirmed alarm frozen for hours while the desired target keeps moving sends nothing and never changes`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 0, NOW.minusSeconds(3600))
        val hourlyTargets = (0 until 6).map { 8 to (it * 10) }

        hourlyTargets.forEachIndexed { index, (hour, minute) ->
            val tick = decideBandAlarmCommands(
                instantAt(hour, minute), requested = null, confirmed = confirmed, pendingDismissTitles = emptySet(),
                slots = null, now = NOW.plusSeconds(index * 3600L), zone = ZONE_UTC
            )
            assertTrue(tick.commands.isEmpty(), "tick $index: frozen, nothing sent")
            assertEquals(BandAlarmOutcome.BLIND_FROZEN, tick.outcome)
            assertEquals(confirmed, tick.confirmedBandAlarm, "the confirmed commitment never changes while frozen")
            assertNull(tick.requestedBandAlarm)
        }
    }

    @Test
    fun `app killed and restarted mid blind episode - the persisted re-send counter is honored, no extra re-send`() {
        // Simulates a reload: this commitment already spent its one bounded re-send for this blind episode
        // before the app was killed. blindResendCount is the exact field NightState.kt already persists, so
        // this sentinel survives a restart without any change to how commitments are saved.
        var requested = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 0, NOW.minusSeconds(3600), blindResendCount = BLIND_RESEND_EXHAUSTED)
        val target = instantAt(8, 0)

        repeat(8) { tickIndex ->
            val tick = decideBandAlarmCommands(target, requested, confirmed = null, pendingDismissTitles = emptySet(), slots = null, now = NOW.plusSeconds((tickIndex + 1) * 300L), zone = ZONE_UTC)
            assertTrue(tick.commands.isEmpty(), "tick $tickIndex: the re-send was already spent before the restart, it never repeats")
            assertEquals(BLIND_RESEND_EXHAUSTED, tick.requestedBandAlarm?.blindResendCount, "stays exhausted, never re-arms itself")
            requested = tick.requestedBandAlarm!!
        }
    }

    // ---- Table returns after a blind episode: reconciliation converges ---------------------------------------

    @Test
    fun `table returns showing the blind SET present - confirmed immediately`() {
        val blindRequest = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 0, NOW.minusSeconds(600))
        val tableShowingIt = listOf(slot(0, enabled = true, hour = 8, minute = 0, title = BAND_ALARM_TITLE))

        val decision = decideBandAlarmCommands(instantAt(8, 0), blindRequest, confirmed = null, pendingDismissTitles = emptySet(), slots = tableShowingIt, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.CONFIRMED, decision.outcome)
        assertEquals(BAND_ALARM_TITLE, decision.confirmedBandAlarm?.title)
        assertNull(decision.requestedBandAlarm)
        assertTrue(decision.commands.isEmpty())
    }

    @Test
    fun `table returns showing the blind SET absent - resent now that it can be verified for real`() {
        val blindRequest = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 0, NOW.minusSeconds(600))

        val decision = decideBandAlarmCommands(instantAt(8, 0), blindRequest, confirmed = null, pendingDismissTitles = emptySet(), slots = freeSlots(), now = NOW, zone = ZONE_UTC)

        assertEquals(listOf(BandAlarmCommand.Set(BAND_ALARM_TITLE, 8, 0)), decision.commands)
        assertEquals(BandAlarmOutcome.MISSING_RESENT, decision.outcome)
        assertNull(decision.confirmedBandAlarm)
    }

    @Test
    fun `table returns with two enabled slots under our title sharing the desired minute - still one confirmed alarm`() {
        val blindRequest = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 0, NOW.minusSeconds(600))
        // Both the original blind SET and the bounded blind re-send landed, in two different physical slots,
        // both under our title, both at the same committed time (a blind re-send never retargets) - the exact
        // scenario the bounded-to-one re-send limit accepts as a one-time cost.
        val duplicateSlots = listOf(
            slot(0, enabled = true, hour = 8, minute = 0, title = BAND_ALARM_TITLE),
            slot(2, enabled = true, hour = 8, minute = 0, title = BAND_ALARM_TITLE),
        )

        val decision = decideBandAlarmCommands(instantAt(8, 0), blindRequest, confirmed = null, pendingDismissTitles = emptySet(), slots = duplicateSlots, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.CONFIRMED, decision.outcome)
        assertEquals(BandAlarmCommitment(BAND_ALARM_TITLE, 8, 0, blindRequest.at), decision.confirmedBandAlarm)
        assertTrue(decision.commands.isEmpty(), "already matches the desired time; nothing more to send")
        // A later DISMISS clears both physical slots at once (Gadgetbridge dismisses by title, every matching
        // slot) - this function only ever tracks the logical alarm under a title, never slot counts.
    }

    // ---- C3: an orphaned slot under our title, referenced by no commitment -----------------------------------

    @Test
    fun `an orphan enabled slot matching the desired time is adopted as confirmed`() {
        val orphan = listOf(slot(0, enabled = true, hour = 8, minute = 0, title = BAND_ALARM_TITLE))

        val decision = decideBandAlarmCommands(instantAt(8, 0), requested = null, confirmed = null, pendingDismissTitles = emptySet(), slots = orphan, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.CONFIRMED, decision.outcome)
        assertEquals(BandAlarmCommitment(BAND_ALARM_TITLE, 8, 0, NOW), decision.confirmedBandAlarm)
        assertTrue(decision.commands.isEmpty(), "adopting a slot that already matches needs no command")
    }

    @Test
    fun `an orphan enabled slot NOT matching the desired time is dismissed and re-set to the right time at once`() {
        val orphan = listOf(slot(0, enabled = true, hour = 6, minute = 0, title = BAND_ALARM_TITLE)) + freeSlots()

        val decision = decideBandAlarmCommands(instantAt(8, 0), requested = null, confirmed = null, pendingDismissTitles = emptySet(), slots = orphan, now = NOW, zone = ZONE_UTC)

        // Adoption dismisses the mismatched orphan; with only one title there is nothing else to SET into, so
        // the tick withholds a fresh request until the next table shows the slot really freed.
        assertTrue(decision.commands.contains(BandAlarmCommand.Dismiss(BAND_ALARM_TITLE)))
        assertEquals(setOf(BAND_ALARM_TITLE), decision.pendingDismissTitles)
        assertNull(decision.confirmedBandAlarm)
    }

    @Test
    fun `an orphan slot under a title already referenced by the current commitment is left to the normal path`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 0, NOW.minusSeconds(900))
        val slots = listOf(slot(0, enabled = true, hour = 8, minute = 0, title = BAND_ALARM_TITLE))

        val decision = decideBandAlarmCommands(instantAt(8, 0), requested = null, confirmed = confirmed, pendingDismissTitles = emptySet(), slots = slots, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.UNCHANGED, decision.outcome)
        assertTrue(decision.commands.isEmpty())
    }

    private fun instantAt(hour: Int, minute: Int): Instant =
        Instant.parse("2026-09-17T%02d:%02d:00Z".format(hour, minute))

    private fun slot(position: Int, enabled: Boolean, hour: Int, minute: Int, title: String?): BandAlarmSlot =
        BandAlarmSlot(position = position, enabled = enabled, hour = hour, minute = minute, title = title, smartWakeup = false, repetition = 0)

    /** Slots Gadgetbridge's own picker would claim (disabled, untitled, not smart), so a fresh SET has somewhere to land. */
    private fun freeSlots(): List<BandAlarmSlot> =
        listOf(slot(8, enabled = false, hour = 0, minute = 0, title = null), slot(9, enabled = false, hour = 0, minute = 0, title = null))
}
