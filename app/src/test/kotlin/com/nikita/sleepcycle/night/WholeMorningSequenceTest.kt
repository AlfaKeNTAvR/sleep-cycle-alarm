package com.nikita.sleepcycle.night

// File purpose: F2 - both reviews were explicit that the real testing gap was not missing Android test
// infrastructure, but that the app-layer fire-time counter (NightOrchestrator.kt's firedAlarmIsWakeAlarm) and
// the engine's own cap check (PlanSteps.kt's isPostWakeNapCapSpent, reached through chooseMode/computeAlarmPlan)
// were tested separately and never against each other across a realistic sequence - exactly the class of bug
// F2 fixed. This test composes both halves for real: napAlarmsUsed, wakeAlarmFiredAt, lastNapAlarmFiredAt and
// morningAlarmAt are never hand-picked here (contrast PostWakeNapTest.kt, engine-side only) - they are derived
// by calling the SAME pure functions PhoneAlarmReceiver/NightOrchestrator call at fire/tick time, exactly as a
// real morning would produce them, and fed straight into the real computeAlarmPlan. If the app's counting
// logic and the engine's cap check ever disagreed again, this is the test that would catch it.
//
// G2: every case below except the last inserts a 3-5 min AWAKE segment after each firing before the owner
// falls back asleep - which is exactly what hid the bug G2 fixed. The last case ("sleeps straight through")
// deliberately does not, since that is the actual scenario the referenceOnset guard broke, and the one H2
// SUPERSEDES: it walks the SAME scenario further, past the point G2 already fixed, into the pull-forward bug
// H2 fixes (see that test's own doc).
//
// H1's own sequence test (the sliding-AWAKE-nap case) lives in a separate class below,
// SlidingAwakeNapSequenceTest, since it needs the owner to wake BEFORE any post-wake nap ever exists.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.AlarmPlan
import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.engine.MAX_NAP_ALARMS
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.SegmentKind
import com.nikita.sleepcycle.engine.SleepSegment
import com.nikita.sleepcycle.engine.computeAlarmPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class WholeMorningSequenceTest {
    private val zone = ZoneOffset.UTC
    private val config = EngineConfig()

    private fun at(text: String): Instant = Instant.parse("${text}Z")
    private fun seg(start: String, end: String, kind: SegmentKind) = SleepSegment(at(start), at(end), kind)

    @Test
    fun `a whole morning - wake alarm fires, two naps fire, a genuinely new third return to sleep FINISHES - the fire-time counter and the engine's cap check agree`() {
        val setting = NightSettings(deadline = null, pickedCycles = 3)

        // The app's own bookkeeping, mutated only by calling the exact functions PhoneAlarmReceiver and
        // NightOrchestrator call - never hand-set to "what the cap check needs to see next".
        var morningAlarmAt: Instant? = null
        var wakeAlarmFiredAt: Instant? = null
        var napAlarmsUsed = 0
        var lastNapAlarmFiredAt: Instant? = null
        // J1.3: mirrors NightState.phoneAlarmFiredFor, written unconditionally by
        // PhoneAlarmReceiver.markPhoneAlarmFired - see recordFiring's own doc below.
        var phoneAlarmFiredFor: Instant? = null

        /** Models NightOrchestrator.runNightTickLocked: compute the plan from the CURRENT latch, then re-latch from its own result - see NightOrchestrator.latchMorningAlarmAt. */
        fun tick(segments: List<SleepSegment>, now: String): AlarmPlan {
            val plan = computeAlarmPlan(segments, setting, at(now), morningAlarmAt, zone, config, wakeAlarmFiredAt, napAlarmsUsed, lastNapAlarmFiredAt, phoneAlarmFiredFor)
            morningAlarmAt = latchMorningAlarmAt(morningAlarmAt, plan)
            return plan
        }

        /** Models PhoneAlarmReceiver.recordWakeOrNapFired for a plan whose own wakeAt has just fired. G8: a NAP firing always counts, pre-wake or post-wake alike - no further guard. H2: also records lastNapAlarmFiredAt. J1.3: phoneAlarmFiredFor is set unconditionally, exactly like markPhoneAlarmFired - this harness never simulates the stale-load race that can skip the branch below, only its always-written counterpart. */
        fun recordFiring(plan: AlarmPlan) {
            val firedFor = requireNotNull(plan.wakeAt)
            phoneAlarmFiredFor = firedFor
            if (firedAlarmIsWakeAlarm(plan.mode, firedFor, morningAlarmAt)) {
                wakeAlarmFiredAt = firedFor
            } else {
                napAlarmsUsed = (napAlarmsUsed + 1).coerceAtMost(MAX_NAP_ALARMS)
                lastNapAlarmFiredAt = firedFor
            }
        }

        // The main wake alarm rings at 07:00 (mode FULL_CYCLES, the picked total all used) and fires. Modeled
        // as a hand-built plan "arriving" from an earlier tick this sequence does not otherwise walk through.
        val wakePlan = AlarmPlan(AlarmMode.FULL_CYCLES, at("2026-09-17T07:00:00"), 3, at("2026-09-17T02:30:00"), false, "r")
        morningAlarmAt = latchMorningAlarmAt(null, wakePlan)
        recordFiring(wakePlan)
        assertEquals(at("2026-09-17T07:00:00"), wakeAlarmFiredAt)
        assertEquals(0, napAlarmsUsed)

        // 07:05: still awake, unconfirmed. F5: the AWAKE safety net arms nothing once the wake alarm fired.
        val stillAwake = tick(
            listOf(seg("2026-09-16T22:30:00", "2026-09-17T07:00:00", SegmentKind.LIGHT), seg("2026-09-17T07:00:00", "2026-09-17T07:05:00", SegmentKind.AWAKE)),
            "2026-09-17T07:05:00"
        )
        assertEquals(AlarmMode.NAP, stillAwake.mode)
        assertNull(stillAwake.wakeAt)

        // 07:11: falls back asleep - the first D5 nap, onset 07:10, alarm at 07:30. Nothing has fired yet.
        val firstNapArmed = tick(
            listOf(
                seg("2026-09-16T22:30:00", "2026-09-17T07:00:00", SegmentKind.LIGHT),
                seg("2026-09-17T07:00:00", "2026-09-17T07:10:00", SegmentKind.AWAKE),
                seg("2026-09-17T07:10:00", "2026-09-17T07:11:00", SegmentKind.LIGHT)
            ),
            "2026-09-17T07:11:00"
        )
        assertEquals(AlarmMode.NAP, firstNapArmed.mode)
        assertEquals(at("2026-09-17T07:30:00"), firstNapArmed.wakeAt)
        assertEquals(0, napAlarmsUsed, "an armed but not yet fired nap must never inflate the counter")

        // 07:30: the first nap alarm FIRES.
        recordFiring(firstNapArmed)
        assertEquals(1, napAlarmsUsed)

        // 07:33: briefly awake again after the first nap alarm. Arms nothing (F5).
        val secondAwake = tick(
            listOf(
                seg("2026-09-16T22:30:00", "2026-09-17T07:00:00", SegmentKind.LIGHT),
                seg("2026-09-17T07:00:00", "2026-09-17T07:10:00", SegmentKind.AWAKE),
                seg("2026-09-17T07:10:00", "2026-09-17T07:30:00", SegmentKind.LIGHT),
                seg("2026-09-17T07:30:00", "2026-09-17T07:33:00", SegmentKind.AWAKE)
            ),
            "2026-09-17T07:33:00"
        )
        assertEquals(AlarmMode.NAP, secondAwake.mode)
        assertNull(secondAwake.wakeAt)

        // 07:36: falls back asleep again - a GENUINE new awakening happened first, so this is the SECOND D5
        // nap on a fresh onset (07:35), not "the same nap re-firing" - lastNapAlarmFiredAt (07:30) is before
        // this new onset, so H2's fix does not apply here; the ordinary onset + napLength target governs.
        val secondNapArmed = tick(
            listOf(
                seg("2026-09-16T22:30:00", "2026-09-17T07:00:00", SegmentKind.LIGHT),
                seg("2026-09-17T07:00:00", "2026-09-17T07:10:00", SegmentKind.AWAKE),
                seg("2026-09-17T07:10:00", "2026-09-17T07:30:00", SegmentKind.LIGHT),
                seg("2026-09-17T07:30:00", "2026-09-17T07:35:00", SegmentKind.AWAKE),
                seg("2026-09-17T07:35:00", "2026-09-17T07:36:00", SegmentKind.LIGHT)
            ),
            "2026-09-17T07:36:00"
        )
        assertEquals(AlarmMode.NAP, secondNapArmed.mode)
        assertEquals(at("2026-09-17T07:55:00"), secondNapArmed.wakeAt)
        assertEquals(1, napAlarmsUsed, "still armed, not yet fired")

        // 07:55: the second nap alarm FIRES - the cap is now spent.
        recordFiring(secondNapArmed)
        assertEquals(MAX_NAP_ALARMS, napAlarmsUsed)

        // 07:58: briefly awake again. Arms nothing (F5) - the cap does not change that.
        val thirdAwake = tick(
            listOf(
                seg("2026-09-16T22:30:00", "2026-09-17T07:00:00", SegmentKind.LIGHT),
                seg("2026-09-17T07:00:00", "2026-09-17T07:10:00", SegmentKind.AWAKE),
                seg("2026-09-17T07:10:00", "2026-09-17T07:30:00", SegmentKind.LIGHT),
                seg("2026-09-17T07:30:00", "2026-09-17T07:35:00", SegmentKind.AWAKE),
                seg("2026-09-17T07:35:00", "2026-09-17T07:55:00", SegmentKind.LIGHT),
                seg("2026-09-17T07:55:00", "2026-09-17T07:58:00", SegmentKind.AWAKE)
            ),
            "2026-09-17T07:58:00"
        )
        assertEquals(AlarmMode.NAP, thirdAwake.mode)
        assertNull(thirdAwake.wakeAt)

        // 08:01: falls asleep a THIRD time - a genuinely new return to sleep with the cap already spent and no
        // deadline to fall back on (F4): the night FINISHES instead of arming a third nap.
        val thirdReturnToSleep = tick(
            listOf(
                seg("2026-09-16T22:30:00", "2026-09-17T07:00:00", SegmentKind.LIGHT),
                seg("2026-09-17T07:00:00", "2026-09-17T07:10:00", SegmentKind.AWAKE),
                seg("2026-09-17T07:10:00", "2026-09-17T07:30:00", SegmentKind.LIGHT),
                seg("2026-09-17T07:30:00", "2026-09-17T07:35:00", SegmentKind.AWAKE),
                seg("2026-09-17T07:35:00", "2026-09-17T07:55:00", SegmentKind.LIGHT),
                seg("2026-09-17T07:55:00", "2026-09-17T08:00:00", SegmentKind.AWAKE),
                seg("2026-09-17T08:00:00", "2026-09-17T08:01:00", SegmentKind.LIGHT)
            ),
            "2026-09-17T08:01:00"
        )
        assertEquals(AlarmMode.FINISHED, thirdReturnToSleep.mode)
        assertNull(thirdReturnToSleep.wakeAt)
    }

    @Test
    fun `the same whole morning keeps a deadline alarm instead of finishing when one is still ahead (F4)`() {
        val setting = NightSettings(deadline = at("2026-09-17T08:30:00"), pickedCycles = 3)
        var morningAlarmAt: Instant? = null
        var wakeAlarmFiredAt: Instant? = at("2026-09-17T07:00:00")
        var napAlarmsUsed = MAX_NAP_ALARMS
        var lastNapAlarmFiredAt: Instant? = null
        // J1.3: mirrors NightState.phoneAlarmFiredFor - starts at the same instant wakeAlarmFiredAt is
        // pre-set to above, since that firing is what set both in the real app.
        var phoneAlarmFiredFor: Instant? = at("2026-09-17T07:00:00")

        fun tick(segments: List<SleepSegment>, now: String): AlarmPlan {
            val plan = computeAlarmPlan(segments, setting, at(now), morningAlarmAt, zone, config, wakeAlarmFiredAt, napAlarmsUsed, lastNapAlarmFiredAt, phoneAlarmFiredFor)
            morningAlarmAt = latchMorningAlarmAt(morningAlarmAt, plan)
            return plan
        }

        fun recordFiring(plan: AlarmPlan) {
            val firedFor = requireNotNull(plan.wakeAt)
            phoneAlarmFiredFor = firedFor
            if (firedAlarmIsWakeAlarm(plan.mode, firedFor, morningAlarmAt)) {
                wakeAlarmFiredAt = firedFor
            } else {
                napAlarmsUsed = (napAlarmsUsed + 1).coerceAtMost(MAX_NAP_ALARMS)
                lastNapAlarmFiredAt = firedFor
            }
        }

        val secondNapArmed = AlarmPlan(AlarmMode.NAP, at("2026-09-17T07:55:00"), 0, at("2026-09-17T07:35:00"), false, "r")
        recordFiring(secondNapArmed) // cap already at MAX before this call; stays there.
        assertEquals(MAX_NAP_ALARMS, napAlarmsUsed)

        val thirdAwake = tick(
            listOf(
                seg("2026-09-16T22:30:00", "2026-09-17T07:00:00", SegmentKind.LIGHT),
                seg("2026-09-17T07:00:00", "2026-09-17T07:10:00", SegmentKind.AWAKE),
                seg("2026-09-17T07:10:00", "2026-09-17T07:30:00", SegmentKind.LIGHT),
                seg("2026-09-17T07:30:00", "2026-09-17T07:35:00", SegmentKind.AWAKE),
                seg("2026-09-17T07:35:00", "2026-09-17T07:55:00", SegmentKind.LIGHT),
                seg("2026-09-17T07:55:00", "2026-09-17T07:58:00", SegmentKind.AWAKE)
            ),
            "2026-09-17T07:58:00"
        )
        assertEquals(AlarmMode.NAP, thirdAwake.mode)
        assertNull(thirdAwake.wakeAt)

        val thirdReturnToSleep = tick(
            listOf(
                seg("2026-09-16T22:30:00", "2026-09-17T07:00:00", SegmentKind.LIGHT),
                seg("2026-09-17T07:00:00", "2026-09-17T07:10:00", SegmentKind.AWAKE),
                seg("2026-09-17T07:10:00", "2026-09-17T07:30:00", SegmentKind.LIGHT),
                seg("2026-09-17T07:30:00", "2026-09-17T07:35:00", SegmentKind.AWAKE),
                seg("2026-09-17T07:35:00", "2026-09-17T07:55:00", SegmentKind.LIGHT),
                seg("2026-09-17T07:55:00", "2026-09-17T08:00:00", SegmentKind.AWAKE),
                seg("2026-09-17T08:00:00", "2026-09-17T08:01:00", SegmentKind.LIGHT)
            ),
            "2026-09-17T08:01:00"
        )
        assertEquals(AlarmMode.DEADLINE_ONLY, thirdReturnToSleep.mode)
        assertEquals(at("2026-09-17T08:30:00"), thirdReturnToSleep.wakeAt)
    }

    @Test
    fun `H2 SUPERSEDES G2 - the owner sleeps straight through both naps - the second nap is a fresh napLength after the first fired, not a 2-minute pull-forward, and the interval between them is a full napLength`() {
        // G2's own regression case (a stretch that never gets interrupted by another awakening) already
        // proved the cap can ENGAGE without an awakening between naps. H2 walks the SAME scenario one step
        // further, into the part G2's own fix still got wrong: what the SECOND nap's own alarm time actually
        // is. Before H2, napAlarm's ASLEEP branch always recomputed referenceOnset + napLength - once nap 1
        // has fired without the onset changing, that recompute (07:10 + 20 = 07:30) is already in the past,
        // and pullForwardIfTooSoon squeezed it into a bare two minutes (07:33) instead of a second real
        // twenty-minute chance.
        val setting = NightSettings(deadline = null, pickedCycles = 3)
        var morningAlarmAt: Instant? = null
        var wakeAlarmFiredAt: Instant? = null
        var napAlarmsUsed = 0
        var lastNapAlarmFiredAt: Instant? = null
        // J1.3: mirrors NightState.phoneAlarmFiredFor - see the first scenario's own note above.
        var phoneAlarmFiredFor: Instant? = null

        fun tick(segments: List<SleepSegment>, now: String): AlarmPlan {
            val plan = computeAlarmPlan(segments, setting, at(now), morningAlarmAt, zone, config, wakeAlarmFiredAt, napAlarmsUsed, lastNapAlarmFiredAt, phoneAlarmFiredFor)
            morningAlarmAt = latchMorningAlarmAt(morningAlarmAt, plan)
            return plan
        }

        fun recordFiring(plan: AlarmPlan) {
            val firedFor = requireNotNull(plan.wakeAt)
            phoneAlarmFiredFor = firedFor
            if (firedAlarmIsWakeAlarm(plan.mode, firedFor, morningAlarmAt)) {
                wakeAlarmFiredAt = firedFor
            } else {
                napAlarmsUsed = (napAlarmsUsed + 1).coerceAtMost(MAX_NAP_ALARMS)
                lastNapAlarmFiredAt = firedFor
            }
        }

        // A single continuous stretch from 07:10 onward, with the tick's own `now` as its open end - unlike
        // every case above, NO further AWAKE segment is ever inserted: the owner truly never wakes again.
        fun asleepSince0710(now: String) = listOf(
            seg("2026-09-16T22:30:00", "2026-09-17T07:00:00", SegmentKind.LIGHT),
            seg("2026-09-17T07:00:00", "2026-09-17T07:10:00", SegmentKind.AWAKE),
            seg("2026-09-17T07:10:00", now, SegmentKind.LIGHT)
        )

        // The main wake alarm rings at 07:00 and fires; the owner briefly registers awake before falling back
        // asleep at 07:10 - a real transition is unavoidable there, but nothing after it.
        val wakePlan = AlarmPlan(AlarmMode.FULL_CYCLES, at("2026-09-17T07:00:00"), 3, at("2026-09-17T02:30:00"), false, "r")
        morningAlarmAt = latchMorningAlarmAt(null, wakePlan)
        recordFiring(wakePlan)

        // 07:11: falls back asleep - the first D5/G8 nap, onset 07:10, alarm at 07:30.
        val firstNapArmed = tick(asleepSince0710("2026-09-17T07:11:00"), "2026-09-17T07:11:00")
        assertEquals(AlarmMode.NAP, firstNapArmed.mode)
        assertEquals(at("2026-09-17T07:30:00"), firstNapArmed.wakeAt)

        // 07:30: the first nap alarm FIRES. The band still shows the SAME continuous stretch onset 07:10.
        recordFiring(firstNapArmed)
        assertEquals(1, napAlarmsUsed)

        // 07:31: referenceOnset is still 07:10 (no awakening ever recorded) and a nap alarm already fired for
        // THIS onset (lastNapAlarmFiredAt 07:30 >= 07:10) - H2's fix: the target is lastNapAlarmFiredAt (07:30)
        // + a full napLength (20 min) = 07:50, a genuinely fresh second chance, not now (07:31) pulled forward
        // to a bare 07:33.
        val secondNapArmed = tick(asleepSince0710("2026-09-17T07:31:00"), "2026-09-17T07:31:00")
        assertEquals(AlarmMode.NAP, secondNapArmed.mode)
        assertEquals(at("2026-09-17T07:50:00"), secondNapArmed.wakeAt)
        // H2's own DoD: the INTERVAL between the two nap alarms, not just their count, must be a full napLength.
        assertEquals(Duration.ofMinutes(20), Duration.between(firstNapArmed.wakeAt, secondNapArmed.wakeAt))

        // 07:40: a LATER tick, still before the second nap has fired, still the SAME continuous stretch. The
        // target must hold STEADY at 07:50 - not recompute to now (07:40) + 20 = 08:00, which is exactly the
        // drift bug a naive "now + napLength" fix would introduce (see WakeAlarm.kt's asleepNapTarget doc).
        val secondNapHeld = tick(asleepSince0710("2026-09-17T07:40:00"), "2026-09-17T07:40:00")
        assertEquals(AlarmMode.NAP, secondNapHeld.mode)
        assertEquals(at("2026-09-17T07:50:00"), secondNapHeld.wakeAt, "the second nap's own target must not drift forward on a later tick before it fires")

        // 07:50: the second (genuinely fresh) nap alarm FIRES - the cap is now spent.
        recordFiring(secondNapArmed)
        assertEquals(MAX_NAP_ALARMS, napAlarmsUsed)

        // 07:52: still the SAME continuous stretch, onset still 07:10. The cap engages (G2's own fix, still
        // correct and untouched by H2): FINISHED, no further alarm armed.
        val afterSecondNapFires = tick(asleepSince0710("2026-09-17T07:52:00"), "2026-09-17T07:52:00")
        assertEquals(AlarmMode.FINISHED, afterSecondNapFires.mode)
        assertNull(afterSecondNapFires.wakeAt)
    }
}

/**
 * H1's own sequence test: the owner wakes naturally BEFORE the alarm, with the picked total's budget already
 * spent, and never confirms awake or falls back asleep. Six consecutive ticks, every one of them the exact
 * shape the contract calls for - hand-traced against WakeAlarm.kt's own formulas. The AWAKE mark starts at
 * 06:30 but every tick's own `now` is a minute or more later, so the mark is never zero-length (normalizeSegments
 * drops a zero-length mark entirely, which would leave the state ASLEEP instead of AWAKE on the very first tick):
 *
 * - 06:31 (owner woke at 06:30, this tick catches it a minute later): state AWAKE, afterAwakening, owedCycles
 *   0 -> rule 7 NAP. AWAKE branch, H8: morningAlarmAt (06:45, latched from the FULL_CYCLES plan that armed
 *   the real alarm) is still AHEAD, so the plan keeps 06:45 rather than arming a nap over it.
 * - 06:36: same reasoning -> 06:45, unchanged.
 * - 06:41: same reasoning -> 06:45, unchanged.
 * - 06:46 (now has passed morningAlarmAt): `!morningAlarmAt.isAfter(now)` is true -> arms NOTHING. This is the
 *   permanent stop; morningAlarmAt is never rewritten by any of this (a NAP plan never latches it), so nothing
 *   between here and the end of the trace can ever re-open the gate.
 * - 06:51: mode is still NAP (the engine's own rule 7 test does not change), but the AWAKE branch still stops
 *   - arms nothing.
 * - 08:56 (a Doze-deferred tick, per the contract's own failure narrative): still stops - arms nothing, over
 *   two hours after morningAlarmAt passed.
 *
 * H8 SUPERSEDES the first three ticks above, which used to assert 06:51 / 06:56 / 07:01. Sliding there is the
 * defect behind the owner's "the alarm never fired" report: the phone has ONE alarm slot for the wake alarm
 * and every nap (PhoneAlarmScheduler's PHONE_ALARM_REQUEST_CODE), so each slid target overwrote the 06:45
 * alarm that was already armed, and the permanent stop at tick 4 then cancelled what was left - a night that
 * ends with no alarm at all. AlarmSequenceReplayTest (engine) walks that whole sequence armed and rung.
 *
 * Before H1, the guard compared against `previousPlan?.wakeAt` - the PREVIOUS TICK's own plan, which in this
 * exact sliding case IS the slid nap itself (`now + napLength` from one tick before), always ~15-19 min ahead
 * of `now` by construction (napLength 20 min, ticks every ~5) - so the guard could never fire, and every tick
 * here would instead arm a fresh alarm, ratcheting forward forever.
 */
class SlidingAwakeNapSequenceTest {
    private val zone = ZoneOffset.UTC
    private val config = EngineConfig()
    private val setting = NightSettings(deadline = null, pickedCycles = 3)

    private fun at(text: String): Instant = Instant.parse("${text}Z")
    private fun seg(start: String, end: String, kind: SegmentKind) = SleepSegment(at(start), at(end), kind)

    /** The one completed stretch every tick below shares: asleep from 02:15 to 06:30 (4 h 15 min, leaving the picked 3 * 90 min all but used up - owedCycles rounds to 0), then AWAKE from 06:30 onward, open-ended at `now`. */
    private fun awakeSince0630(now: String) = listOf(
        seg("2026-09-17T02:15:00", "2026-09-17T06:30:00", SegmentKind.LIGHT),
        seg("2026-09-17T06:30:00", now, SegmentKind.AWAKE)
    )

    @Test
    fun `H1 the sliding AWAKE nap stops permanently once the latched morningAlarmAt arrives, and never resumes on any later tick`() {
        var morningAlarmAt: Instant? = null
        val wakeAlarmFiredAt: Instant? = null // never set in this scenario - the real wake alarm never fires, see H1's own failure trace.
        val napAlarmsUsed = 0
        val lastNapAlarmFiredAt: Instant? = null

        fun tick(now: String): AlarmPlan {
            // J1.3: the wake alarm never fires in this scenario (see wakeAlarmFiredAt's own comment above),
            // so phoneAlarmFiredFor is always null too - nothing to mirror here.
            val plan = computeAlarmPlan(awakeSince0630(now), setting, at(now), morningAlarmAt, zone, config, wakeAlarmFiredAt, napAlarmsUsed, lastNapAlarmFiredAt, phoneAlarmFiredFor = null)
            morningAlarmAt = latchMorningAlarmAt(morningAlarmAt, plan)
            return plan
        }

        // Establishes the night's real alarm and latches morningAlarmAt to it (02:15 + 3 * 90 min = 06:45) -
        // the FULL_CYCLES plan a normal tick before 06:30 would have produced. Hand-built here, exactly like
        // the other sequence tests in this file, to start the trace from a realistic mid-night state without
        // walking through the whole night first.
        val establishedPlan = AlarmPlan(AlarmMode.FULL_CYCLES, at("2026-09-17T06:45:00"), 3, at("2026-09-17T02:15:00"), false, "r")
        morningAlarmAt = latchMorningAlarmAt(null, establishedPlan)
        assertEquals(at("2026-09-17T06:45:00"), morningAlarmAt)

        // Tick 1, 06:31: the owner woke naturally at 06:30, this tick catches it a minute later - morningAlarmAt
        // (06:45) is still ahead of now, so the AWAKE branch slides.
        val tick1 = tick("2026-09-17T06:31:00")
        assertEquals(AlarmMode.NAP, tick1.mode)
        assertEquals(at("2026-09-17T06:45:00"), tick1.wakeAt)
        assertEquals(at("2026-09-17T06:45:00"), morningAlarmAt, "a NAP plan must never rewrite the latch")

        // Tick 2, 06:36: unchanged - the morning alarm stays exactly where it is.
        val tick2 = tick("2026-09-17T06:36:00")
        assertEquals(AlarmMode.NAP, tick2.mode)
        assertEquals(at("2026-09-17T06:45:00"), tick2.wakeAt)

        // Tick 3, 06:41: still unchanged.
        val tick3 = tick("2026-09-17T06:41:00")
        assertEquals(AlarmMode.NAP, tick3.mode)
        assertEquals(at("2026-09-17T06:45:00"), tick3.wakeAt)

        // Tick 4, 06:46: now has passed morningAlarmAt (06:45) - the stop fires for the first time. This is
        // the one tick G3's own dead-code guard could reach (see H1's contract) - the difference is what
        // happens next.
        val tick4 = tick("2026-09-17T06:46:00")
        assertEquals(AlarmMode.NAP, tick4.mode)
        assertNull(tick4.wakeAt)

        // Tick 5, 06:51: the owner still has not confirmed awake or fallen back asleep. Under G3's own guard
        // this would have re-armed (previousPlan?.wakeAt was null after a null-wakeAt plan, resetting the
        // guard to false) - H1's latch is untouched by that null plan, so the stop holds.
        val tick5 = tick("2026-09-17T06:51:00")
        assertEquals(AlarmMode.NAP, tick5.mode)
        assertNull(tick5.wakeAt)

        // Tick 6, 08:56: a Doze-deferred tick over two hours later, per the contract's own failure narrative
        // (the meeting the alarm used to ring into). Still stopped.
        val tick6 = tick("2026-09-17T08:56:00")
        assertEquals(AlarmMode.NAP, tick6.mode)
        assertNull(tick6.wakeAt)
    }
}
