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
 *  - the dead-band/stale-sync freeze: NightOrchestrator.shouldKeepPreviousPlan
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
 *
 * S2/SHOULD-FIX-2 (reviewer note, 2026-09-21) ADDS a sync-FAILURE mode, threaded through the same
 * [advanceTo]/[openApp] calls as [syncFails]: a tick that becomes due during such a call mirrors
 * `resolveSyncOutcome`'s two not-ok branches (an outright failure, or a success that turns out stale) by
 * feeding [shouldKeepPreviousPlan] `syncOk = false` and, when it does not freeze, recomputing from
 * [lastSyncedSegments] (the last segments a SUCCESSFUL sync actually saw) rather than this call's own live
 * [segmentsAt] - exactly like `SyncOutcome.segments` staying `state.lastSegments` on a failed or stale read.
 * The previous round declined this, arguing branch coverage of the guard was already complete and a
 * sequence-level mirror would just duplicate an app-layer decision - the reviewer reverted must-fix 1 and
 * must-fix 3 in turn and measured zero sequence-level test failures for either, despite both being live
 * defects in a guard with full branch coverage; branch coverage cannot catch a guard whose INPUTS are wrong.
 * This harness already mirrors [shouldKeepPreviousPlan] (added here for the first time this round, see its
 * own doc below), [armPhoneAlarmIfNeeded], [firedAlarmIsWakeAlarm] and [latchMorningAlarmAt], so the
 * duplication objection is spent - the last several rounds each had to edit these mirrors in lockstep anyway.
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
     *
     * J3 (owner-reported, 2026-09-21) REMOVES the [phoneAlarmFiredFor] snapshot this used to carry
     * (`phoneAlarmFiredForAtStart`). J2 must-fix 4 kept that snapshot so a firing that happened WHILE this
     * tick was pending could not retroactively change what this tick's own arm decision saw - but that is
     * exactly the mechanism must-fix 4 turned out to get wrong: see [armPhoneAlarmIfNeeded]'s own J3 doc for
     * why the arm step now deliberately reads the LIVE [phoneAlarmFiredFor] field at commit time instead. The
     * PLAN itself (already computed and stored in [plan] here) still reflects only this tick's own [startedAt]
     * snapshot, same as before - only the arm step's own fired-marker read moved.
     */
    private data class PendingTick(val startedAt: Instant, val commitAt: Instant, val plan: AlarmPlan)

    private val marks = mutableListOf<SleepMark>()
    private var nextTickAt: Instant? = null
    private var armedAlarmAt: Instant? = null
    private var pendingTick: PendingTick? = null
    private var phoneAlarmFiredFor: Instant? = null
    private var lastNapAlarmFiredAt: Instant? = null
    private var morningAlarmAt: Instant? = null
    private var started = false

    /** S2: the segments the last SUCCESSFUL sync actually saw - what a failed/stale sync's own tick recomputes from, mirroring `SyncOutcome.segments` staying `state.lastSegments` on either of `resolveSyncOutcome`'s not-ok branches. */
    private var lastSyncedSegments: List<SleepSegment> = emptyList()

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
        runTick(instant(at), Duration.ZERO, syncFails = false)
    }

    /** The Debug screen's "Asleep" toggle flipped to asleep at [at]; unlike before J1.4 this does NOT itself tick - see the class doc. */
    fun markAsleep(at: String) {
        mark(asleep = true, at = at)
    }

    /** The Debug screen's "Asleep" toggle flipped to awake at [at] - the owner marking "I am not asleep any more". Does not itself tick - see the class doc. */
    fun markAwake(at: String) {
        mark(asleep = false, at = at)
    }

    /**
     * D8/J3 (owner-reported, 2026-09-21): models total power loss - the phone's battery dies, taking every
     * AlarmManager alarm with it (whatever was armed, per [armedAlarmAt], simply disappears without ever
     * firing) and NightService along with it (no more ticks run on the ordinary schedule, per [nextTickAt],
     * until something starts the app again). Mirrors BootReceiver.handleBoot's own precondition, not its body -
     * BootReceiver correctly refuses to re-arm a target already in the past, which is not the bug under test;
     * what matters here is the ordinary tick that runs once the app is alive again, driven by a later
     * [openApp] call. Nothing else about state (segments, [lastPlan], [phoneAlarmFiredFor]) changes - exactly
     * like a real power loss, which is not a tick, a firing, or any of PhoneAlarmReceiver's own bookkeeping.
     */
    fun batteryDies() {
        armedAlarmAt = null
        nextTickAt = null
    }

    /** Runs every tick and every alarm firing due up to and including [at]. [syncDuration] and [syncFails] apply to any SCHEDULED tick that becomes due during this call - see the class doc. */
    fun advanceTo(at: String, syncDuration: Duration = Duration.ZERO, syncFails: Boolean = false) {
        advanceTo(instant(at), syncDuration, syncFails)
    }

    /**
     * NightController.runImmediateTick (the Debug screen's own "check now", or simply opening the app while a
     * night is in progress): runs a tick right at [at] regardless of whether one is due on the ordinary
     * schedule. First flushes anything already due up to [at] on the ordinary schedule (zero sync duration,
     * always a successful sync - only the app-open tick itself uses [syncDuration]/[syncFails]), exactly like
     * a real immediate tick can still race an already in-flight scheduled one.
     */
    fun openApp(at: String, syncDuration: Duration = Duration.ZERO, syncFails: Boolean = false) {
        val whenAt = instant(at)
        advanceTo(whenAt)
        runTick(whenAt, syncDuration, syncFails)
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

    private fun advanceTo(at: Instant, syncDuration: Duration = Duration.ZERO, syncFails: Boolean = false) {
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
                else -> runTick(due, syncDuration, syncFails)
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
     * S2 (reviewer note, 2026-09-21) ADDS [syncFails], mirroring NightOrchestrator.shouldKeepPreviousPlan: a
     * failed sync first asks whether to freeze on [lastPlan] unchanged rather than recompute at all, and when
     * it does not freeze, recomputes from [lastSyncedSegments] (frozen at the last SUCCESSFUL sync) rather
     * than this tick's own live [segmentsAt] - a successful sync updates that snapshot before computing, a
     * failed one leaves it untouched, exactly like `resolveSyncOutcome`'s two branches.
     *
     * A tick that starts before an alarm fires but commits after it carries a plan computed WITHOUT knowing
     * that firing happened (its own snapshot predates it) - the same target it already knew about is still "in
     * the future" relative to its own [at], so nothing about the PLAN itself catches this. [armPhoneAlarmIfNeeded]
     * is what actually stops a re-arm here - see its own J3 doc for what changed and why, and
     * `TickScheduleRaceTest.kt`'s own tests for the two scenarios this is built to reproduce and resolve.
     */
    private fun runTick(at: Instant, syncDuration: Duration, syncFails: Boolean) {
        check(pendingTick == null) { "a tick is already pending at ${pendingTick?.startedAt} - NightReplay does not model overlapping ticks" }
        nextTickAt = null
        val syncOk = !syncFails
        val segments = if (syncOk) segmentsAt(at).also { lastSyncedSegments = it } else lastSyncedSegments
        val plan = if (shouldKeepPreviousPlan(syncOk, lastPlan, phoneAlarmFiredFor, at)) {
            checkNotNull(lastPlan)
        } else {
            computeAlarmPlan(
                segments, setting, at, morningAlarmAt, testZone, config, wakeAlarmFiredAt, napAlarmsUsed,
                lastNapAlarmFiredAt, phoneAlarmFiredFor
            )
        }
        pendingTick = PendingTick(at, at.plus(syncDuration), plan)
        if (syncDuration.isZero) commitPendingTick()
    }

    /**
     * NightOrchestrator.shouldKeepPreviousPlan (mirrored here for the first time this round - S2/SHOULD-FIX-2):
     * whether this tick should skip re-planning entirely and keep [previousPlan] unchanged rather than feeding
     * a failed/stale sync's own frozen segments through [computeAlarmPlan] against this tick's own moving
     * [now]. True exactly when the sync did not succeed AND [previousPlan] already carries a real, still-
     * pending armed alarm (`wakeAt` non-null, not yet matching [phoneAlarmFiredFor], and still strictly after
     * [now] - J2 must-fix 1's own correction, without which this freezes forever once `now` catches up to a
     * spent `wakeAt` nothing ever fired). See NightOrchestrator.kt's own doc for the full history (J1.5, J2
     * must-fix 1).
     */
    private fun shouldKeepPreviousPlan(syncOk: Boolean, previousPlan: AlarmPlan?, phoneAlarmFiredFor: Instant?, now: Instant): Boolean {
        val wakeAt = previousPlan?.wakeAt ?: return false
        return !syncOk && wakeAt != phoneAlarmFiredFor && wakeAt.isAfter(now)
    }

    /**
     * The pending tick's own arm/latch/save/reschedule, all at once.
     *
     * J3: the arm step reads the LIVE [phoneAlarmFiredFor] field (a firing that happened while this tick was
     * pending IS visible to it now) rather than a tick-start snapshot - see [armPhoneAlarmIfNeeded]'s own doc
     * for why.
     */
    private fun commitPendingTick() {
        val pending = checkNotNull(pendingTick) { "commitPendingTick called with nothing pending" }
        pendingTick = null
        armPhoneAlarmIfNeeded(pending.plan, pending.startedAt)
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
     * not in the future is left alone, anything else is (re-)armed.
     *
     * J3 (owner-reported, 2026-09-21) CORRECTS J2 must-fix 4. Must-fix 4 re-read the real CLOCK right before
     * this check (here, [armTimeNow] used to be [PendingTick.commitAt], the tick's own post-sync commit
     * instant, rather than its [tickStartedAt]/decisionNow) to stop a stale-plan tick from re-arming a target
     * that had already fired during its own sync. That closed the duplicate-ring bug, but opened the opposite
     * one: D8's pull-forward target sits only [EngineConfig.minAlarmLead] (2 min, plus up to a minute of
     * rounding) past decisionNow, while a real sync can itself cost close to that much - so whenever a sync
     * outlives the very lead it produced, the commit-time clock has already caught up to (or passed) a target
     * that has NEVER FIRED, and this refused to arm it. Unlike a duplicate ring, that failure does not
     * self-heal: the next tick recomputes the identical shape (same dead sync, same near-target lead) and
     * refuses again, forever, until the owner intervenes - see NightOrchestrator.kt's own J3 doc for the full
     * traced sequence (phone battery dies, boots hours later with the band also dead) this fixes.
     *
     * The corrected guard re-samples the FIRED MARKER instead of the clock: [phoneAlarmFiredFor] here is read
     * LIVE (the class field, which [fireAlarm] updates the instant a firing happens, even while this tick was
     * still pending) rather than a tick-start snapshot, and the clock both checks compare against goes back to
     * being [tickStartedAt] (decisionNow) throughout, matching NightOrchestrator's own [now] parameter. The
     * original must-fix 4 scenario (a target that rang DURING this tick's own sync) now resolves at the first
     * check instead: the live [phoneAlarmFiredFor] already equals [wakeAt] exactly, so nothing is re-armed and
     * there is still exactly one ring - see `J2 must-fix 4 replay` below, unchanged in what it asserts. A
     * target that has never fired is never refused, whatever the sync costs - see `J3 replay` below, which
     * fails without this fix.
     */
    private fun armPhoneAlarmIfNeeded(plan: AlarmPlan, tickStartedAt: Instant) {
        val wakeAt = plan.wakeAt
        if (wakeAt == null) {
            armedAlarmAt = null
            return
        }
        if (wakeAt == phoneAlarmFiredFor) return
        if (!wakeAt.isAfter(tickStartedAt)) return
        armedAlarmAt = wakeAt
        armings.add(tickStartedAt to wakeAt)
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
