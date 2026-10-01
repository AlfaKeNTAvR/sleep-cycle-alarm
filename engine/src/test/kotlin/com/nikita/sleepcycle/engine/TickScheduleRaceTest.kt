package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
 *
 * J3 (owner-reported, 2026-09-21) ADDS a fifth case below (`J3 replay`), pinning the regression J2 must-fix 4
 * introduced: see NightReplay.armPhoneAlarmIfNeeded's own J3 doc, and NightOrchestrator.kt's own J3 doc, for
 * the full trace. That test and the `J2 must-fix 4 replay` case just below it together pin BOTH properties the
 * arm step must hold at once: a target that fired during its own tick's sync is never re-armed a second time,
 * and a target that has never fired is never refused, however expensive that tick's own sync turns out to be.
 *
 * J4 (owner-reported, 2026-09-21) ADDED two more cases, `J4 replay` and `J4 nudge replay`: a residual gap J3
 * left in the same arm step (a stale re-arm racing the fired marker's own write, closed by
 * NightOrchestrator.shouldRefuseStaleRearm) and a behaviour change J3 shipped unnoted in what a marker match
 * returns to the nudge guard (see NightOrchestrator.kt's own J4 doc on `armPhoneAlarmIfNeeded` and
 * `napSupersedesPendingNudge` for both). Both were verified failing without their own fix, by hand, before
 * being restored - see AUTONOMOUS_DECISIONS_09_21_2026.md for the exact output.
 *
 * J5 (owner-approved revert, 2026-09-21) REMOVES `J4 replay` along with the guard it pinned - see the note
 * where it stood, below. `J4 nudge replay` stays: that half of J4 was a real fix and is untouched.
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
        // 3-minute sync). One ring, never two.
        //
        // CORRECTED, not just renamed: before must-fix 4, this exact scenario used to commit the stale
        // 06:28:30 tick's own arm step against its OWN decisionNow (still 06:28:30), which believed 06:30 was
        // still ahead of "now" and re-armed it - already in the real past, so Android fired it immediately. The
        // test used to assert that SECOND ring as an accepted, "bounded" cost, in the same house failure mode
        // this project has been bitten by five times running: a green test pinning a live defect as correct.
        // This test is J1_1-adjacent groundwork with that defect intentionally FIXED under it now, renamed
        // accordingly rather than left claiming a J1.1-only scope it no longer has.
        //
        // J3 (owner-reported, 2026-09-21) CORRECTS must-fix 4's own mechanism, not the property this test pins:
        // must-fix 4 closed the double-ring by re-reading the real CLOCK at commit time (06:31:30, catching the
        // now-past 06:30 target). That traded a duplicate ring for a DIFFERENT bug - a never-fired target could
        // now be refused forever once its own sync outlived its lead (see the `J3 replay` test below, and
        // NightOrchestrator.kt's own J3 doc for the full trace). J3 instead re-reads the FIRED MARKER live at
        // commit time and keeps the CLOCK check on the tick's own decisionNow (06:28:30) throughout - this
        // scenario now resolves at the marker check instead: the live phoneAlarmFiredFor is exactly 06:30 by
        // the time this tick commits (the alarm having fired normally in between), matching wakeAt exactly, so
        // arming is refused there, on an exact match, never on the clock. Same one ring, different reason.
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

    @Test fun `J3 replay - a never-fired pull-forward target survives an expensive dead sync and still rings`() {
        // This is the MUST-FIX regression: J2 must-fix 4's own commit-time clock re-read fixed the scenario
        // above (a duplicate ring) by opening this one (a MISSED ring, with no recovery). The owner's own
        // traced sequence: night starts, no deadline, 5 cycles, plan FULL_CYCLES wakeAt 06:30, correctly armed.
        // The phone's battery dies before 06:30 ever arrives (D8's own precondition for the recovery this test
        // pins: a target the phone never actually got to ring), and it boots hours later, past the target, with
        // the band also dead (Gadgetbridge unreachable, both of syncOrFail's own 60 s timeouts burned on the
        // first post-boot sync).
        val replay = nightOwnerTraced()

        // Well before 06:30, undisturbed: confirm the target is correctly armed going into the outage.
        replay.advanceTo("2026-09-21T04:00:00")
        assertEquals(listOf(instant("2026-09-21T06:30:00")), replay.armedTargetsAfter("2026-09-20T23:00:00"), replay.trace())

        // The battery dies: the armed 06:30 alarm and the ordinary tick schedule both disappear, without the
        // alarm ever ringing. Nothing else changes - the owner is still asleep the whole time.
        replay.batteryDies()

        // Boots at 07:41:57, past the spent 06:30 target, and the first tick's own sync costs 124 s and fails
        // (dead band) - the reviewer's own traced numbers. shouldKeepPreviousPlan correctly releases the freeze
        // (06:30 is no longer ahead of decisionNow, so there is nothing pending left to protect - J2 must-fix
        // 1's own job), so this recomputes: FULL_CYCLES still applies (the owner never woke, so the onset is
        // still 23:00 regardless of how stale the frozen segments are), raw is still the spent 06:30, and D8's
        // own pull-forward moves it to decisionNow + minAlarmLead (2 min), rounded up to the whole minute -
        // 07:44:00. The tick's own sync outlives that same 2-minute lead: it commits at 07:41:57 + 124 s =
        // 07:44:01, one second AFTER the target it just computed.
        replay.openApp("2026-09-21T07:41:57", syncDuration = Duration.ofSeconds(124), syncFails = true)

        // The tick itself only committed the PLAN (07:44:00) right away - the arm step does not run until the
        // pending tick's own commit, at 07:44:01 (decisionNow + the 124 s sync). Advance just past it.
        replay.advanceTo("2026-09-21T07:44:01")

        // Pre-J3 (must-fix 4's own commit-time clock re-read): armTimeNow was 07:44:01, strictly after the
        // 07:44:00 target, so this was refused - logged as an error and dropped, never armed, and the plan
        // stays FULL_CYCLES with a target inside the near-alarm window, so every following tick (same dead
        // sync, same phase) recomputes the identical shape and refuses again, forever. Post-J3, the arm check
        // compares against decisionNow (07:41:57) instead, still well ahead of the 07:44:00 target, so it arms
        // correctly - this assertion is what fails without the fix.
        assertEquals(listOf(instant("2026-09-21T07:44:00")), replay.armedTargetsAfter("2026-09-21T07:41:57"), replay.trace())

        replay.advanceTo("2026-09-21T08:00:00")
        assertEquals(listOf(instant("2026-09-21T07:44:00")), replay.firings.map { it.firedFor }, replay.trace())
        assertEquals(instant("2026-09-21T07:44:00"), replay.wakeAlarmFiredAt, replay.trace())
    }

    // J5 (owner-approved revert, 2026-09-21): a `J4 replay` test stood here, pinning J4's own second arming
    // guard (`shouldRefuseStaleRearm`) by tying a tick's own commit to the exact instant its already-armed,
    // unchanged target fires. It was REMOVED with the guard - it existed only for it. See the J5 record above
    // `shouldArmPhoneAlarm` in NightOrchestrator.kt for why that guard could leave a deadline night permanently
    // silent, and for the positive "an alarm is actually outstanding" evidence a correct version would need
    // before anything like it comes back with a test of its own.

    @Test fun `J4 nudge replay - a pre-wake nap firing during its own tick's sync keeps its own fresh nudge`() {
        // The behaviour-change half of the same owner report (NightOrchestrator.kt's own J4 nudge doc): J3's
        // live marker-match branch used to return true (armPhoneAlarmIfNeeded's own "or already fired" half of
        // its old contract), which napSupersedesPendingNudge read as "a fresh nap was armed this tick" -
        // cancelling whatever nudge is pending. When the marker match is reached by a firing that happened
        // DURING this tick's own sync, the nudge it cancels is not some stale leftover from an earlier alarm; it
        // is the SAME nap's own fresh nudge, armed by the SAME firing this match just confirmed. Same lattice as
        // "J1_1 replay, the nap equivalent": deadline 01:30, 3 cycles, falling back asleep at 00:46:30
        // establishes an ASLEEP rule 7 nap target of 01:06:30 (onset + napLength), correctly armed by the 00:50
        // tick, re-confirmed unchanged by the 01:05 tick (J1.1).
        //
        // P3 (owner spec, 2026-09-30) REWROTE this case. Its old night rang the 00:30 morning alarm first and
        // napped AFTER it (01:06:30) - after the morning alarm the engine now arms nothing at all, so that nap no
        // longer exists. Moved to a genuinely PRE-morning nap instead, which P3 leaves as it was: same deadline
        // and cycles, but the owner wakes at 23:58 and dozes off again at 00:00:30, before the 00:30 morning
        // alarm - no whole cycle fits before 01:30 from there, so rule 7 rings a nap at 00:20:30, in the morning
        // alarm's place. LOST in the move: the exact "fired during its own tick's 2-minute sync" race shape,
        // which needed the old night's tick lattice. What is still pinned here is the outcome J4 protects - the
        // nap's own fresh nudge survives - on an ordinary schedule; J4's exact-match return and J5's ordering
        // keep their direct unit tests (PhoneAlarmArmingTest.kt, OutOfBedNudgeSupersessionTest.kt).
        val replay = NightReplay(settings(deadline = "2026-09-21T01:30:00", cycles = 3))
        replay.startNight("2026-09-20T23:00:00")
        replay.markAsleep("2026-09-20T23:00:00")
        replay.markAwake("2026-09-20T23:58:00")
        replay.advanceTo("2026-09-20T23:59:30")
        replay.markAsleep("2026-09-21T00:00:30")
        // The nap wakes him at 00:20:30 (sleeping straight through it would, by H2/H7.2, arm a second nap
        // that supersedes this nudge - a different, pre-existing behaviour this test is not about).
        replay.advanceTo("2026-09-21T00:20:45")
        replay.markAwake("2026-09-21T00:21:00")
        replay.advanceTo("2026-09-21T00:25:00")

        val napFirings = replay.firingsAfter("2026-09-21T00:00:30")
        assertEquals(listOf(instant("2026-09-21T00:20:30")), napFirings.map { it.firedFor }, replay.trace())
        assertEquals(AlarmMode.NAP, napFirings.single().mode, replay.trace())

        // Its own nudge: rung out to the 9 min auto-stop (00:29:30), then 10 min - 00:39:30. Not superseded by
        // the very nap that armed it.
        assertEquals(instant("2026-09-21T00:39:30"), replay.pendingNudgeAt, replay.trace())
    }

    @Test fun `J5 replay - a nap cancelled while awake leaves a nudge behind, not a silent night`() {
        // The composed hole (reviewer-reported, 2026-09-21, NightOrchestrator.awakeNapCancellationNeedsNudge's
        // own doc): the 06:30 wake alarm fires and arms its own 06:45 nudge, the owner dozes off at 06:33, a
        // tick arms a rule 7 nap and correctly supersedes that nudge, then the owner stirs awake again and the
        // next tick's AWAKE branch cancels the nap. Pre-J5 the night was left with no alarm, no nudge and no
        // pre-check, and nothing re-arms a nudge except a firing, a boot restore or a speed change - so nothing
        // ever rang again.
        //
        // P3 (owner spec, 2026-09-30) REWROTE this case: the hole it pinned can no longer open after the
        // morning alarm, because nothing there arms a nap to supersede the nudge in the first place. Same night,
        // new expectation: the nudge is simply never touched by dozing off and stirring again. (J5's predicate,
        // awakeNapCancellationNeedsNudge, keeps its unit tests in OutOfBedNudgeSupersessionTest.kt for the
        // pre-morning case, where a band-detected nap can still supersede a nudge.)
        val replay = NightReplay(settings(cycles = 5))
        replay.startNight("2026-09-20T23:00:00")
        replay.markAsleep("2026-09-20T23:00:00")

        // The wake alarm rings at 06:30; nobody stops it, so its nudge is 06:30 + 9 + 10 = 06:49. The owner is
        // awake briefly, then dozes off again at 06:33.
        replay.markAwake("2026-09-21T06:30:30")
        replay.markAsleep("2026-09-21T06:33:00")

        // Used to: a band-detected nap armed here, and the nudge cancelled (pendingNudgeAt null).
        replay.advanceTo("2026-09-21T06:46:00")
        assertEquals(instant("2026-09-21T06:49:00"), replay.pendingNudgeAt, replay.trace())

        // The owner stirs; the nudge rings at 06:49 regardless, and the next is pending for 07:08.
        replay.markAwake("2026-09-21T06:47:00")
        replay.advanceTo("2026-09-21T07:00:00")
        assertEquals(listOf(instant("2026-09-21T06:49:00")), replay.nudgeFirings, replay.trace())
        assertEquals(instant("2026-09-21T07:08:00"), replay.pendingNudgeAt, replay.trace())
    }

    @Test fun `J5 delivery replay - a late-delivered alarm arms its nudge from when it was delivered, not from what it was armed for`() {
        // The harness fidelity bug (reviewer-reported, 2026-09-21, NightReplay.fireAlarm's own J5 doc): this
        // harness measured the nudge from the instant the alarm was ARMED for, while PhoneAlarmReceiver
        // measures it from the receiver's own `now` - the instant the intent actually arrived. Pre-J5 the two
        // disagreed by exactly the delivery lateness, silently, on every night this harness has ever replayed.
        val replay = nightOwnerTraced()

        // The 06:30 wake alarm is delivered two minutes late, as Doze or an alarm-manager backlog can do.
        //
        // L1 (owner decision, 2026-09-21) MOVED this stop from 07:00 to 06:46. The nudge this test is about
        // now actually rings in the harness and arms the next one, so a 07:00 stop would have the 06:47 nudge
        // fire (delivered 06:49) and the assertion below read its 07:04 successor instead of the instant this
        // test exists to pin. Stopping just before the nudge is due leaves exactly what this test measured
        // before - the wake alarm delivered and its nudge armed, nothing else having happened yet.
        replay.advanceTo("2026-09-21T06:46:00", alarmDelivery = Duration.ofMinutes(2))

        // Attribution is unaffected by lateness, on both sides: PhoneAlarmReceiver reads the armed-for instant
        // off the intent's own extra, never the delivery time (J2 must-fix 2).
        assertEquals(listOf(instant("2026-09-21T06:30:00")), replay.firings.map { it.firedFor }, replay.trace())
        assertEquals(instant("2026-09-21T06:30:00"), replay.wakeAlarmFiredAt, replay.trace())

        // The assertion that fails without the fix: measured from the 06:32 delivery, not the 06:30 arming.
        // P3 (2026-09-30): delivered 06:32, rung out to its 9 min auto-stop (06:41), then 10 min - 06:51 (it was
        // 06:47 when the nudge was 15 min from the firing). Still well after this advance's own 06:46 stop.
        assertEquals(instant("2026-09-21T06:51:00"), replay.pendingNudgeAt, replay.trace())
    }

    @Test fun `L1 replay - the out-of-bed nudge repeats every outOfBedDelay while the owner never confirms being awake`() {
        // L1 (owner decision, 2026-09-21): pressing "I'm awake" is the only thing that means the owner is
        // genuinely up. It was never pressed here, so the nudge must keep ringing - see
        // PhoneAlarmReceiver.armOutOfBedNudge's own L1 doc for the contract, and docs/decisions.md's L1 record
        // for the owner's own reasoning and his explicit rejection of a cap.
        //
        // The night this replays is the plainest possible version of "did not get up": the 06:30 wake alarm
        // rings, the band reads awake from 06:30:30 onward, and the owner simply lies there. Rule 7's AWAKE
        // branch has nothing left to arm once the wake alarm has fired (F5/H1), so after 06:30 the nudge is
        // the ONLY thing in this night capable of ringing at all - which is exactly why it repeating matters.
        val replay = nightOwnerTraced()
        replay.markAwake("2026-09-21T06:30:30")
        replay.advanceTo("2026-09-21T07:59:00")

        // The assertion that fails without the change: pre-L1, PhoneAlarmReceiver.recordRealAlarmFired
        // returned at `if (isOutOfBed) return` before it could arm anything, so this list was exactly
        // [06:45] and the night went silent from there. A whole lattice, with no cap and no deadline stop, is
        // the point.
        //
        // P3 (owner spec, 2026-09-30): the lattice is now 19 min, not 15 - in this harness nobody presses Stop,
        // so each ring runs to its 9 min auto-stop and the next nudge lands 10 min after that (on the phone, a
        // Stop pressed at once gives 10 min). 06:30 -> 06:49 -> 07:08 -> 07:27 -> 07:46 -> 08:05.
        assertEquals(
            listOf("06:49", "07:08", "07:27", "07:46").map { instant("2026-09-21T$it:00") },
            replay.nudgeFirings,
            replay.trace()
        )
        // Still one link pending when the test stops - the chain does not end on its own.
        assertEquals(instant("2026-09-21T08:05:00"), replay.pendingNudgeAt, replay.trace())

        // The nudge's own firings never touch the phone alarm slot's own bookkeeping
        // (PhoneAlarmReceiver.firedAlarmRecordsPlanBookkeeping): exactly one plan alarm rang this night, the
        // 06:30 wake alarm, and no nudge was ever counted as a nap.
        assertEquals(listOf(instant("2026-09-21T06:30:00")), replay.firings.map { it.firedFor }, replay.trace())
        assertEquals(0, replay.napAlarmsUsed, replay.trace())

        // And the repeat never fights J5's own re-arm (awakeNapCancellationNeedsNudge): every instant above
        // sits on the 19-minute lattice measured from the 06:30 firing, so no tick ever added a nudge of its
        // own measured from its own `now`. It cannot: a nudge is always pending while the chain is alive, and
        // that predicate requires none to be.
        assertTrue(replay.nudgeFirings.zipWithNext().all { (first, second) -> Duration.between(first, second) == Duration.ofMinutes(19) }, replay.trace())
    }

    @Test fun `J1_1 replay - waking 4 minutes before the alarm and opening the app 90 s before it still rings correctly, once, attributed to the wake alarm`() {
        // NAMING NOTE, checked by hand: this pins J1.1 alone, not J1.2. It was originally meant to also exercise
        // J1.2's own window widening in firedAlarmIsWakeAlarm (since the firing here is NAP-mode, not
        // FULL_CYCLES, going through the H8/J1.2 attribution check instead of morningAlarmAlreadyRang), but with
        // J1.1 applied the firing lands EXACTLY at morningAlarmAt (06:30) - the whole point of J1.1 keeping the
        // target unshifted - so H8's ORIGINAL exact-equality check already succeeds here too, and reverting
        // J1.2 alone (with J1.1 still applied) leaves this test passing unchanged.
        //
        // J3 SHOULD FIX 4 (reviewer note, 2026-09-21) CORRECTS the reference below: J1.2's own window no longer
        // exists to be pinned by anything - J2 must-fix 2 REVERTED it back to H8's original exact equality (see
        // NightOrchestrator.kt's own must-fix 2 doc for why the window was itself a regression), and the very
        // test this note used to point at was INVERTED along with it: `NapAlarmCountingTest.kt`'s test, once
        // named `J1_2 a NAP firing one minute after the latched morning alarm is still the wake alarm`, is now
        // `J2 must-fix 2 - a NAP firing one minute after the latched morning alarm is a genuine nap, not the
        // wake alarm` (plus its siblings, same file) - asserting the OPPOSITE of what this note used to claim.
        // A reader following the old reference would land on an assertion of the opposite; corrected here rather
        // than left pointing at a test that no longer says what this note used to say it says.
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

    @Test fun `J1_1 replay, the nap equivalent - a scheduled tick landing inside minAlarmLead before a pre-wake nap target does not shift it`() {
        // Deliberately its OWN clean night (not nightOwnerTraced's own offset start), with a deadline
        // (01:30) close enough that onset + one whole cycle overruns it - isNapEligible's own deadline branch,
        // so the return to sleep at 00:46:30 is NAP-eligible regardless of owedCycles. This also lands the
        // wake alarm cleanly at 00:30 with no lead-window issue of its own (the grid lands exactly on it, same
        // as AlarmSequenceReplayTest's own nightAsleepAtEleven) - this test is isolated to the NAP half, never
        // touching J1.1's own effect on the morning alarm itself, so a broken pullForwardIfTooSoon cannot also
        // perturb the morning alarm's own ring time and cascade into a different NAP grid phase than the one
        // this test's own numbers were worked out against.
        //
        // P3 (owner spec, 2026-09-30) REWROTE this case. Its old night napped AFTER the 00:30 morning alarm,
        // where the engine now arms nothing at all. Moved to the genuinely PRE-morning nap of "J4 nudge replay"
        // above (woke 23:58, asleep again 00:00:30, rule 7 rings 00:20:30 in the 00:30 morning alarm's place).
        // LOST in the move: the old night's tick lattice, which put a tick exactly 90 s before the target.
        // What is still pinned is the outcome J1.1 protects for a nap target - armed once and never moved later,
        // rung exactly at its own instant; J1.1's lead-window case itself keeps its morning-alarm replay at the
        // top of this file and its unit tests in ComputeWakeAlarmTest.kt (pullForwardIfTooSoon is one function
        // for every rule).
        val replay = NightReplay(settings(deadline = "2026-09-21T01:30:00", cycles = 3))
        replay.startNight("2026-09-20T23:00:00")
        replay.markAsleep("2026-09-20T23:00:00")
        replay.markAwake("2026-09-20T23:58:00")
        replay.advanceTo("2026-09-20T23:59:30")
        replay.markAsleep("2026-09-21T00:00:30")

        replay.advanceTo("2026-09-21T00:20:00")
        assertEquals(listOf(instant("2026-09-21T00:20:30")), replay.armedTargetsAfter("2026-09-21T00:00:30"), replay.trace())

        replay.advanceTo("2026-09-21T00:25:00")
        val napFirings = replay.firingsAfter("2026-09-20T23:58:00")
        assertEquals(listOf(instant("2026-09-21T00:20:30")), napFirings.map { it.firedFor }, replay.trace())
        assertEquals(AlarmMode.NAP, napFirings.single().mode, replay.trace())
        assertTrue(replay.napAlarmsUsed >= 1, replay.trace())
    }
}
