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
 *
 * WHAT THIS HARNESS DOES NOT MODEL (J5, reviewer-reported, 2026-09-21). Written down so nobody reads a green
 * suite here as more assurance than it is. Every item below is a KNOWN, deliberate gap, not a bug to fix in
 * passing; each one is a state or an interleaving this harness cannot express at all, so no test written
 * against it can ever challenge behaviour that depends on one.
 *
 *  - ARMING ALWAYS SUCCEEDS. [armPhoneAlarmIfNeeded] here has no failure path: there is no failed arm, no
 *    revoked exact-alarm permission, and no failed fired-marker read. That is exactly the state needed to
 *    challenge J4's own reverted stale-re-arm guard (see the J5 record in NightOrchestrator.kt), which is why
 *    that guard's counterexamples had to be traced by hand rather than replayed here.
 *  - FIRING IS ATOMIC. [fireAlarm] does the firing, the fired-marker write, attribution and the nudge creation
 *    in one indivisible step. In production those are four separately observable steps on a different thread,
 *    and a tick's own commit can interleave between any two of them - the whole J3/J4/J5 nudge family lives in
 *    exactly those gaps.
 *  - THE NUDGE'S PRE-CHECK NEVER RUNS. L1 (owner decision, 2026-09-21) narrows what used to be a wider gap
 *    here, "THE NUDGE IS NEVER DISPATCHED": a pending nudge now actually FIRES in [advanceTo], recorded in
 *    [nudgeFirings], and arms the next one exactly as PhoneAlarmReceiver does, which is what lets a test say
 *    anything at all about the repeating chain. What is still missing is its H7.3 pre-check
 *    (OutOfBedPreNudgeCheck.kt, with its own re-sync and fail-open rule), which never runs here - so a test
 *    can say a nudge rang, never that a genuinely-asleep owner's nudge was cancelled two minutes before it.
 *  - THE NUDGE CHAIN HAS NO END HERE. Production ends it when the night ends: NightController.endNight, from
 *    "I'm awake" or the app's own end-night action, cancels the nudge, its pre-check and its record. This
 *    harness models neither owner action, and it does not model G1's FINISHED bookkeeping clearing night
 *    state either (which in production stops the chain a further nudge later, since PhoneAlarmReceiver needs
 *    a night state to re-arm). Nudges therefore repeat here for as long as a test keeps advancing the clock,
 *    which is enough to pin the repeat itself and nothing about how it stops.
 *  - BATTERY LOSS DOES NOT REBOOT. [batteryDies] drops the armed alarm and the tick schedule, and a later
 *    [openApp] resumes from there - `BootReceiver.handleBoot` never runs, so nothing here exercises boot
 *    restoration, including J5's own overdue-nudge restore.
 *  - SYNC FAILURE IS A BOOLEAN. `syncFails` collapses `resolveSyncOutcome`'s real freshness classification (an
 *    outright failure, a success carrying only stale samples, an export file that did not advance) into one
 *    flag. The guard it feeds cannot tell the three apart here, though the UI and the night log do.
 *  - INSTANTS KEEP FULL PRECISION. Everything here stays a [java.time.Instant]; production round-trips through
 *    epoch MILLISECONDS (the alarm intent's own extra, the persisted instant files), so sub-millisecond
 *    differences this harness can represent do not survive the real path.
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
     *
     * J4: [segments] added - this tick's own segment snapshot, needed at commit time to recompute the SAME
     * [SleepState] [cancelNudgeIfSupersededByNap] mirrors NightOrchestrator computing from (`detectSleepState`
     * on this tick's own segments and its own `decisionNow`/[startedAt] - never the commit instant).
     */
    private data class PendingTick(val startedAt: Instant, val commitAt: Instant, val plan: AlarmPlan, val segments: List<SleepSegment>)

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

    /**
     * J4: the out-of-bed nudge's own pending fire instant, OutOfBedNudgeStore's mirror - set to
     * `deliveredAt + config.outOfBedDelay` unconditionally by every firing ([fireAlarm], D4's "every alarm
     * that fires arms its own fresh nudge"), and cleared by [cancelNudgeIfSupersededByNap] when a fresh nap
     * supersedes it. Null means no nudge is currently pending (including "cancelled").
     *
     * L1: [fireNudge] now sets this too, so a nudge firing leaves the NEXT nudge pending rather than nothing.
     */
    var pendingNudgeAt: Instant? = null
        private set

    /** Every alarm that rang this night, in order. The out-of-bed nudge is not one of these - see [nudgeFirings]. */
    val firings = mutableListOf<Firing>()

    /**
     * L1: every out-of-bed nudge that actually rang this night, in order, by the instant it was armed for.
     * Kept apart from [firings] deliberately: those carry the plan alarm's own attribution (wake versus nap),
     * which a nudge firing has none of and deliberately never touches - see
     * PhoneAlarmReceiver.firedAlarmRecordsPlanBookkeeping.
     */
    val nudgeFirings = mutableListOf<Instant>()

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

    /**
     * Runs every tick and every alarm firing due up to and including [at]. [syncDuration] and [syncFails] apply
     * to any SCHEDULED tick that becomes due during this call - see the class doc.
     *
     * J5 (reviewer note, 2026-09-21) ADDS [alarmDelivery]: how much later than its own armed-for instant an
     * alarm firing during this call is actually DELIVERED to the receiver. Zero by default, which is how every
     * test before J5 ran and still runs. It exists because production and this harness used to disagree about
     * one observable: production's nudge is armed at the RECEIVER's own current time plus `outOfBedDelay`
     * (PhoneAlarmReceiver.armOutOfBedNudge takes `now`, sampled when the intent actually arrives), while this
     * harness armed it from the instant the alarm was armed FOR - so a 06:30 alarm delivered at 06:32 meant a
     * 06:45 nudge here and a 06:47 nudge on the phone. Attribution is deliberately NOT affected by lateness, on
     * either side: production reads `EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI`, the armed-for instant carried on
     * the intent, never the delivery time (see NightOrchestrator.firedAlarmIsWakeAlarm's own J2 must-fix 2 doc).
     */
    fun advanceTo(at: String, syncDuration: Duration = Duration.ZERO, syncFails: Boolean = false, alarmDelivery: Duration = Duration.ZERO) {
        advanceTo(instant(at), syncDuration, syncFails, alarmDelivery)
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

    private fun advanceTo(at: Instant, syncDuration: Duration = Duration.ZERO, syncFails: Boolean = false, alarmDelivery: Duration = Duration.ZERO) {
        while (true) {
            // Ties (a pending tick's own commit landing at the exact instant an armed alarm fires) resolve
            // commit-first, deterministically - see the class doc's own note on what that is built to express.
            val dueCommit = pendingTick?.commitAt?.takeIf { !it.isAfter(at) }
            // J5: an alarm becomes due at its armed-for instant plus [alarmDelivery], and [fireAlarm] is handed
            // BOTH instants - the one it was armed for (what attribution keys on) and the one it was actually
            // delivered at (what the nudge is measured from), matching production.
            val armedFor = armedAlarmAt
            val dueFire = armedFor?.plus(alarmDelivery)?.takeIf { !it.isAfter(at) }
            val dueTick = nextTickAt?.takeIf { !it.isAfter(at) }
            // L1: the nudge is armed through AlarmManager exactly like the plan alarm, so it is delivered on
            // the same terms, [alarmDelivery] included - and it fires on its own schedule, independently of
            // whatever is in the plan's own slot.
            val nudgeFor = pendingNudgeAt
            val dueNudge = nudgeFor?.plus(alarmDelivery)?.takeIf { !it.isAfter(at) }
            val due = listOfNotNull(dueCommit, dueFire, dueTick, dueNudge).minOrNull() ?: return
            when {
                due == dueCommit -> commitPendingTick()
                due == dueFire -> fireAlarm(checkNotNull(armedFor), due)
                due == dueTick -> runTick(due, syncDuration, syncFails)
                else -> fireNudge(checkNotNull(nudgeFor), due)
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
        pendingTick = PendingTick(at, at.plus(syncDuration), plan, segments)
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
     * The pending tick's own arm/nudge-supersede/latch/save/reschedule, all at once.
     *
     * J3: the arm step reads the LIVE [phoneAlarmFiredFor] field (a firing that happened while this tick was
     * pending IS visible to it now) rather than a tick-start snapshot - see [armPhoneAlarmIfNeeded]'s own doc
     * for why.
     *
     * J4: [cancelNudgeIfSupersededByNap] added, mirroring NightOrchestrator's own call right after arming -
     * see its own doc for the fix this pins (a nap's OWN freshly-armed nudge no longer superseded by that same
     * nap's own already-fired marker match).
     */
    private fun commitPendingTick() {
        val pending = checkNotNull(pendingTick) { "commitPendingTick called with nothing pending" }
        pendingTick = null
        val napAlarmArmed = armPhoneAlarmIfNeeded(pending.plan, pending.startedAt)
        cancelNudgeIfSupersededByNap(pending.plan, pending.segments, pending.startedAt, napAlarmArmed)
        // J5: mirrors NightOrchestrator's own call right after the supersede check, and like it reads [lastPlan]
        // BEFORE the assignment below overwrites it.
        rearmNudgeIfNapCancelledWhileAwake(pending.plan, pending.segments, pending.startedAt)
        morningAlarmAt = latchMorningAlarmAt(morningAlarmAt, pending.plan)
        lastPlan = pending.plan
        plans.add(pending.plan)
        // FIX2: booked from this tick's own decisionNow ([PendingTick.startedAt]), never its later commit instant.
        nextTickAt = nextSyncDelay(pending.plan, pending.startedAt, config)?.let { pending.startedAt.plus(it) }
    }

    /**
     * NightOrchestrator.cancelNudgeIfSupersededByNap / napSupersedesPendingNudge, reimplemented here (the engine
     * module cannot import the app module's `internal` originals - see this class's own header). Recomputes the
     * SAME [SleepState] this tick's own [plan] was built from, from [segments] and this tick's own [now]
     * (`decisionNow`/[PendingTick.startedAt] - never the commit instant, matching production).
     *
     * J5 must-fix: mirrors the added "[pendingNudgeAt] strictly before the nap's own target" test - only a
     * nudge that would ring MID-nap is superseded, never one due at or after the nap itself, which can only be
     * that nap's own fresh D4 nudge or something later. See NightOrchestrator.napSupersedesPendingNudge's own
     * J5 doc for the ordering this closes (a firing landing AFTER a genuinely successful arm, which
     * [napAlarmArmed] alone cannot tell apart from an ordinary supersession).
     */
    private fun cancelNudgeIfSupersededByNap(plan: AlarmPlan, segments: List<SleepSegment>, now: Instant, napAlarmArmed: Boolean) {
        val napWakeAt = plan.wakeAt ?: return
        val nudgeAt = pendingNudgeAt ?: return
        val sleepState = detectSleepState(normalizeSegments(segments, now, config))
        val supersedes = plan.mode == AlarmMode.NAP && sleepState == SleepState.ASLEEP && napAlarmArmed &&
            nudgeAt.isBefore(napWakeAt)
        if (supersedes) pendingNudgeAt = null
    }

    /**
     * NightOrchestrator.rearmNudgeIfNapCancelledWhileAwake / awakeNapCancellationNeedsNudge, reimplemented here
     * (same reason as every other mirror in this class). A tick that cancels a rule 7 nap because the AWAKE
     * branch gave it nothing to arm, with no nudge left pending, puts one back at [now] + outOfBedDelay - see
     * NightOrchestrator's own J5 doc for the composed silence this closes.
     */
    private fun rearmNudgeIfNapCancelledWhileAwake(plan: AlarmPlan, segments: List<SleepSegment>, now: Instant) {
        val sleepState = detectSleepState(normalizeSegments(segments, now, config))
        val needed = plan.mode == AlarmMode.NAP && plan.wakeAt == null && lastPlan?.wakeAt != null &&
            sleepState == SleepState.AWAKE && pendingNudgeAt == null
        if (needed) pendingNudgeAt = now.plus(config.outOfBedDelay)
    }

    /**
     * PhoneAlarmReceiver.onReceive: the armed alarm rings, records the instant it was scheduled for
     * unconditionally (phoneAlarmFiredFor - see PhoneAlarmReceiver.markPhoneAlarmFired), and is attributed to
     * either the main wake alarm or a nap from the plan COMMITTED at that moment (recordWakeOrNapFired -
     * attribution is skipped when that plan's own wakeAt no longer matches, which this harness can still
     * reach via a mismatched commit racing a firing, even though none of the regression tests below need it).
     * D4: every firing arms a fresh out-of-bed nudge unconditionally, [config.outOfBedDelay] later - overwriting
     * whatever was pending before, nap or not.
     *
     * J5 (reviewer note, 2026-09-21) SPLITS the one instant this used to take into two, correcting a real
     * fidelity bug. [firedFor] is the instant the alarm was ARMED for, which is what every piece of
     * PhoneAlarmReceiver's own bookkeeping keys on (it reads `EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI`, carried on
     * the intent since the moment it was armed, never the delivery time). [deliveredAt] is when the receiver
     * actually ran, and the nudge is measured from THAT, matching `armOutOfBedNudge(context, state, now)`. The
     * two are equal unless a caller passes `alarmDelivery`; before this they were equal by construction, so a
     * 06:30 alarm delivered at 06:32 armed its nudge for 06:45 here and 06:47 on the phone.
     */
    private fun fireAlarm(firedFor: Instant, deliveredAt: Instant) {
        armedAlarmAt = null
        phoneAlarmFiredFor = firedFor
        pendingNudgeAt = deliveredAt.plus(config.outOfBedDelay)
        val firedPlan = lastPlan?.takeIf { it.wakeAt == firedFor }
        firings.add(Firing(firedFor, firedPlan?.mode))
        if (firedPlan == null) return
        if (firedAlarmIsWakeAlarm(firedPlan.mode, firedFor, morningAlarmAt)) {
            wakeAlarmFiredAt = firedFor
        } else {
            napAlarmsUsed = (napAlarmsUsed + 1).coerceAtMost(MAX_NAP_ALARMS)
            lastNapAlarmFiredAt = firedFor
        }
    }

    /**
     * L1 (owner decision, 2026-09-21): PhoneAlarmReceiver.onReceive for the out-of-bed nudge's OWN firing -
     * a function this harness had no need of before, because a fired nudge used to leave [pendingNudgeAt]
     * null for the rest of the night. It records that the nudge rang and arms the NEXT one
     * [EngineConfig.outOfBedDelay] after the instant it was actually DELIVERED at, the same rule [fireAlarm]
     * follows and for the same reason (`armOutOfBedNudge(context, state, now)` samples the receiver's own
     * clock, not what the alarm was armed for).
     *
     * Deliberately touches NONE of [phoneAlarmFiredFor], [wakeAlarmFiredAt], [napAlarmsUsed],
     * [lastNapAlarmFiredAt] or [firings]. Those four facts and their wake-versus-nap attribution belong to the
     * phone alarm's own slot and never to the nudge - the one boundary
     * PhoneAlarmReceiver.firedAlarmRecordsPlanBookkeeping still holds after L1, and the reason a nudge firing
     * can never be mistaken by a later tick for a plan alarm having rung.
     */
    private fun fireNudge(firedFor: Instant, deliveredAt: Instant) {
        nudgeFirings.add(firedFor)
        pendingNudgeAt = deliveredAt.plus(config.outOfBedDelay)
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
     * this check (a now-removed parameter this function's must-fix-4-era signature carried, itself
     * [PendingTick.commitAt], the tick's own post-sync commit instant, rather than its
     * [tickStartedAt]/decisionNow - reworded to plain text since that parameter was deleted in the same commit
     * that deleted this whole must-fix 4 mechanism, and does not name any parameter this function still has) to
     * stop a stale-plan tick from re-arming a target that had already fired during its own sync. That closed the
     * duplicate-ring bug, but opened the opposite
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
     *
     * J4 briefly ADDED a second, independent guard here, mirroring NightOrchestrator.shouldRefuseStaleRearm
     * ([wakeAt] unchanged from the last committed plan's own `wakeAt` AND a fresh real-clock read at commit
     * time having already reached it). J5 (owner-approved revert, 2026-09-21) REMOVED it, here and in
     * production alike: the "an unchanged target can never be a pull-forward recovery" premise it rested on is
     * false (minute rounding and deadline clipping both make a genuine recovery recompute the SAME instant),
     * and on a deadline-capped target the wrong refusal leaves the night permanently silent. See the J5 record
     * above `shouldArmPhoneAlarm` in NightOrchestrator.kt for the full reasoning and for what a correct version
     * of the idea would need instead. The `previousWakeAt`/`armTimeNow` parameters this guard needed went with
     * it, and so did its own `J4 replay` test in `TickScheduleRaceTest.kt`.
     *
     * J4 nudge must-fix (owner-reported, 2026-09-21) also changes what this function RETURNS, not just what it
     * arms: it is now `Boolean` (was `Unit`), mirroring NightOrchestrator.armPhoneAlarmIfNeeded's own FIX4/J4
     * contract - true only when THIS call freshly armed [wakeAt], never merely on an already-fired marker match
     * (see the exact-match branch below). [cancelNudgeIfSupersededByNap]'s own guard reads this, exactly like
     * the production `napAlarmArmed` - see `J4 nudge replay` below, which fails without this change (a nap's
     * own already-fired match used to supersede that SAME nap's own freshly-armed nudge).
     */
    private fun armPhoneAlarmIfNeeded(plan: AlarmPlan, tickStartedAt: Instant): Boolean {
        val wakeAt = plan.wakeAt
        if (wakeAt == null) {
            armedAlarmAt = null
            return false
        }
        // J4 nudge must-fix: false, not true - an exact marker match still never re-arms, but no longer tells
        // the nudge guard that a FRESH nap was armed this tick (see this function's own J4 nudge doc above).
        if (wakeAt == phoneAlarmFiredFor) return false
        if (!wakeAt.isAfter(tickStartedAt)) return false
        armedAlarmAt = wakeAt
        armings.add(tickStartedAt to wakeAt)
        return true
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
        nudgeFirings.forEach { appendLine("nudge fired $it") }
        appendLine("pending nudge $pendingNudgeAt")
        pendingTick?.let { appendLine("pending tick started=${it.startedAt} commitAt=${it.commitAt} plan=${it.plan.mode} wakeAt=${it.plan.wakeAt}") }
    }
}
