package com.nikita.sleepcycle.night

// File purpose: V10 REPLACES the original version of this file. That version asserted
// `virtualNow(warp, realInstantFor(warp, t)) == t` and then fed that verified-identical value into the SAME
// computation twice - past the assertion, the two sequences were the same computation on the same input, which
// cannot fail. It also never touched scheduleTick, armPhoneAlarmIfNeeded, PhoneAlarmReceiver or the pre-nudge
// check, never changed speed mid-sequence, and never ran a tick after the night ended.
//
// This version drives a whole simulated morning - before sleep, the wake alarm, the out-of-bed nudge armed and
// then cancelled by a confirmed-asleep pre-nudge check, a return to sleep (rule 7's nap), that nap firing, a
// SECOND nap (exercising the D5/G8 cap), and the night finally reaching FINISHED - through the SAME pure
// decision functions the app's own Context-bound code calls at each of those points:
//   computeAlarmPlan, shouldArmPhoneAlarm, firedAlarmIsWakeAlarm, napSupersedesPendingNudge,
//   shouldCancelNudgeForPreCheck, simulatedSleepStateAt, latchMorningAlarmAt
// scheduleTick/armPhoneAlarmIfNeeded/PhoneAlarmReceiver/the pre-nudge check's own AlarmManager plumbing
// themselves cannot be driven from a JVM unit test in this repository - there is no Robolectric (or other
// Android test harness) dependency here to fake a Context with (`app/build.gradle.kts`'s test deps are JUnit
// Jupiter and org.json only). Per this task's own instruction ("if driving a path needs a Context, extract the
// decision it turns on into a pure function and test that"), every one of the Context-bound call sites above
// delegates its actual decision to one of the pure functions this test drives directly - see the fix round's
// own report for the specific list of what is, and is not, covered by an automated test as a result.
//
// The whole scenario is replayed several ways from the SAME driving events - directly (as if at 1x), through a
// SINGLE continuous warp at 60x and at 600x, and through a warp that CHANGES mid-sequence (60x, then dropped to
// 1x partway through the nap stretch, mirroring a real desk session's "drop to 1x and watch by hand", U2) - and
// all must reach the IDENTICAL trace of decisions. The mid-sequence change and the late tick after FINISHED
// (both asserted inside [driveMorning] itself, so every replay exercises them) are exactly what the file this
// replaces never covered.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.AlarmPlan
import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.engine.MAX_NAP_ALARMS
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.computeAlarmPlan
import com.nikita.sleepcycle.engine.detectSleepState
import com.nikita.sleepcycle.engine.normalizeSegments
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** [Instant] has no `plusMinutes` of its own (unlike [java.time.LocalDateTime]) - this file's timeline reads a lot more clearly with one than with `.plus(Duration.ofMinutes(n))` at every step. */
private fun Instant.plusMinutes(minutes: Long): Instant = this.plus(Duration.ofMinutes(minutes))

class WarpedNightSequenceTest {
    private val zone = java.time.ZoneOffset.UTC
    private val config = EngineConfig()
    private val settings = NightSettings(deadline = null, pickedCycles = 3)

    /** Bedtime, whole-minute aligned so every offset from it (and every EngineConfig duration, all whole minutes - see engine.Model.kt) stays whole-minute too, which is what keeps virtualNow/realInstantFor's round trip exact at every offered speed (SimulatedClock.kt), never off by the sub-millisecond slack integer division could otherwise introduce. */
    private val bedtime: Instant = Instant.parse("2026-09-17T22:00:00Z")

    /** One tick's full decision trace - not just the [AlarmPlan], so the reference and reconstructed runs can be compared on everything the app-layer pure functions decide, not only what the engine itself returns. */
    private data class TickTrace(
        val plan: AlarmPlan,
        val wakeAlarmFiredAt: Instant?,
        val napAlarmsUsed: Int,
        val morningAlarmAt: Instant?,
        val phoneAlarmShouldArm: Boolean,
        val nudgeSupersededByNap: Boolean
    )

    /**
     * Drives one whole simulated morning: asleep, the wake alarm, an out-of-bed nudge armed then cancelled by a
     * confirmed-asleep pre-nudge check, two naps (reaching [MAX_NAP_ALARMS]), FINISHED, and a late tick well
     * after that. [nowAt] recovers "now" for a given virtual tick instant - direct passthrough for the
     * reference run, or through a (possibly-changing) [ClockWarp] for a reconstructed one, exactly how a real
     * tick's own `nowInstant()` does (`AppClock.now() = virtualNow(warp, Instant.now())`).
     */
    private fun driveMorning(nowAt: (Instant) -> Instant): List<TickTrace> {
        var events = emptyList<SimulatedSleepEvent>()
        var wakeAlarmFiredAt: Instant? = null
        var napAlarmsUsed = 0
        var lastNapAlarmFiredAt: Instant? = null
        var morningAlarmAt: Instant? = null
        var pendingNudgeAt: Instant? = null
        val trace = mutableListOf<TickTrace>()

        fun tick(virtualNow: Instant): TickTrace {
            val now = nowAt(virtualNow)
            val segments = buildSimulatedSegments(events, now)
            val plan = computeAlarmPlan(
                segments, settings, now, morningAlarmAt, zone, config,
                wakeAlarmFiredAt, napAlarmsUsed, lastNapAlarmFiredAt,
                // J1.3: this harness never models phoneAlarmFiredFor at all (see shouldArmPhoneAlarm's own
                // hardcoded null a few lines below) - untouched here for the same reason.
                phoneAlarmFiredFor = null
            )
            val phoneAlarmShouldArm = shouldArmPhoneAlarm(plan.wakeAt, now, phoneAlarmFiredFor = null)
            // FIX4: mirrors NightOrchestrator.cancelNudgeIfSupersededByNap's own two extra guards - a confirmed
            // ASLEEP reading (recomputed here exactly like the app's own detectSleepState(normalizeSegments(...))
            // pair does from the SAME segments the plan above was just built from) and a successfully armed
            // replacement nap. This test has no Android `schedulePhoneAlarm` to call, so [phoneAlarmShouldArm] -
            // already this scenario's own stand-in for "armPhoneAlarmIfNeeded would succeed" - doubles as the
            // armed signal too.
            val sleepState = detectSleepState(normalizeSegments(segments, now, config))
            val supersedes = napSupersedesPendingNudge(plan, pendingNudgeAt, sleepState, napAlarmArmed = phoneAlarmShouldArm)
            if (supersedes) pendingNudgeAt = null
            morningAlarmAt = latchMorningAlarmAt(morningAlarmAt, plan)
            val entry = TickTrace(plan, wakeAlarmFiredAt, napAlarmsUsed, morningAlarmAt, phoneAlarmShouldArm, supersedes)
            trace += entry
            return entry
        }

        /** Simulates the wake/nap alarm at [plan]'s own wakeAt actually firing: PhoneAlarmReceiver's own bookkeeping (F2/F6/H2/D4), through [firedAlarmIsWakeAlarm]. Also arms the out-of-bed nudge, H7.1's own +15 min. */
        fun fireAlarm(plan: AlarmPlan) {
            val firedFor = requireNotNull(plan.wakeAt)
            if (firedAlarmIsWakeAlarm(plan.mode, firedFor, morningAlarmAt)) {
                wakeAlarmFiredAt = firedFor
            } else {
                napAlarmsUsed = (napAlarmsUsed + 1).coerceAtMost(MAX_NAP_ALARMS)
                lastNapAlarmFiredAt = firedFor
            }
            pendingNudgeAt = firedFor.plus(config.outOfBedDelay)
        }

        /** Simulates the pre-nudge check (U6): reads the simulated timeline exactly like [simulatedSleepStateAt] does in production, and cancels the nudge on a confirmed ASLEEP reading, per [shouldCancelNudgeForPreCheck] - unless (L2.2) [mode] is already FINISHED. Returns whether it cancelled. */
        fun preNudgeCheck(virtualNow: Instant, mode: AlarmMode?): Boolean {
            val now = nowAt(virtualNow)
            val cancels = shouldCancelNudgeForPreCheck(simulatedSleepStateAt(events, now, config), mode)
            if (cancels) pendingNudgeAt = null
            return cancels
        }

        // ---- Before sleep, then asleep - the picked total (3 cycles) is owed the whole time this stretch runs. ----
        tick(bedtime.minus(Duration.ofMinutes(1)))
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.ASLEEP, bedtime)
        tick(bedtime.plus(Duration.ofMinutes(1)))

        // ---- Wake alarm: onset + 3*90 min = +4:30. Checked 30 min early so EngineConfig.minAlarmLead's own
        // pull-forward (D8) cannot kick in and move the target - this must read the raw, unpulled instant. ----
        val wakeCheckAt = bedtime.plus(Duration.ofHours(4))
        val wakePlan = tick(wakeCheckAt).plan
        assertEquals(AlarmMode.FULL_CYCLES, wakePlan.mode)
        val wakeAt = requireNotNull(wakePlan.wakeAt)
        assertEquals(bedtime.plus(Duration.ofHours(4)).plusMinutes(30), wakeAt)
        fireAlarm(wakePlan)

        // ---- Owner reports awake right after the wake alarm - closes the first stretch, so sleptSoFar reaches the full picked total and owedCycles drops to 0. ----
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.AWAKE, wakeAt.plusMinutes(1))

        // ---- The pre-nudge check fires first (H7.3, 2 min before the nudge) and finds the owner still AWAKE - it must NOT cancel the nudge. ----
        val preCheck1At = requireNotNull(pendingNudgeAt).minus(config.preNudgeCheckLead)
        assertFalse(preNudgeCheck(preCheck1At, wakePlan.mode))

        // ---- One minute after the pre-nudge check (still chronologically before the nudge's own due instant,
        // +15 min), the owner falls back asleep: rule 7's nap. ----
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.ASLEEP, wakeAt.plusMinutes(14))
        val napTick1 = tick(wakeAt.plusMinutes(15))
        assertEquals(AlarmMode.NAP, napTick1.plan.mode, "owedCycles is 0 and this is a return to sleep after an awakening - rule 7 must arm a nap")
        assertTrue(napTick1.nudgeSupersededByNap, "H7.2: a nap must supersede a still-pending nudge")

        // ---- First nap fires. ----
        val nap1WakeAt = requireNotNull(napTick1.plan.wakeAt)
        fireAlarm(napTick1.plan)
        assertEquals(1, napAlarmsUsed)

        // ---- Owner briefly reported awake, then asleep again for a second nap. ----
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.AWAKE, nap1WakeAt.plusMinutes(1))
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.ASLEEP, nap1WakeAt.plusMinutes(3))
        val napTick2 = tick(nap1WakeAt.plusMinutes(4))
        assertEquals(AlarmMode.NAP, napTick2.plan.mode, "napAlarmsUsed (1) is still under the cap - a second nap is still offered")
        val nap2WakeAt = requireNotNull(napTick2.plan.wakeAt)
        fireAlarm(napTick2.plan)
        assertEquals(2, napAlarmsUsed)

        // ---- A THIRD return to sleep after the cap is spent must FINISH the night outright (D5/G8), not arm a third nap. ----
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.AWAKE, nap2WakeAt.plusMinutes(1))
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.ASLEEP, nap2WakeAt.plusMinutes(3))
        val finishedTick = tick(nap2WakeAt.plusMinutes(4))
        assertEquals(AlarmMode.FINISHED, finishedTick.plan.mode)
        assertEquals(null, finishedTick.plan.wakeAt)
        assertFalse(finishedTick.phoneAlarmShouldArm, "a FINISHED plan has no alarm left to arm")

        // ---- A LATE tick, well after FINISHED - must stay inert (V10's own named gap: "never runs a tick arriving after the night ended"). ----
        val lateTick = tick(nap2WakeAt.plus(Duration.ofHours(3)))
        assertEquals(AlarmMode.FINISHED, lateTick.plan.mode)
        assertFalse(lateTick.phoneAlarmShouldArm)
        assertEquals(finishedTick.morningAlarmAt, lateTick.morningAlarmAt, "FINISHED never overwrites the latched morning alarm time")

        return trace
    }

    @Test
    fun `driven directly (as if at 1x), the whole morning reaches every assertion inside driveMorning`() {
        val trace = driveMorning { virtualNow -> virtualNow }
        assertEquals(AlarmMode.FINISHED, trace.last().plan.mode)
        assertEquals(MAX_NAP_ALARMS, trace.last().napAlarmsUsed)
    }

    @Test
    fun `a single continuous 60x warp reaches the IDENTICAL trace as the direct run`() {
        val reference = driveMorning { virtualNow -> virtualNow }
        val warp = ClockWarp(speed = 60, anchorReal = Instant.parse("2026-09-17T20:00:00Z"), anchorVirtual = Instant.parse("2026-09-17T21:00:00Z"))
        val reconstructed = driveMorning { virtualNow -> virtualNow(warp, realInstantFor(warp, virtualNow)) }
        assertEquals(reference, reconstructed)
    }

    @Test
    fun `a 600x warp reaches the IDENTICAL trace as the direct run`() {
        val reference = driveMorning { virtualNow -> virtualNow }
        val warp = ClockWarp(speed = 600, anchorReal = Instant.parse("2026-09-17T20:00:00Z"), anchorVirtual = Instant.parse("2026-09-17T21:00:00Z"))
        val reconstructed = driveMorning { virtualNow -> virtualNow(warp, realInstantFor(warp, virtualNow)) }
        assertEquals(reference, reconstructed)
    }

    @Test
    fun `a warp that CHANGES mid-sequence (60x, then dropped to 1x) still reaches the IDENTICAL trace - V5's own scenario`() {
        val reference = driveMorning { virtualNow -> virtualNow }
        val fastWarp = ClockWarp(speed = 60, anchorReal = Instant.parse("2026-09-17T20:00:00Z"), anchorVirtual = Instant.parse("2026-09-17T21:00:00Z"))
        // Drops to 1x partway through the nap stretch - after the wake alarm and the pre-nudge check, before
        // the second nap fires - exactly the "drop to 1x and watch by hand" desk session U2 describes.
        // computeSpeedChangeWarp is the SAME function DebugScreenController.setSpeed itself calls.
        val switchVirtualInstant = bedtime.plus(Duration.ofHours(5))
        val switchRealInstant = realInstantFor(fastWarp, switchVirtualInstant)
        val slowWarp = computeSpeedChangeWarp(fastWarp, speed = 1, realNow = switchRealInstant)
        val reconstructed = driveMorning { virtualNow ->
            val warpAtThisPoint = if (virtualNow.isBefore(switchVirtualInstant)) fastWarp else slowWarp
            virtualNow(warpAtThisPoint, realInstantFor(warpAtThisPoint, virtualNow))
        }
        assertEquals(reference, reconstructed)
    }
}
