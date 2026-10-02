package com.nikita.sleepcycle.night

// File purpose: V10 REPLACES the original version of this file. That version asserted
// `virtualNow(warp, realInstantFor(warp, t)) == t` and then fed that verified-identical value into the SAME
// computation twice - past the assertion, the two sequences were the same computation on the same input, which
// cannot fail. It also never touched scheduleTick, armPhoneAlarmIfNeeded, PhoneAlarmReceiver or the pre-nudge
// check, never changed speed mid-sequence, and never ran a tick after the night ended.
//
// This version drives a whole simulated morning - before sleep, the wake alarm, the out-of-bed nudge armed and
// then left ringing by a pre-nudge check that (M1) finds nothing else armed to take over, a return to sleep
// (rule 7's nap), that nap firing, a SECOND nap (exercising the D5/G8 cap), and the night finally reaching
// FINISHED - through the SAME pure decision functions the app's own Context-bound code calls at each of those
// points:
//   computeAlarmPlan, shouldArmPhoneAlarm, firedAlarmIsWakeAlarm, napSupersedesPendingNudge,
//   shouldCancelNudgeForPreCheck, latchMorningAlarmAt
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
//
// P3 (owner spec, 2026-09-30) REPLACED the scenario after the wake alarm (see [driveMorning]'s own doc): the
// two band-detected naps, the supersession, the pre-nudge check and the cap are gone from the post-alarm path,
// and the owner's own Nap button and the ring-end nudge (PostAlarmCycle.kt's nextFollowUp) take their place.
// The warp-invariance contract is unchanged, and now also covers the follow-up timing (TickTrace.followUp).

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
        val nudgeSupersededByNap: Boolean,
        /** P3: the nudge or manual nap pending in the out-of-bed slot as of this tick. */
        val followUp: PendingFollowUp?,
    )

    /**
     * Drives one whole simulated morning: asleep, the wake alarm, and then - P3 (owner spec, 2026-09-30) - the
     * post-alarm cycle: the nudge armed by the firing and moved by the Stop, a band-detected return to sleep that
     * arms nothing, three manual naps in a row (no cap), each followed by its own nudge, and a late tick well
     * after that. [nowAt] recovers "now" for a given virtual tick instant - direct passthrough for the reference
     * run, or through a (possibly-changing) [ClockWarp] for a reconstructed one, exactly how a real tick's own
     * `nowInstant()` does (`AppClock.now() = virtualNow(warp, Instant.now())`).
     *
     * P3 REWROTE the part after the wake alarm. It used to drive two band-detected naps (H7.2 superseding the
     * nudge, the D5/G8 cap) to FINISHED, with an M1 pre-nudge check in between. After the morning alarm none of
     * that exists any more: no band nap, no supersession, no pre-check armed, no cap. Every instant below is
     * virtual and hand-worked from bedtime 22:00: the morning alarm at 02:30.
     */
    private fun driveMorning(nowAt: (Instant) -> Instant): List<TickTrace> {
        var events = emptyList<SimulatedSleepEvent>()
        var wakeAlarmFiredAt: Instant? = null
        var napAlarmsUsed = 0
        var lastNapAlarmFiredAt: Instant? = null
        var morningAlarmAt: Instant? = null
        var phoneAlarmFiredFor: Instant? = null
        var followUp: PendingFollowUp? = null
        val trace = mutableListOf<TickTrace>()

        fun tick(virtualNow: Instant): TickTrace {
            val now = nowAt(virtualNow)
            val segments = buildSimulatedSegments(events, now)
            val plan = computeAlarmPlan(
                segments, settings, now, morningAlarmAt, zone, config,
                wakeAlarmFiredAt, napAlarmsUsed, lastNapAlarmFiredAt, phoneAlarmFiredFor
            )
            val phoneAlarmShouldArm = shouldArmPhoneAlarm(plan.wakeAt, now, phoneAlarmFiredFor)
            // FIX4: mirrors NightOrchestrator.cancelNudgeIfSupersededByNap's own guards. P3 narrows it to a
            // pending NUDGE (never the owner's own nap), and after the morning alarm it can never fire anyway,
            // since no plan carries a nap target there.
            val sleepState = detectSleepState(normalizeSegments(segments, now, config))
            val pendingNudgeAt = followUp?.takeIf { it.kind == FollowUpKind.NUDGE }?.at
            val supersedes = napSupersedesPendingNudge(plan, pendingNudgeAt, sleepState, napAlarmArmed = phoneAlarmShouldArm)
            if (supersedes) followUp = null
            morningAlarmAt = latchMorningAlarmAt(morningAlarmAt, plan)
            val entry = TickTrace(plan, wakeAlarmFiredAt, napAlarmsUsed, morningAlarmAt, phoneAlarmShouldArm, supersedes, followUp)
            trace += entry
            return entry
        }

        /** P3: one post-alarm event, through the same [nextFollowUp] PhoneAlarmReceiver, AlarmRingService and the Nap button call. */
        fun apply(event: PostAlarmEvent) {
            followUp = nextFollowUp(event, followUp, settings.deadline, config)
        }

        /** The wake alarm firing: PhoneAlarmReceiver's own bookkeeping (F2/F6/H2), through [firedAlarmIsWakeAlarm], then the P3 safety-net nudge. */
        fun fireWakeAlarm(plan: AlarmPlan) {
            val firedFor = requireNotNull(plan.wakeAt)
            phoneAlarmFiredFor = firedFor
            if (firedAlarmIsWakeAlarm(plan.mode, firedFor, morningAlarmAt)) {
                wakeAlarmFiredAt = firedFor
            } else {
                napAlarmsUsed = (napAlarmsUsed + 1).coerceAtMost(MAX_NAP_ALARMS)
                lastNapAlarmFiredAt = firedFor
            }
            apply(PostAlarmEvent.AlarmFired(nowAt(firedFor)))
        }

        fun at(time: String): Instant = Instant.parse("2026-09-18T$time:00Z")

        // ---- Before sleep, then asleep - the picked total (3 cycles) is owed the whole time this stretch runs. ----
        tick(bedtime.minus(Duration.ofMinutes(1)))
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.ASLEEP, bedtime)
        tick(bedtime.plus(Duration.ofMinutes(1)))

        // ---- Wake alarm: onset + 3*90 min = 02:30. Checked 30 min early so EngineConfig.minAlarmLead's own
        // pull-forward (D8) cannot kick in and move the target - this must read the raw, unpulled instant. ----
        val wakePlan = tick(at("02:00")).plan
        assertEquals(AlarmMode.FULL_CYCLES, wakePlan.mode)
        assertEquals(at("02:30"), wakePlan.wakeAt)
        fireWakeAlarm(wakePlan)
        assertEquals(PendingFollowUp(FollowUpKind.NUDGE, at("02:49")), followUp, "safety net: 9 min ring, then 10 min")

        // ---- Stop pressed at 02:31, awake: the nudge moves to 02:41. ----
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.AWAKE, at("02:31"))
        apply(PostAlarmEvent.RingEnded(nowAt(at("02:31"))))
        assertEquals(PendingFollowUp(FollowUpKind.NUDGE, at("02:41")), followUp)

        // ---- Dozes off at 02:35. The band sees it; nothing is armed for it and the nudge is left alone. ----
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.ASLEEP, at("02:35"))
        val dozedTick = tick(at("02:36"))
        assertEquals(null, dozedTick.plan.wakeAt, "P3: no band-detected nap after the morning alarm")
        assertFalse(dozedTick.nudgeSupersededByNap)
        assertEquals(PendingFollowUp(FollowUpKind.NUDGE, at("02:41")), dozedTick.followUp)

        // ---- Three manual naps in a row: pressed, rings 20 min later, stopped a minute after, next press. ----
        val rounds = listOf(
            Triple("02:37", "02:57", "02:58"),
            Triple("02:59", "03:19", "03:20"),
            Triple("03:21", "03:41", "03:42"),
        )
        for ((pressed, rings, stopped) in rounds) {
            apply(PostAlarmEvent.NapPressed(nowAt(at(pressed))))
            assertEquals(PendingFollowUp(FollowUpKind.NAP, at(rings)), followUp)
            assertEquals(null, tick(at(pressed)).plan.wakeAt, "the engine arms nothing alongside the owner's own nap")
            apply(PostAlarmEvent.AlarmFired(nowAt(at(rings))))
            apply(PostAlarmEvent.RingEnded(nowAt(at(stopped))))
        }
        assertEquals(PendingFollowUp(FollowUpKind.NUDGE, at("03:52")), followUp)
        assertEquals(0, napAlarmsUsed, "the owner's own naps never count against MAX_NAP_ALARMS")

        // ---- A LATE tick, hours later - must stay inert (V10's own named gap: "never runs a tick arriving after the night ended"). ----
        val lateTick = tick(at("06:42"))
        assertEquals(null, lateTick.plan.wakeAt)
        assertFalse(lateTick.phoneAlarmShouldArm)
        assertEquals(at("02:30"), lateTick.morningAlarmAt, "nothing after the morning alarm overwrites its latched time")

        return trace
    }

    @Test
    fun `driven directly (as if at 1x), the whole morning reaches every assertion inside driveMorning`() {
        val trace = driveMorning { virtualNow -> virtualNow }
        assertEquals(PendingFollowUp(FollowUpKind.NUDGE, Instant.parse("2026-09-18T03:52:00Z")), trace.last().followUp)
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
        // computeSpeedChangeWarp is the SAME function DebugScreenController.setSpeedChoice itself calls.
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
