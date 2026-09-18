package com.nikita.sleepcycle.night

// File purpose: multi-tick sequence tests for decideBandAlarmCommands, split out of BandAlarmDecisionTest.kt
// to keep each file a manageable size. Each test threads the previous tick's returned
// requested/confirmed/pendingDismissTitles into the next call, the same way NightOrchestrator does -
// because the bug class this function guards against (dropping a confirmed alarm, leaving the band at zero
// alarms, losing a dismissal) only shows up across ticks.

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
    fun `moving pre-sleep plan every tick still confirms and never leaves the band at zero alarms`() {
        // Tick 1: nothing of ours yet, two genuinely free slots -> the alternating protocol, request A for 08:00.
        var requested: BandAlarmCommitment? = null
        var confirmed: BandAlarmCommitment? = null
        var pendingDismiss: Set<String> = emptySet()

        val tick1 = decideBandAlarmCommands(instantAt(8, 0), requested, confirmed, pendingDismiss, slots = freeSlots(), now = NOW, zone = ZONE_UTC)
        assertEquals(BandAlarmOutcome.REQUESTED, tick1.outcome)
        requested = tick1.requestedBandAlarm; confirmed = tick1.confirmedBandAlarm; pendingDismiss = tick1.pendingDismissTitles

        // Tick 2: the export now shows A present (the previous request landed) AND the plan moved to 08:15
        // in the same tick. Confirmation must still happen, and a replacement for the new target must start
        // without ever dismissing A before its replacement is confirmed.
        val tableWithA = listOf(slot(0, enabled = true, hour = 8, minute = 0, title = BAND_ALARM_TITLE_A)) + freeSlots()
        val tick2 = decideBandAlarmCommands(instantAt(8, 15), requested, confirmed, pendingDismiss, slots = tableWithA, now = NOW.plusSeconds(300), zone = ZONE_UTC)
        assertEquals(BAND_ALARM_TITLE_A, tick2.confirmedBandAlarm?.title, "A must be confirmed this tick even though the target already moved on")
        assertEquals(BAND_ALARM_TITLE_B, tick2.requestedBandAlarm?.title, "the replacement goes out under the other title")
        assertTrue(tick2.commands.none { it is BandAlarmCommand.Dismiss }, "A is not dismissed before B confirms - the band must keep an alarm")
        requested = tick2.requestedBandAlarm; confirmed = tick2.confirmedBandAlarm; pendingDismiss = tick2.pendingDismissTitles

        // Tick 3: the target moves again (08:20) before B ever showed up. The same pending title (B) is
        // re-sent for the new time; A, still confirmed, is left completely alone.
        val tick3 = decideBandAlarmCommands(instantAt(8, 20), requested, confirmed, pendingDismiss, slots = tableWithA, now = NOW.plusSeconds(600), zone = ZONE_UTC)
        assertEquals(listOf(BandAlarmCommand.Set(BAND_ALARM_TITLE_B, 8, 20)), tick3.commands)
        assertEquals(BAND_ALARM_TITLE_A, tick3.confirmedBandAlarm?.title)
        requested = tick3.requestedBandAlarm; confirmed = tick3.confirmedBandAlarm; pendingDismiss = tick3.pendingDismissTitles

        // Tick 4: B finally shows up (at 08:20, matching the last request). A is dismissed now, and only now.
        val tableWithBoth = listOf(
            slot(0, enabled = true, hour = 8, minute = 0, title = BAND_ALARM_TITLE_A),
            slot(1, enabled = true, hour = 8, minute = 20, title = BAND_ALARM_TITLE_B),
        )
        val tick4 = decideBandAlarmCommands(instantAt(8, 20), requested, confirmed, pendingDismiss, slots = tableWithBoth, now = NOW.plusSeconds(900), zone = ZONE_UTC)
        assertEquals(BandAlarmOutcome.CONFIRMED, tick4.outcome)
        assertEquals(listOf(BandAlarmCommand.Dismiss(BAND_ALARM_TITLE_A)), tick4.commands)
        assertEquals(BAND_ALARM_TITLE_B, tick4.confirmedBandAlarm?.title)
        assertEquals(setOf(BAND_ALARM_TITLE_A), tick4.pendingDismissTitles)
    }

    // ---- Rule: silent SET failure keeps re-sending without touching the confirmed alarm --------------------

    @Test
    fun `silent SET failure keeps re-sending the pending request every tick and never dismisses the confirmed alarm`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 0, NOW.minusSeconds(1800))
        var requested: BandAlarmCommitment? = BandAlarmCommitment(BAND_ALARM_TITLE_B, 8, 30, NOW.minusSeconds(600))
        // A free slot is there for B to claim, so this is the alternating protocol; B still never appears,
        // which is exactly the silent SET failure Gadgetbridge never reports.
        val slotsWithoutB = listOf(
            slot(0, enabled = true, hour = 8, minute = 0, title = BAND_ALARM_TITLE_A),
            slot(1, enabled = false, hour = 7, minute = 0, title = "Alarm"),
        ) + freeSlots()

        val tick1 = decideBandAlarmCommands(instantAt(8, 30), requested, confirmed, emptySet(), slotsWithoutB, NOW, ZONE_UTC)
        assertEquals(BandAlarmOutcome.RESENT, tick1.outcome)
        assertEquals(listOf(BandAlarmCommand.Set(BAND_ALARM_TITLE_B, 8, 30)), tick1.commands)
        assertEquals(confirmed, tick1.confirmedBandAlarm)

        val tick2 = decideBandAlarmCommands(instantAt(8, 30), tick1.requestedBandAlarm, tick1.confirmedBandAlarm, tick1.pendingDismissTitles, slotsWithoutB, NOW.plusSeconds(300), ZONE_UTC)
        assertEquals(BandAlarmOutcome.RESENT, tick2.outcome)
        assertEquals(confirmed, tick2.confirmedBandAlarm)
        assertEquals(BAND_ALARM_TITLE_B, tick2.requestedBandAlarm?.title)
    }

    // ---- Rule 2: lost dismissal is re-sent until the table shows the title gone ----------------------------

    @Test
    fun `a dismissal that did not take is re-sent until the table shows the title gone`() {
        val tableStillShowingA = listOf(slot(0, enabled = true, hour = 8, minute = 0, title = BAND_ALARM_TITLE_A))

        val tick1 = decideBandAlarmCommands(null, requested = null, confirmed = null, pendingDismissTitles = setOf(BAND_ALARM_TITLE_A), slots = tableStillShowingA, now = NOW, zone = ZONE_UTC)
        assertEquals(listOf(BandAlarmCommand.Dismiss(BAND_ALARM_TITLE_A)), tick1.commands)
        assertEquals(setOf(BAND_ALARM_TITLE_A), tick1.pendingDismissTitles, "still present: the dismissal is kept pending, not dropped")

        val tick2 = decideBandAlarmCommands(null, requested = null, confirmed = null, pendingDismissTitles = tick1.pendingDismissTitles, slots = emptyList(), now = NOW.plusSeconds(300), zone = ZONE_UTC)
        assertEquals(BandAlarmOutcome.DISMISSED, tick2.outcome)
        assertTrue(tick2.pendingDismissTitles.isEmpty())
    }

    // ---- Rule 2: both slots occupied blocks a fresh SET -----------------------------------------------------

    @Test
    fun `both our titles ENABLED and unreferenced are orphans (C3) - adopted, not blocked forever`() {
        // Since C3, an enabled slot under one of our own titles that no commitment references is an orphan,
        // reclaimed rather than left to permanently block a fresh SET (the pre-C3 behavior this test used to
        // cover). Neither matches the desired time, so both are dismissed and tracked pending; a fresh SET is
        // still withheld this same tick (the table read this tick still shows both titles occupied - a future
        // tick's table will confirm they are really gone before a SET is attempted, same "never guess" rule
        // as everywhere else in this function).
        val bothPresent = listOf(
            slot(0, enabled = true, hour = 7, minute = 0, title = BAND_ALARM_TITLE_A),
            slot(1, enabled = true, hour = 7, minute = 30, title = BAND_ALARM_TITLE_B),
        )

        val decision = decideBandAlarmCommands(instantAt(8, 0), requested = null, confirmed = null, pendingDismissTitles = emptySet(), slots = bothPresent, now = NOW, zone = ZONE_UTC)

        assertEquals(
            setOf(BandAlarmCommand.Dismiss(BAND_ALARM_TITLE_A), BandAlarmCommand.Dismiss(BAND_ALARM_TITLE_B)),
            decision.commands.toSet()
        )
        assertEquals(setOf(BAND_ALARM_TITLE_A, BAND_ALARM_TITLE_B), decision.pendingDismissTitles)
        assertNull(decision.requestedBandAlarm)
    }

    @Test
    fun `both our titles present but DISABLED still blocks a fresh SET - not an orphan, nothing to adopt`() {
        // A disabled, titled slot is not an active alarm (Gadgetbridge only frees a slot for SET when it is
        // both disabled AND titleless - DeviceAlarmReceiver.java), so C3's orphan adoption (which only looks
        // at ENABLED slots) never touches it, and the pre-C3 "leave it alone" guard still applies.
        val bothPresentDisabled = listOf(
            slot(0, enabled = false, hour = 7, minute = 0, title = BAND_ALARM_TITLE_A),
            slot(1, enabled = false, hour = 7, minute = 30, title = BAND_ALARM_TITLE_B),
        )

        val decision = decideBandAlarmCommands(instantAt(8, 0), requested = null, confirmed = null, pendingDismissTitles = emptySet(), slots = bothPresentDisabled, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.NO_FREE_SLOT, decision.outcome)
        assertTrue(decision.commands.isEmpty())
        assertNull(decision.requestedBandAlarm)
    }

    @Test
    fun `a replacement target does not steal a title still waiting to be confirmed gone from a prior teardown`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 0, NOW.minusSeconds(900))
        // B was dismissed from an earlier round and has not yet been confirmed gone.
        val tableShowingBoth = listOf(
            slot(0, enabled = true, hour = 8, minute = 0, title = BAND_ALARM_TITLE_A),
            slot(1, enabled = true, hour = 7, minute = 0, title = BAND_ALARM_TITLE_B),
        )

        val decision = decideBandAlarmCommands(
            instantAt(8, 30), requested = null, confirmed = confirmed,
            pendingDismissTitles = setOf(BAND_ALARM_TITLE_B), slots = tableShowingBoth, now = NOW, zone = ZONE_UTC
        )

        // B is still present (step 1 resends its dismissal), so replacing A must wait rather than race it.
        assertTrue(decision.commands.contains(BandAlarmCommand.Dismiss(BAND_ALARM_TITLE_B)))
        assertNull(decision.requestedBandAlarm)
        assertEquals(confirmed, decision.confirmedBandAlarm)
        // C5: this is the expected one-tick lag while alternating titles - DISMISS_PENDING always wins the
        // headline outcome here (reconciliation runs first), which logs as info (band_alarm_waiting), not error.
        assertEquals(BandAlarmOutcome.DISMISS_PENDING, decision.outcome)
    }

    // ---- Rule 4: no table available -----------------------------------------------------------------------

    @Test
    fun `no table at all on the first tick still sends the SET blind`() {
        val decision = decideBandAlarmCommands(instantAt(8, 30), requested = null, confirmed = null, pendingDismissTitles = emptySet(), slots = null, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.BLIND, decision.outcome)
        assertEquals(listOf(BandAlarmCommand.Set(BAND_ALARM_TITLE_A, 8, 30)), decision.commands)
        assertEquals(BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 30, NOW), decision.requestedBandAlarm)
    }

    @Test
    fun `no table with an existing confirmed alarm freezes it rather than re-sending blind`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 30, NOW.minusSeconds(900))

        val decision = decideBandAlarmCommands(instantAt(8, 30), requested = null, confirmed = confirmed, pendingDismissTitles = emptySet(), slots = null, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.BLIND_FROZEN, decision.outcome)
        assertTrue(decision.commands.isEmpty())
        assertEquals(confirmed, decision.confirmedBandAlarm)
    }

    @Test
    fun `no table with an outstanding request for the SAME target sends nothing yet, just counts the tick (C2)`() {
        val requested = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 30, NOW.minusSeconds(120))

        val decision = decideBandAlarmCommands(instantAt(8, 30), requested = requested, confirmed = null, pendingDismissTitles = emptySet(), slots = null, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.BLIND_FROZEN, decision.outcome)
        assertTrue(decision.commands.isEmpty())
        assertEquals(requested.copy(blindResendCount = 1), decision.requestedBandAlarm, "the target did not change, so nothing is (re)sent yet - only the blind-resend tick count advances")
    }

    @Test
    fun `a requested but unconfirmed alarm with the target moving while blind is frozen at its original time, never dismissed`() {
        val requested = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 0, NOW.minusSeconds(60))

        val decision = decideBandAlarmCommands(instantAt(8, 30), requested, confirmed = null, pendingDismissTitles = emptySet(), slots = null, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.BLIND_FROZEN, decision.outcome)
        assertTrue(decision.commands.isEmpty(), "no DISMISS, no SET - the target moved but a blind tick carries no new information to act on")
        assertEquals(BAND_ALARM_TITLE_A, decision.requestedBandAlarm?.title)
        assertEquals(8, decision.requestedBandAlarm?.hour)
        assertEquals(0, decision.requestedBandAlarm?.minute, "held at the ORIGINAL committed minute, never retargeted while blind")
    }

    @Test
    fun `pending dismissals are held untouched while blind and retried once the table is back`() {
        // B is already confirmed and matches the desired target exactly, so the blind tick has nothing else to
        // do (no fresh SET, no freeze-worthy requested commitment) - isolating what this test is actually
        // about: A's pending dismissal must sit untouched through the blind tick, not be re-sent or dropped.
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE_B, 8, 30, NOW.minusSeconds(900))
        val pendingDismiss = setOf(BAND_ALARM_TITLE_A)

        val blindTick = decideBandAlarmCommands(instantAt(8, 30), requested = null, confirmed = confirmed, pendingDismissTitles = pendingDismiss, slots = null, now = NOW, zone = ZONE_UTC)
        assertTrue(blindTick.commands.isEmpty(), "never dismissed, never re-sent, while blind")
        assertEquals(pendingDismiss, blindTick.pendingDismissTitles, "kept pending untouched, not dropped")

        val tableStillShowingA = listOf(
            slot(0, enabled = true, hour = 7, minute = 0, title = BAND_ALARM_TITLE_A),
            slot(1, enabled = true, hour = 8, minute = 30, title = BAND_ALARM_TITLE_B),
        )
        val tableReturns = decideBandAlarmCommands(
            instantAt(8, 30), blindTick.requestedBandAlarm, blindTick.confirmedBandAlarm, blindTick.pendingDismissTitles,
            slots = tableStillShowingA, now = NOW.plusSeconds(300), zone = ZONE_UTC
        )
        assertEquals(listOf(BandAlarmCommand.Dismiss(BAND_ALARM_TITLE_A)), tableReturns.commands, "retried now that a real table can verify it")
    }

    @Test
    fun `table returns after being absent confirms the blind request`() {
        val blindRequest = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 30, NOW.minusSeconds(600))
        val tableNowShowingIt = listOf(slot(0, enabled = true, hour = 8, minute = 30, title = BAND_ALARM_TITLE_A))

        val decision = decideBandAlarmCommands(instantAt(8, 30), requested = blindRequest, confirmed = null, pendingDismissTitles = emptySet(), slots = tableNowShowingIt, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.CONFIRMED, decision.outcome)
        assertEquals(BAND_ALARM_TITLE_A, decision.confirmedBandAlarm?.title)
        assertNull(decision.requestedBandAlarm)
    }

    // ---- FINISHED teardown verified by read-back ----------------------------------------------------------

    @Test
    fun `FINISHED teardown is only reported done once the table confirms both titles gone`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 0, NOW.minusSeconds(900))
        val requested = BandAlarmCommitment(BAND_ALARM_TITLE_B, 8, 15, NOW.minusSeconds(60))
        val tableShowingBoth = listOf(
            slot(0, enabled = true, hour = 8, minute = 0, title = BAND_ALARM_TITLE_A),
            slot(1, enabled = true, hour = 8, minute = 15, title = BAND_ALARM_TITLE_B),
        )

        val tick1 = decideBandAlarmCommands(null, requested, confirmed, pendingDismissTitles = emptySet(), slots = tableShowingBoth, now = NOW, zone = ZONE_UTC)
        assertEquals(setOf(BandAlarmCommand.Dismiss(BAND_ALARM_TITLE_A), BandAlarmCommand.Dismiss(BAND_ALARM_TITLE_B)), tick1.commands.toSet())
        assertNull(tick1.requestedBandAlarm)
        assertNull(tick1.confirmedBandAlarm)
        assertEquals(setOf(BAND_ALARM_TITLE_A, BAND_ALARM_TITLE_B), tick1.pendingDismissTitles)

        // A is gone, B is not yet: only B's dismissal is re-sent, and the tick is not reported as fully done.
        val tableWithOnlyB = listOf(slot(0, enabled = true, hour = 8, minute = 15, title = BAND_ALARM_TITLE_B))
        val tick2 = decideBandAlarmCommands(null, null, null, tick1.pendingDismissTitles, tableWithOnlyB, NOW.plusSeconds(300), ZONE_UTC)
        assertEquals(listOf(BandAlarmCommand.Dismiss(BAND_ALARM_TITLE_B)), tick2.commands)
        assertEquals(setOf(BAND_ALARM_TITLE_B), tick2.pendingDismissTitles)

        val tick3 = decideBandAlarmCommands(null, null, null, tick2.pendingDismissTitles, emptyList(), NOW.plusSeconds(600), ZONE_UTC)
        assertEquals(BandAlarmOutcome.DISMISSED, tick3.outcome)
        assertTrue(tick3.commands.isEmpty())
        assertTrue(tick3.pendingDismissTitles.isEmpty())
    }

    @Test
    fun `FINISHED while blind still sends the teardown dismissals`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 0, NOW.minusSeconds(900))
        val requested = BandAlarmCommitment(BAND_ALARM_TITLE_B, 8, 15, NOW.minusSeconds(60))

        val decision = decideBandAlarmCommands(null, requested, confirmed, pendingDismissTitles = emptySet(), slots = null, now = NOW, zone = ZONE_UTC)

        assertEquals(setOf(BandAlarmCommand.Dismiss(BAND_ALARM_TITLE_A), BandAlarmCommand.Dismiss(BAND_ALARM_TITLE_B)), decision.commands.toSet())
        assertNull(decision.requestedBandAlarm)
        assertNull(decision.confirmedBandAlarm)
        assertEquals(setOf(BAND_ALARM_TITLE_A, BAND_ALARM_TITLE_B), decision.pendingDismissTitles)
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
        assertEquals(BAND_ALARM_TITLE_A, requested?.title, "the frozen commitment always keeps its original title")
        assertEquals(8, requested?.hour)
        assertEquals(0, requested?.minute, "frozen at the very first tick's target, never retargeted while blind")
        assertEquals(BLIND_RESEND_EXHAUSTED, requested?.blindResendCount, "the one re-send is spent for good, this blind episode")
    }

    @Test
    fun `a confirmed alarm frozen for hours while the desired target keeps moving sends nothing and never changes`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 0, NOW.minusSeconds(3600))
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
        var requested = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 0, NOW.minusSeconds(3600), blindResendCount = BLIND_RESEND_EXHAUSTED)
        val target = instantAt(8, 0)

        repeat(8) { tickIndex ->
            val tick = decideBandAlarmCommands(target, requested, confirmed = null, pendingDismissTitles = emptySet(), slots = null, now = NOW.plusSeconds((tickIndex + 1) * 300L), zone = ZONE_UTC)
            assertTrue(tick.commands.isEmpty(), "tick $tickIndex: the re-send was already spent before the restart, it never repeats")
            assertEquals(BLIND_RESEND_EXHAUSTED, tick.requestedBandAlarm?.blindResendCount, "stays exhausted, never re-arms itself")
            requested = tick.requestedBandAlarm!!
        }
    }

    // ---- Table returns after a blind episode: reconciliation converges, never at zero alarms ----------------

    @Test
    fun `table returns showing the blind SET present - confirmed immediately`() {
        val blindRequest = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 0, NOW.minusSeconds(600))
        val tableShowingIt = listOf(slot(0, enabled = true, hour = 8, minute = 0, title = BAND_ALARM_TITLE_A))

        val decision = decideBandAlarmCommands(instantAt(8, 0), blindRequest, confirmed = null, pendingDismissTitles = emptySet(), slots = tableShowingIt, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.CONFIRMED, decision.outcome)
        assertEquals(BAND_ALARM_TITLE_A, decision.confirmedBandAlarm?.title)
        assertNull(decision.requestedBandAlarm)
        assertTrue(decision.commands.isEmpty())
    }

    @Test
    fun `table returns showing the blind SET absent - resent now that it can be verified for real`() {
        val blindRequest = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 0, NOW.minusSeconds(600))

        val decision = decideBandAlarmCommands(instantAt(8, 0), blindRequest, confirmed = null, pendingDismissTitles = emptySet(), slots = freeSlots(), now = NOW, zone = ZONE_UTC)

        assertEquals(listOf(BandAlarmCommand.Set(BAND_ALARM_TITLE_A, 8, 0)), decision.commands)
        assertEquals(BandAlarmOutcome.RESENT, decision.outcome)
        assertNull(decision.confirmedBandAlarm)
    }

    @Test
    fun `table returns with two enabled slots under the re-sent title sharing the desired minute - still one confirmed alarm`() {
        val blindRequest = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 0, NOW.minusSeconds(600))
        // Both the original blind SET and the bounded blind re-send landed, in two different physical slots,
        // both under title A, both at the same committed time (a blind re-send never retargets) - the exact
        // scenario the bounded-to-one re-send limit accepts as a one-time cost.
        val duplicateSlots = listOf(
            slot(0, enabled = true, hour = 8, minute = 0, title = BAND_ALARM_TITLE_A),
            slot(2, enabled = true, hour = 8, minute = 0, title = BAND_ALARM_TITLE_A),
        )

        val decision = decideBandAlarmCommands(instantAt(8, 0), blindRequest, confirmed = null, pendingDismissTitles = emptySet(), slots = duplicateSlots, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.CONFIRMED, decision.outcome)
        assertEquals(BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 0, blindRequest.at), decision.confirmedBandAlarm)
        assertTrue(decision.commands.isEmpty(), "already matches the desired time; nothing more to send")
        // A later DISMISS(A) clears both physical slots at once (Gadgetbridge dismisses by title, every
        // matching slot) - this function only ever tracks the logical alarm under a title, never slot counts.
    }

    @Test
    fun `table returns with both our titles present at different times - the band is never left without a confirmed alarm`() {
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 0, NOW.minusSeconds(3600))
        val blindRequest = BandAlarmCommitment(BAND_ALARM_TITLE_B, 7, 45, NOW.minusSeconds(600))
        // A (our real confirmed alarm) is present and correct; B (the blind episode's unconfirmed SET) never
        // landed at all.
        val tableShowingOnlyA = listOf(slot(0, enabled = true, hour = 8, minute = 0, title = BAND_ALARM_TITLE_A))

        val decision = decideBandAlarmCommands(instantAt(8, 0), blindRequest, confirmed, pendingDismissTitles = emptySet(), slots = tableShowingOnlyA, now = NOW, zone = ZONE_UTC)

        assertEquals(BAND_ALARM_TITLE_A, decision.confirmedBandAlarm?.title, "the band is never left without a confirmed alarm while B is retargeted")
        assertTrue(decision.commands.none { it is BandAlarmCommand.Dismiss }, "A is not touched just because B is being cleaned up")
    }

    // ---- C3: an orphaned slot under one of our titles, referenced by no commitment -------------------------

    @Test
    fun `an orphan enabled slot matching the desired time is adopted as confirmed`() {
        val orphan = listOf(slot(0, enabled = true, hour = 8, minute = 0, title = BAND_ALARM_TITLE_B))

        val decision = decideBandAlarmCommands(instantAt(8, 0), requested = null, confirmed = null, pendingDismissTitles = emptySet(), slots = orphan, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.CONFIRMED, decision.outcome)
        assertEquals(BandAlarmCommitment(BAND_ALARM_TITLE_B, 8, 0, NOW), decision.confirmedBandAlarm)
        assertTrue(decision.commands.isEmpty(), "adopting a slot that already matches needs no command")
    }

    @Test
    fun `an orphan enabled slot NOT matching the desired time is dismissed and tracked pending`() {
        val orphan = listOf(slot(0, enabled = true, hour = 6, minute = 0, title = BAND_ALARM_TITLE_B))

        val decision = decideBandAlarmCommands(instantAt(8, 0), requested = null, confirmed = null, pendingDismissTitles = emptySet(), slots = orphan, now = NOW, zone = ZONE_UTC)

        // Dismissing the mismatched orphan does not by itself satisfy the desired alarm: since nothing else is
        // on file, the normal (non-blind) path also requests a fresh SET under the other, genuinely free title.
        assertEquals(
            setOf(BandAlarmCommand.Dismiss(BAND_ALARM_TITLE_B), BandAlarmCommand.Set(BAND_ALARM_TITLE_A, 8, 0)),
            decision.commands.toSet()
        )
        assertEquals(setOf(BAND_ALARM_TITLE_B), decision.pendingDismissTitles)
        assertEquals(BAND_ALARM_TITLE_A, decision.requestedBandAlarm?.title)
        assertNull(decision.confirmedBandAlarm)
    }

    @Test
    fun `an orphan slot under a title already referenced by the current commitment is left to the normal path`() {
        // A is our own confirmed alarm; nothing orphaned about it.
        val confirmed = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 0, NOW.minusSeconds(900))
        val slots = listOf(slot(0, enabled = true, hour = 8, minute = 0, title = BAND_ALARM_TITLE_A))

        val decision = decideBandAlarmCommands(instantAt(8, 0), requested = null, confirmed = confirmed, pendingDismissTitles = emptySet(), slots = slots, now = NOW, zone = ZONE_UTC)

        assertEquals(BandAlarmOutcome.UNCHANGED, decision.outcome)
        assertTrue(decision.commands.isEmpty())
    }

    private fun instantAt(hour: Int, minute: Int): Instant =
        Instant.parse("2026-09-17T%02d:%02d:00Z".format(hour, minute))

    private fun slot(position: Int, enabled: Boolean, hour: Int, minute: Int, title: String?): BandAlarmSlot =
        BandAlarmSlot(position = position, enabled = enabled, hour = hour, minute = minute, title = title, smartWakeup = false, repetition = 0)

    /**
     * Two slots Gadgetbridge's own picker would claim (disabled, untitled, not smart). The slot mode is read
     * off the table (BandAlarmSlotMode.kt), so a table needs real room in it for the alternating protocol to
     * apply - a table listing only our own alarms is a one-slot band, and that is what
     * BandAlarmSingleSlotModeTest covers.
     */
    private fun freeSlots(): List<BandAlarmSlot> =
        listOf(slot(8, enabled = false, hour = 0, minute = 0, title = null), slot(9, enabled = false, hour = 0, minute = 0, title = null))
}
