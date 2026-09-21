package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * J1.4 (owner-reported, 2026-09-21): the four regression cases the re-anchored [NightReplay] was built to make
 * reachable at all - see its own class doc for why 498 tests missed every one of these before. Each test below
 * says in its own comment exactly which fix it was verified against (temporarily reverting that one guard in a
 * scratch copy of WakeAlarm.kt and confirming an AssertionFailedError, then restoring it - see
 * AUTONOMOUS_DECISIONS_09_21_2026.md for the exact commands and output). Two of the four turned out, once
 * checked this way, to depend on J1.1 alone rather than on J1.2 or J1.3 specifically - said so plainly in their
 * own comments rather than left implied, since a test whose docstring claims more than it verifies is exactly
 * the kind of vacuous coverage this project has been bitten by before.
 */
class TickScheduleRaceTest {
    /**
     * The owner's own traced night: night started 22:53:30, band onset 23:00, 5 cycles, raw target 06:30. The
     * tick grid this produces (normalSyncDelay 15 min from 22:53:30, then frequentSyncDelay 5 min once inside
     * nearAlarmSyncWindow of 06:30) sits on a lattice offset by 3 min 30 s from a clean 5-minute boundary -
     * landing a tick at 06:28:30, ninety seconds before the alarm, exactly as the owner traced it. [startNight]
     * and [markAsleep] are deliberately NOT the same instant, unlike [AlarmSequenceReplayTest]'s own
     * `nightAsleepAtEleven` - the offset between them is what produces this exact lattice phase.
     */
    private fun nightOwnerTraced(): NightReplay {
        val replay = NightReplay(settings(cycles = 5))
        replay.startNight("2026-09-20T22:53:30")
        replay.markAsleep("2026-09-20T23:00:00")
        return replay
    }

    @Test fun `J1_1 replay - a scheduled tick landing inside minAlarmLead before the morning alarm does not shift it`() {
        val replay = nightOwnerTraced()

        // Asleep straight through - the natural schedule lands a tick at 06:28:30, ninety seconds before the
        // correctly-armed 06:30 target, then the target itself at 06:30.
        replay.advanceTo("2026-09-21T07:00")

        // Pre-J1.1 the 06:28:30 tick pulled 06:30 forward to 06:31 (now + minAlarmLead, rounded up), replacing
        // the correct alarm in the phone's one slot - so the ONLY thing that ever rang was 06:31, a minute
        // late. Post-J1.1, 06:30 is still ahead of 06:28:30's own `now`, so it is returned unchanged.
        assertEquals(listOf(instant("2026-09-21T06:30:00")), replay.firings.map { it.firedFor }, replay.trace())
        assertEquals(instant("2026-09-21T06:30:00"), replay.wakeAlarmFiredAt, replay.trace())
    }

    @Test fun `J2 must-fix 4 replay - the same lead-window tick with a slow sync rings only once, not twice`() {
        // Advance to just before the 06:28:30 tick, then let THAT one tick run with a 3-minute sync duration:
        // its own input snapshot (taken at 06:28:30, before the 06:30 firing) still sees no wakeAlarmFiredAt
        // and no phoneAlarmFiredFor, so - exactly like the fast-tick case above - it computes 06:30 unchanged
        // (J1.1). The already-armed 06:30 alarm fires normally in between, at 06:30, attributed correctly via
        // the ordinary lastPlan match. The 06:28:30 tick then finally commits at 06:31:30 (after its own
        // 3-minute sync) - J2 must-fix 4's own re-read of the real clock right at that commit sees 06:31:30,
        // not the tick's own stale 06:28:30 decisionNow, so the past-check correctly refuses to re-arm a target
        // (06:30) that, by now, is already a minute and a half gone. One ring, never two.
        //
        // CORRECTED, not just renamed: before must-fix 4, this exact scenario used to commit the stale
        // 06:28:30 tick's own arm step against its OWN decisionNow (still 06:28:30), which believed 06:30 was
        // still ahead of "now" and re-armed it - already in the real past, so Android fired it immediately. The
        // test used to assert that SECOND ring as an accepted, "bounded" cost, in the same house failure mode
        // this project has been bitten by five times running: a green test pinning a live defect as correct.
        // This test is J1_1-adjacent groundwork with that defect intentionally FIXED under it now, renamed
        // accordingly rather than left claiming a J1.1-only scope it no longer has.
        val replay = nightOwnerTraced()

        // Stop just short of the 06:28:30 tick (the previous one, at 06:23:30, still runs and commits
        // normally, so 06:30 is correctly armed going in) - the 3-minute sync duration must apply to the
        // 06:28:30 tick ITSELF, not to one that already ran and committed before this call started.
        replay.advanceTo("2026-09-21T06:23:31")
        replay.advanceTo("2026-09-21T06:33:00", syncDuration = Duration.ofMinutes(3))
        assertEquals(
            listOf(instant("2026-09-21T06:30:00")),
            replay.firings.map { it.firedFor },
            replay.trace()
        )

        // The bound: keep advancing well past the alarm, and the ring count must never climb past one.
        replay.advanceTo("2026-09-21T08:00:00")
        assertEquals(1, replay.firings.size, replay.trace())
        assertEquals(instant("2026-09-21T06:30:00"), replay.wakeAlarmFiredAt, replay.trace())
    }

    @Test fun `J1_1 replay - waking 4 minutes before the alarm and opening the app 90 s before it still rings correctly, once, attributed to the wake alarm`() {
        // NAMING NOTE, checked by hand: this pins J1.1 alone, not J1.2. It was originally meant to also exercise
        // J1.2's own window widening in firedAlarmIsWakeAlarm (since the firing here is NAP-mode, not
        // FULL_CYCLES, going through the H8/J1.2 attribution check instead of morningAlarmAlreadyRang), but with
        // J1.1 applied the firing lands EXACTLY at morningAlarmAt (06:30) - the whole point of J1.1 keeping the
        // target unshifted - so H8's ORIGINAL exact-equality check already succeeds here too, and reverting
        // J1.2 alone (with J1.1 still applied) leaves this test passing unchanged. J1.2's own window is pinned
        // directly by NapAlarmCountingTest.kt's `J1_2 a NAP firing one minute after the latched morning alarm is
        // still the wake alarm` and its siblings, all verified to fail against the pre-J1.2 exact-equality
        // check when it landed.
        val replay = nightOwnerTraced()

        // Asleep until 06:26, four minutes before the 06:30 alarm - then awake, still lying there.
        replay.advanceTo("2026-09-21T06:26:00")
        replay.markAwake("2026-09-21T06:26:00")

        // The owner opens the app ninety seconds before the alarm. This runs rule 7's AWAKE branch
        // (awakeNapTarget), which defers to the still-pending morning alarm (06:30) rather than sliding past
        // it (H8) - and that same 06:30 then goes through the SAME pullForwardIfTooSoon J1.1 fixed, on this
        // different call path (NAP mode, not FULL_CYCLES). Pre-J1.1 this shifted the target to 06:31 here too.
        replay.openApp("2026-09-21T06:28:30")
        assertEquals(listOf(instant("2026-09-21T06:30:00")), replay.armedTargetsAfter("2026-09-21T06:26:00"), replay.trace())

        replay.advanceTo("2026-09-21T07:00:00")

        // Fires as a NAP-mode plan (rule 7's own AWAKE branch armed it), correctly attributed to the MAIN wake
        // alarm rather than counted as one of the two naps, via H8's own mode-aware check.
        assertEquals(listOf(instant("2026-09-21T06:30:00")), replay.firings.map { it.firedFor }, replay.trace())
        assertEquals(AlarmMode.NAP, replay.firings.single().mode, replay.trace())
        assertEquals(instant("2026-09-21T06:30:00"), replay.wakeAlarmFiredAt, replay.trace())
        assertEquals(0, replay.napAlarmsUsed, replay.trace())
    }

    @Test fun `J1_1 replay, the nap equivalent - a scheduled tick landing inside minAlarmLead before a mid-night nap target does not shift it`() {
        // Deliberately its OWN clean night (not nightOwnerTraced's own offset start), with a deadline
        // (01:30) close enough that onset + one whole cycle overruns it - isNapEligible's own deadline branch,
        // so the return to sleep at 00:46:30 is NAP-eligible regardless of owedCycles. This also lands the
        // wake alarm cleanly at 00:30 with no lead-window issue of its own (the grid lands exactly on it, same
        // as AlarmSequenceReplayTest's own nightAsleepAtEleven) - this test is isolated to the NAP half, never
        // touching J1.1's own effect on the morning alarm itself, so a broken pullForwardIfTooSoon cannot also
        // perturb the morning alarm's own ring time and cascade into a different NAP grid phase than the one
        // this test's own numbers were worked out against.
        val replay = NightReplay(settings(deadline = "2026-09-21T01:30:00", cycles = 3))
        replay.startNight("2026-09-20T23:00:00")
        replay.markAsleep("2026-09-20T23:00:00")

        // The wake alarm rings cleanly at 00:30, the picked total fully used up.
        replay.markAwake("2026-09-21T00:31:00")
        replay.advanceTo("2026-09-21T00:45:01")

        // Falls back asleep at 00:46:30 - NOT the instant the next tick first sees it (J1.4's own point): the
        // tick that establishes rule 7's ASLEEP nap (asleepNapTarget, onset + napLength = 00:46:30 + 20 min =
        // 01:06:30) lands at 00:50 (nextSyncDelay from the 00:45 tick's own NAP-mode frequentSyncDelay), not at
        // 00:46:30 itself - offsetting the ensuing 5-minute NAP grid so a later tick lands at 01:05, ninety
        // seconds before the target, inside minAlarmLead.
        replay.markAsleep("2026-09-21T00:46:30")

        // Advance past the 01:05 near-miss tick, but not yet to 01:06:30 itself.
        replay.advanceTo("2026-09-21T01:05:30")

        // Pre-J1.1 the 01:05 tick pulled 01:06:30 forward to 01:07 (now + minAlarmLead, rounded up), replacing
        // the correct nap alarm with a late one. Post-J1.1, 01:06:30 is still ahead of 01:05's own `now`, so it
        // holds.
        assertEquals(listOf(instant("2026-09-21T01:06:30")), replay.armedTargetsAfter("2026-09-21T00:46:30"), replay.trace())

        replay.advanceTo("2026-09-21T01:15:00")
        val napFirings = replay.firingsAfter("2026-09-21T00:31:00")
        assertEquals(listOf(instant("2026-09-21T01:06:30")), napFirings.map { it.firedFor }, replay.trace())
        assertEquals(AlarmMode.NAP, napFirings.single().mode, replay.trace())
        assertTrue(replay.napAlarmsUsed >= 1, replay.trace())
    }
}
