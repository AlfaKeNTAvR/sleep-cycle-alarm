package com.nikita.sleepcycle.engine

import java.time.Duration
import java.time.Instant

/**
 * A faithful stand-in for the whole app-layer alarm loop, so a test can drive a night the way a real (or
 * simulated) night actually runs it: ticks scheduled by [nextSyncDelay], the phone alarm armed and re-armed
 * by every tick, that alarm actually FIRING when its instant arrives, and the four facts
 * PhoneAlarmReceiver records at fire time fed back into the next [computeAlarmPlan] call.
 *
 * Every rule below mirrors one named app-layer function; the engine module cannot import them (they live in
 * :app, on top of Android), so they are restated here and named in comments so a change on either side is
 * easy to find:
 *  - arming: NightOrchestrator.armPhoneAlarmIfNeeded / shouldArmPhoneAlarm
 *  - fire-time attribution: PhoneAlarmReceiver.recordWakeOrNapFired / NightOrchestrator.firedAlarmIsWakeAlarm
 *  - the morning-alarm latch: NightOrchestrator.latchMorningAlarmAt
 *  - the sleep timeline: BandDataSimulator.buildSimulatedSegments (the Debug screen's "Asleep" toggle)
 *
 * J1.4 (owner-reported, 2026-09-21) RE-ANCHORS this harness, replacing its old design where the first
 * [markAsleep]/[markAwake] call was itself an immediate tick, and every later tick was scheduled off of a mark
 * or a whole 90/20/5-minute step from one. That was precisely why 498 tests never caught J1.1 or J1.3: a
 * scheduled tick could never land inside [EngineConfig.minAlarmLead] of an already-armed target, because every
 * target sat on that same lattice the ticks did. A real band segment is never seen the instant it happens -
 * only the next tick the OS actually schedules sees it - so marks here do not tick any more; [startNight] is
 * the one explicit anchor (like NightController.startNight, the app's own first tick), and every later tick
 * comes from [nextSyncDelay] alone, landing wherever that real schedule actually lands relative to whatever
 * target is armed - including inside the lead, which is exactly the case that needed testing.
 *
 * [advanceTo] and [openApp] also take an optional sync-duration [Duration]: a tick that becomes due during
 * that call reads its own input snapshot (segments, wakeAlarmFiredAt, phoneAlarmFiredFor, and the rest) at the
 * instant it STARTS, but does not commit - arm, latch, save, schedule the next tick - until that many minutes
 * later, mirroring runNightTickLocked's own FIX1 trade-off (state is loaded and decisionNow sampled before the
 * sync's own I/O runs, and the plan is still built from that pre-sync snapshot once the sync finally
 * finishes). An armed alarm can therefore fire WHILE such a tick is still pending, using whatever was
 * committed by the tick before it - see [runTick]'s own doc for what this is built to reproduce: J1.3's
 * re-ring, bounded now to exactly one extra ring rather than an unbounded loop.
 */
internal class NightReplay(
    private val setting: NightSettings,
    private val config: EngineConfig = EngineConfig()
) {
    /** One flip of the Debug screen's "Asleep" toggle, or the band reporting the same transition. */
    private data class SleepMark(val asleep: Boolean, val at: Instant)

    /** One alarm that actually rang: the instant it was armed for, and the mode of the plan it was attributed to (null when attribution failed). */
    data class Firing(val firedFor: Instant, val mode: AlarmMode?)

    /**
     * A tick that has read its own input snapshot (at [startedAt]) but not yet committed.
     * [phoneAlarmFiredForAtStart] is carried separately from the live field of the same name because a firing
     * that happens WHILE this tick is pending must not retroactively change what this tick's own
     * (already-computed) [plan] or arm decision sees - exactly like a real tick, whose `state.phoneAlarmFiredFor`
     * is fixed at load time, before its own sync runs.
     */
    private data class PendingTick(val startedAt: Instant, val commitAt: Instant, val plan: AlarmPlan, val phoneAlarmFiredForAtStart: Instant?)

    private val marks = mutableListOf<SleepMark>()
    private var nextTickAt: Instant? = null
    private var armedAlarmAt: Instant? = null
    private var pendingTick: PendingTick? = null
    private var phoneAlarmFiredFor: Instant? = null
    private var lastNapAlarmFiredAt: Instant? = null
    private var morningAlarmAt: Instant? = null
    private var started = false

    /** F6: the instant the MAIN wake alarm fired, as PhoneAlarmReceiver would have recorded it. */
    var wakeAlarmFiredAt: Instant? = null
        private set

    /** F2/G8: how many nap alarms have actually rung this night. */
    var napAlarmsUsed: Int = 0
        private set

    /** The plan the last tick COMMITTED - the app's NightState.lastPlan. A pending (not yet committed) tick's own plan is not reflected here until it commits - see [PendingTick]. */
    var lastPlan: AlarmPlan? = null
        private set

    /** Every alarm that rang this night, in order. */
    val firings = mutableListOf<Firing>()

    /** Every arming of the phone alarm, in order: the tick that armed it, and the instant it was armed for. */
    val armings = mutableListOf<Pair<Instant, Instant>>()

    /** Every tick's own plan, in commit order, for asserting on a whole sequence rather than one call. */
    val plans = mutableListOf<AlarmPlan>()

    /**
     * NightController.startNight: the night's own explicit first tick, at [at]. Unlike every later tick there
     * is no previous state to race, so it always commits immediately (zero sync duration) - a slow FIRST sync
     * has nothing armed yet to straddle.
     */
    fun startNight(at: String) {
        check(!started) { "startNight already called - one NightReplay models exactly one night" }
        started = true
        runTick(instant(at), Duration.ZERO)
    }

    /** The Debug screen's "Asleep" toggle flipped to asleep at [at]; unlike before J1.4 this does NOT itself tick - see the class doc. */
    fun markAsleep(at: String) {
        mark(asleep = true, at = at)
    }

    /** The Debug screen's "Asleep" toggle flipped to awake at [at] - the owner marking "I am not asleep any more". Does not itself tick - see the class doc. */
    fun markAwake(at: String) {
        mark(asleep = false, at = at)
    }

    /** Runs every tick and every alarm firing due up to and including [at]. [syncDuration] applies to any SCHEDULED tick that becomes due during this call - see the class doc. */
    fun advanceTo(at: String, syncDuration: Duration = Duration.ZERO) {
        advanceTo(instant(at), syncDuration)
    }

    /**
     * NightController.runImmediateTick (the Debug screen's own "check now", or simply opening the app while a
     * night is in progress): runs a tick right at [at] regardless of whether one is due on the ordinary
     * schedule. First flushes anything already due up to [at] on the ordinary schedule (zero sync duration -
     * only the app-open tick itself uses [syncDuration]), exactly like a real immediate tick can still race an
     * already in-flight scheduled one.
     */
    fun openApp(at: String, syncDuration: Duration = Duration.ZERO) {
        val whenAt = instant(at)
        advanceTo(whenAt)
        runTick(whenAt, syncDuration)
    }

    /** The distinct instants the phone alarm was armed for by the ticks from [at] onward - what the owner watching the night screen would see the alarm time do. */
    fun armedTargetsAfter(at: String): List<Instant> {
        val from = instant(at)
        return armings.filter { (armedAt, _) -> !armedAt.isBefore(from) }.map { (_, target) -> target }.distinct()
    }

    /** Every alarm that rang at or after [at]. */
    fun firingsAfter(at: String): List<Firing> {
        val from = instant(at)
        return firings.filter { !it.firedFor.isBefore(from) }
    }

    private fun mark(asleep: Boolean, at: String) {
        check(started) { "startNight must run before any mark" }
        val markAt = instant(at)
        advanceTo(markAt)
        marks.add(SleepMark(asleep, markAt))
    }

    private fun advanceTo(at: Instant, syncDuration: Duration = Duration.ZERO) {
        while (true) {
            // Ties (a pending tick's own commit landing at the exact instant an armed alarm fires) resolve
            // commit-first, deterministically - see the class doc's own note on what that is built to express.
            val dueCommit = pendingTick?.commitAt?.takeIf { !it.isAfter(at) }
            val dueFire = armedAlarmAt?.takeIf { !it.isAfter(at) }
            val dueTick = nextTickAt?.takeIf { !it.isAfter(at) }
            val due = listOfNotNull(dueCommit, dueFire, dueTick).minOrNull() ?: return
            when (due) {
                dueCommit -> commitPendingTick()
                dueFire -> fireAlarm(due)
                else -> runTick(due, syncDuration)
            }
        }
    }

    /**
     * NightOrchestrator.runNightTickLocked: reads its own input snapshot (segments, wakeAlarmFiredAt,
     * napAlarmsUsed, lastNapAlarmFiredAt, phoneAlarmFiredFor) and computes its plan AT [at] - exactly like the
     * real tick's `decisionNow`, sampled once before its own sync I/O runs (FIX1) - but does not commit (arm,
     * latch, save, schedule the next tick) until [syncDuration] later. A zero [syncDuration] commits right
     * away, same as every tick before J1.4's own sync-duration parameter existed.
     *
     * J1.3's re-ring is reproduced through exactly this staleness: a tick that starts before an alarm fires but
     * commits after it carries a plan computed WITHOUT knowing that firing happened (its own snapshot predates
     * it) - the same [EngineConfig.minAlarmLead]-window target it already knew about is still "in the future"
     * relative to its own [at], so J1.1's own fix does not save it here, and it re-arms the very target that,
     * by the time this tick actually commits, has already rung. What J1.3 bounds is what happens AFTER that:
     * the NEXT tick's own snapshot, loaded fresh after this one commits, sees the true phoneAlarmFiredFor and
     * stops there - exactly one extra ring, never the unbounded loop from before J1.3.
     */
    private fun runTick(at: Instant, syncDuration: Duration) {
        check(pendingTick == null) { "a tick is already pending at ${pendingTick?.startedAt} - NightReplay does not model overlapping ticks" }
        nextTickAt = null
        val plan = computeAlarmPlan(
            segmentsAt(at), setting, at, morningAlarmAt, testZone, config, wakeAlarmFiredAt, napAlarmsUsed,
            lastNapAlarmFiredAt, phoneAlarmFiredFor
        )
        pendingTick = PendingTick(at, at.plus(syncDuration), plan, phoneAlarmFiredFor)
        if (syncDuration.isZero) commitPendingTick()
    }

    /** The pending tick's own arm/latch/save/reschedule, all at once - see [runTick]'s own doc for why the arm step also uses this tick's OWN (possibly stale) phoneAlarmFiredFor snapshot, never the live one. */
    private fun commitPendingTick() {
        val pending = checkNotNull(pendingTick) { "commitPendingTick called with nothing pending" }
        pendingTick = null
        armPhoneAlarmIfNeeded(pending.plan, pending.phoneAlarmFiredForAtStart, pending.startedAt)
        morningAlarmAt = latchMorningAlarmAt(morningAlarmAt, pending.plan)
        lastPlan = pending.plan
        plans.add(pending.plan)
        // FIX2: booked from this tick's own decisionNow ([PendingTick.startedAt]), never its later commit instant.
        nextTickAt = nextSyncDelay(pending.plan, pending.startedAt, config)?.let { pending.startedAt.plus(it) }
    }

    /**
     * PhoneAlarmReceiver.onReceive: the armed alarm rings, records the instant it was scheduled for
     * unconditionally (phoneAlarmFiredFor - see PhoneAlarmReceiver.markPhoneAlarmFired), and is attributed to
     * either the main wake alarm or a nap from the plan COMMITTED at that moment (recordWakeOrNapFired -
     * attribution is skipped when that plan's own wakeAt no longer matches, which this harness can still
     * reach via a mismatched commit racing a firing, even though none of the regression tests below need it).
     */
    private fun fireAlarm(at: Instant) {
        armedAlarmAt = null
        phoneAlarmFiredFor = at
        val firedPlan = lastPlan?.takeIf { it.wakeAt == at }
        firings.add(Firing(at, firedPlan?.mode))
        if (firedPlan == null) return
        if (firedAlarmIsWakeAlarm(firedPlan.mode, at, morningAlarmAt)) {
            wakeAlarmFiredAt = at
        } else {
            napAlarmsUsed = (napAlarmsUsed + 1).coerceAtMost(MAX_NAP_ALARMS)
            lastNapAlarmFiredAt = at
        }
    }

    /**
     * NightOrchestrator.firedAlarmIsWakeAlarm - J2 must-fix 2 REVERTS this mirror back to H8's original exact
     * equality, matching the production function's own revert: a NAP-mode firing is the wake alarm only when it
     * fires at EXACTLY the latched morning alarm time. See NightOrchestrator.kt's own doc for why the J1.2
     * window this used to mirror was itself a regression (a genuine mid-night nap landing inside the window by
     * coincidence, misattributed as the wake alarm) rather than a real fix for anything - delivery jitter can
     * never move [firedFor], which is read from the alarm's own armed-for extra, not from when it was delivered.
     */
    private fun firedAlarmIsWakeAlarm(mode: AlarmMode, firedFor: Instant, morningAlarmAt: Instant?): Boolean =
        mode != AlarmMode.NAP || firedFor == morningAlarmAt

    /**
     * NightOrchestrator.armPhoneAlarmIfNeeded: a null wake time cancels, an instant that already fired or is
     * not in the future is left alone, anything else is (re-)armed - EXCEPT [phoneAlarmFiredForAtStart]'s own
     * J1.3 second line of defence: a wakeAt strictly after it but still within minAlarmLead is refused too, not
     * just an exact match. [phoneAlarmFiredForAtStart] is this PENDING tick's own snapshot, taken at
     * [PendingTick.startedAt] - see [runTick]'s own doc for why a stale-snapshot tick's arm step is stale here
     * too, and why that is the accepted, BOUNDED cost the "rings twice" regression test below documents rather
     * than hides.
     */
    private fun armPhoneAlarmIfNeeded(plan: AlarmPlan, phoneAlarmFiredForAtStart: Instant?, now: Instant) {
        val wakeAt = plan.wakeAt
        if (wakeAt == null) {
            armedAlarmAt = null
            return
        }
        if (wakeAt == phoneAlarmFiredForAtStart) return
        if (!wakeAt.isAfter(now)) return
        if (phoneAlarmFiredForAtStart != null && wakeAt.isAfter(phoneAlarmFiredForAtStart) &&
            !wakeAt.isAfter(phoneAlarmFiredForAtStart.plus(config.minAlarmLead))
        ) {
            return
        }
        armedAlarmAt = wakeAt
        armings.add(now to wakeAt)
    }

    /** NightOrchestrator.latchMorningAlarmAt: only a FULL_CYCLES or DEADLINE_ONLY plan sets the night's morning alarm time, and a null wakeAt never erases it (H8). */
    private fun latchMorningAlarmAt(previous: Instant?, plan: AlarmPlan): Instant? = when (plan.mode) {
        AlarmMode.FULL_CYCLES, AlarmMode.DEADLINE_ONLY -> plan.wakeAt ?: previous
        AlarmMode.NAP, AlarmMode.FINISHED -> previous
    }

    /** BandDataSimulator.buildSimulatedSegments: each mark opens a segment that runs to the next mark, or to [now] for the most recent one. */
    private fun segmentsAt(now: Instant): List<SleepSegment> =
        marks.indices.mapNotNull { index ->
            val mark = marks[index]
            val end = marks.getOrNull(index + 1)?.at ?: now
            if (!end.isAfter(mark.at)) {
                null
            } else {
                SleepSegment(mark.at, end, if (mark.asleep) SegmentKind.LIGHT else SegmentKind.AWAKE)
            }
        }

    /** A readable trace of the whole night, for a failure message that says what the sequence actually did. */
    fun trace(): String = buildString {
        plans.forEach { appendLine("plan ${it.mode} wakeAt=${it.wakeAt}") }
        firings.forEach { appendLine("fired ${it.firedFor} attributedTo=${it.mode}") }
        pendingTick?.let { appendLine("pending tick started=${it.startedAt} commitAt=${it.commitAt} plan=${it.plan.mode} wakeAt=${it.plan.wakeAt}") }
    }
}
