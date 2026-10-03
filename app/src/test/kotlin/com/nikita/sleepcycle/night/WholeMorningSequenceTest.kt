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
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.SegmentKind
import com.nikita.sleepcycle.engine.SleepSegment
import com.nikita.sleepcycle.engine.computeAlarmPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

class WholeMorningSequenceTest {
    private val zone = ZoneOffset.UTC
    private val config = EngineConfig()

    private fun at(text: String): Instant = Instant.parse("${text}Z")
    private fun seg(start: String, end: String, kind: SegmentKind) = SleepSegment(at(start), at(end), kind)

    /**
     * P3 (owner spec, 2026-09-30) REPLACES the three cases that stood here: the two-nap morning that FINISHED,
     * its F4 deadline twin, and H2's "sleeps straight through both naps". All three walked band-detected naps
     * AFTER the morning alarm - armed, fired, counted against MAX_NAP_ALARMS - and P3 removed exactly that: after
     * the morning alarm the engine arms nothing, and the owner's own Nap button (PostAlarmCycle.kt) takes over,
     * uncounted. The F2 contract this file exists for (the receiver's fire-time counter and the engine's cap
     * check, composed for real) still holds on the same timeline, in its new shape: with nothing armed, nothing
     * fires, so the counter never moves and the cap never bites, however many times he dozes off. The cap's own
     * mode logic keeps its engine tests (PostWakeNapTest.kt), and the pre-wake side keeps
     * SlidingAwakeNapSequenceTest below.
     */
    @Test
    fun `P3 a whole morning of dozing off three times after the wake alarm arms nothing, counts nothing, and never finishes the night`() {
        val setting = NightSettings(deadline = null, pickedCycles = 3)

        // The app's own bookkeeping, mutated only by calling the exact functions PhoneAlarmReceiver and
        // NightOrchestrator call - never hand-set to "what the cap check needs to see next".
        var morningAlarmAt: Instant? = null
        var wakeAlarmFiredAt: Instant? = null
        var napAlarmsUsed = 0
        var lastNapAlarmFiredAt: Instant? = null
        var phoneAlarmFiredFor: Instant? = null

        fun tick(segments: List<SleepSegment>, now: String): AlarmPlan {
            val plan = computeAlarmPlan(segments, setting, at(now), morningAlarmAt, zone, config, wakeAlarmFiredAt, napAlarmsUsed, lastNapAlarmFiredAt, phoneAlarmFiredFor)
            morningAlarmAt = latchMorningAlarmAt(morningAlarmAt, plan)
            return plan
        }

        // The main wake alarm rings at 07:00 and fires (PhoneAlarmReceiver.recordWakeOrNapFired).
        val wakePlan = AlarmPlan(AlarmMode.FULL_CYCLES, at("2026-09-17T07:00:00"), 3, at("2026-09-17T02:30:00"), false, "r")
        morningAlarmAt = latchMorningAlarmAt(null, wakePlan)
        phoneAlarmFiredFor = wakePlan.wakeAt
        if (firedAlarmIsWakeAlarm(wakePlan.mode, at("2026-09-17T07:00:00"), morningAlarmAt)) wakeAlarmFiredAt = wakePlan.wakeAt
        assertEquals(at("2026-09-17T07:00:00"), wakeAlarmFiredAt)

        // Awake 07:00-07:10, asleep 07:10-07:30, awake 07:30-07:35, asleep 07:35-07:55, awake 07:55-08:00,
        // asleep from 08:00. Each return to sleep used to arm a nap (07:30, 07:55) and the third FINISHED.
        val timeline = listOf(
            "2026-09-17T07:00:00" to SegmentKind.AWAKE, "2026-09-17T07:10:00" to SegmentKind.LIGHT,
            "2026-09-17T07:30:00" to SegmentKind.AWAKE, "2026-09-17T07:35:00" to SegmentKind.LIGHT,
            "2026-09-17T07:55:00" to SegmentKind.AWAKE, "2026-09-17T08:00:00" to SegmentKind.LIGHT,
        )
        fun segmentsUntil(now: String): List<SleepSegment> {
            val marks = listOf("2026-09-16T22:30:00" to SegmentKind.LIGHT) + timeline.filter { at(it.first).isBefore(at(now)) }
            return marks.mapIndexed { index, (start, kind) -> seg(start, marks.getOrNull(index + 1)?.first ?: now, kind) }
        }
        val ticks = listOf("2026-09-17T07:05:00", "2026-09-17T07:11:00", "2026-09-17T07:15:00", "2026-09-17T07:33:00",
            "2026-09-17T07:36:00", "2026-09-17T07:40:00", "2026-09-17T07:58:00", "2026-09-17T08:01:00")
        for (now in ticks) {
            val plan = tick(segmentsUntil(now), now)
            assertNull(plan.wakeAt, "nothing armed at $now")
            assertEquals(AlarmMode.NAP, plan.mode, "rule 7's mode still applies at $now, it just arms nothing")
        }
        assertEquals(0, napAlarmsUsed)
        assertNull(lastNapAlarmFiredAt)
        assertEquals(at("2026-09-17T07:00:00"), morningAlarmAt)
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

    /** The one completed stretch every tick below shares: asleep from 02:05 to 06:30 (4 h 25 min, leaving 5 min of the picked 3 * 90 min - inside the 10 min forgiveness, so owedCycles is 0; it was 02:15 until the owner's 2026-10-03 round-up rule, when 15 min left still rounded to 0), then AWAKE from 06:30 onward, open-ended at `now`. */
    private fun awakeSince0630(now: String) = listOf(
        seg("2026-09-17T02:05:00", "2026-09-17T06:30:00", SegmentKind.LIGHT),
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
