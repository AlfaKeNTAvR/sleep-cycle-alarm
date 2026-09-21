package com.nikita.sleepcycle.night

// File purpose: runNightTick - the one function that runs a whole sync-plan-alarm cycle, per app-spec.md.
// This is the app's main call site into the engine's plan computation. J5 CORRECTION (reviewer-reported,
// 2026-09-21): the only OTHER computeAlarmPlan call site in the app is NightController.startNight (the night's
// own first plan), not NightUiSupport.kt, which this line named for several rounds and which has none - it
// calls the engine's UI helpers (listWakeOptions and friends), never computeAlarmPlan.
// The entire transaction runs under withNightTransactionLock so a service tick, an immediate UI tick, and
// endNight can never interleave. D1/D2: the band is a sensor only now - every tick arms the phone alarm at
// plan.wakeAt, and no alarm command is ever sent to the band.
//
// FIX1 (owner-reported, 2026-09-21): the clock is now re-sampled ONCE, inside the lock, and that single
// `decisionNow` drives the data path (segment build), the plan, phone-alarm arming, nudge supersession,
// lastSyncAt and next-tick scheduling alike - see runNightTickLocked's own doc for the bug this replaces
// (an older tick committing after a newer one, on its own stale pre-lock `now`) and the one accepted
// trade-off it carries on a real sync (up to ~2 min of real band I/O no longer gets a fresher post-sync
// clock read for the plan - bounded by the sync's own worst case, and worth it to remove the data-integrity
// bug). FIX1 also makes this function the one publisher of a committed tick's state - see publishNightState's
// own call at the bottom of runNightTickLocked. FIX2 is the next-tick scheduling half - see nextTickAt.
// FIX4 is napSupersedesPendingNudge's own tightened guard - see its own doc.

import android.content.Context
import android.util.Log
import com.nikita.sleepcycle.alarm.cancelOutOfBedAlarm
import com.nikita.sleepcycle.alarm.cancelPhoneAlarm
import com.nikita.sleepcycle.alarm.alarmLabelFor
import com.nikita.sleepcycle.alarm.scheduleOutOfBedAlarm
import com.nikita.sleepcycle.alarm.schedulePhoneAlarm
import com.nikita.sleepcycle.bridge.BandDataResult
import com.nikita.sleepcycle.bridge.checkDataFreshness
import com.nikita.sleepcycle.bridge.clipSegmentsToNightStart
import com.nikita.sleepcycle.bridge.syncAndReadBandData
import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.AlarmPlan
import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.engine.SleepSegment
import com.nikita.sleepcycle.engine.SleepState
import com.nikita.sleepcycle.engine.computeAlarmPlan
import com.nikita.sleepcycle.engine.detectSleepState
import com.nikita.sleepcycle.engine.nextSyncDelay
import com.nikita.sleepcycle.engine.normalizeSegments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private const val LOG_TAG = "NightOrchestrator"

/** The zone to interpret the plan's wake time in, read fresh on every call so a timezone change takes effect on the next tick. */
private fun currentZone(): ZoneId = ZoneId.systemDefault()

/**
 * D1: the guard all three arming paths SHARE - [armPhoneAlarmIfNeeded] (every tick),
 * `BootReceiver.handleBoot` (after a reboot) and `startNight` (the very first arm). Never arms a null alarm,
 * one at or before [now] (Android fires a past exact alarm immediately), or one that already fired
 * ([phoneAlarmFiredFor]).
 *
 * J5 CORRECTION (reviewer-reported, 2026-09-21): this doc used to call itself "the ONE guard ... so none of
 * the three can drift out of sync with the other two". They HAVE drifted, deliberately and correctly, and the
 * claim was reading as a promise the code does not keep. What is actually true is weaker and still worth
 * having: these three conditions are shared by all three paths, and no path may arm anything this function
 * would refuse. Beyond that each path adds what it alone needs - [armPhoneAlarmIfNeeded] also re-reads the
 * fired marker live at commit time (J3) and cancels a now-null target, which the boot path must NOT do (it has
 * no previous plan of its own to compare against and no tick in flight to race). A future guard that belongs
 * to only one path belongs in that path, not here.
 */
fun shouldArmPhoneAlarm(wakeAt: Instant?, now: Instant, phoneAlarmFiredFor: Instant?): Boolean =
    wakeAt != null && wakeAt.isAfter(now) && wakeAt != phoneAlarmFiredFor

// J5 (owner-approved revert, 2026-09-21): `shouldRefuseStaleRearm` WAS TRIED HERE AND REVERTED. Do not re-add
// it in this shape. J4 put a second arming guard right before schedulePhoneAlarm, refusing to arm whenever this
// tick's own target equalled the previous plan's target (NightState.lastPlan's own wakeAt) and a freshly re-read
// real clock had already reached it. Its safety argument was that an unchanged target can never be a D8
// pull-forward recovery, so the guard could only ever refuse a re-arm of something already armed. That argument
// is FALSE, and the guard can leave a whole night PERMANENTLY SILENT. Two counterexamples, both traced:
//
//  - Minute rounding. Uninterrupted light sleep from 23:00, five cycles, no deadline, raw target 06:30. D8's
//    own recovery computes `now + minAlarmLead` rounded up to the whole minute, so a recovery decision at
//    07:41:50 and another at 07:41:57 BOTH produce 07:44. If the first tick's own arm attempt failed, the
//    second tick's commit landing at 07:44:01 is refused by this guard with nothing armed on the phone.
//  - Deadline clipping. Light sleep from 00:00, four cycles, raw target 06:00, deadline 06:30. Recovery
//    decisions at 06:27:57 and at 06:28:57 both produce 06:30, the second one through deadline clipping. A
//    failed first arm plus a commit at 06:30:01 is refused, and the NEXT tick sees the deadline passed and
//    finishes the night - nothing having rung at all.
//
// The root error: a tick saves NightState.lastPlan whether arming SUCCEEDED or FAILED (see runNightTickLocked's
// own `newState` copy below - it takes `plan` unconditionally and discards armPhoneAlarmIfNeeded's own result),
// so equality with the saved plan is NOT evidence that an alarm exists on the phone. The one code path that
// actually makes an arm fail is schedulePhoneAlarm's own SecurityException (exact-alarm permission revoked) or
// a null AlarmManager - so a permission revoked and later re-granted mid-night reaches this directly. (A
// restored or quarantined state file does NOT reach it: rolling back to an older plan never touches
// AlarmManager, so whatever was armed is still live. Claimed in the original report, checked, and left out
// here rather than repeated.)
//
// The refusal window is narrow and precisely shaped: `decisionNow < wakeAt <= realNow`, i.e. the target falls
// INSIDE this tick's own sync, at most about 2 minutes wide. Only the DEADLINE path is fatal. For a full-cycles
// or nap target the same wrong refusal self-heals: the absent fired marker that made the refusal wrong is the
// same absent marker that keeps the spent-target check false, so the next tick computes a NEW target and arms
// it - a ring up to about 8 minutes late rather than silence. On a deadline-capped target the pull-forward caps
// AT the deadline, which is already past, and rule 1 has by then already ended the night, so there is no later
// tick left to recover. shouldKeepPreviousPlan's own stale-sync freeze refuses nothing by itself, but it
// GUARANTEES the unchanged-target half of this guard for exactly the window the guard fires in: the two
// together mean a night whose syncs are failing can never arm in the last 2 minutes before its own target.
//
// It also did not achieve what it was added for: a double delivery remains possible in the gap between the
// fired-marker check and schedulePhoneAlarm itself, because nothing synchronises a tick with PhoneAlarmReceiver.
// So it cost a permanent-silence path and bought little.
//
// The correct version of this idea needs POSITIVE EVIDENCE that an alarm is actually outstanding - a record
// written when arming SUCCEEDS and cleared when that alarm fires or is cancelled - never equality with a plan
// that is saved even when arming failed. The OTHER half of the J4 commit is KEPT, both of them genuine:
// PhoneAlarmFiredStore.kt's own temp-file-and-rename write, and the J4 change that returns false on a
// fired-marker match so a nap no longer cancels its own nudge (see armPhoneAlarmIfNeeded's own J4 doc below).

/**
 * F6: whether a just-fired real (non-test, non-nudge) alarm should be attributed to the actual wake alarm -
 * true for any mode other than NAP. Used by PhoneAlarmReceiver at FIRE time (never by armPhoneAlarmIfNeeded,
 * which no longer touches this bookkeeping at all - F2 superseded the whole arm-time counting path this used
 * to sit next to). Deliberately excludes NAP: a mid-night rule 7 nap must never itself count as "the wake
 * alarm fired" - G8 kept this exclusion (wakeAlarmFiredAt still means "the MAIN wake alarm, never a nap's own
 * firing") even though the nap cap itself no longer reads wakeAlarmFiredAt at all; its remaining job is G3's
 * guard on rule 7's sliding AWAKE nap (see WakeAlarm.kt's `napAlarm`).
 *
 * G8 SUPERSEDES F2: a NAP firing that is NOT the wake alarm always counts toward [NightState.napAlarmsUsed]
 * now, mid-night (rule 7) or post-wake alike - PhoneAlarmReceiver.recordWakeOrNapFired increments it whenever
 * this function returns false, with no further guard of its own (the old `firedAlarmIsPostWakeNap`, which
 * required the wake alarm to have already fired, is gone: it was the reason the cap could never engage on a
 * night that reaches naps without the main wake alarm ever ringing).
 *
 * H8 ADDS the second test: a firing at the night's own latched morning alarm time ([NightState.morningAlarmAt])
 * is the wake alarm whatever mode the plan that armed it carried. Since H8, a NAP plan can legitimately carry
 * the still-pending morning alarm as its own `wakeAt` (see WakeAlarm.kt's `awakeNapTarget`: lying awake in the
 * last few minutes before the alarm is rule 7, and rule 7 now defers to the morning alarm instead of re-arming
 * the phone past it), so the mode alone would attribute the night's real wake-up to a nap - spending one of the
 * two nap alarms on it and leaving wakeAlarmFiredAt unrecorded.
 *
 * J1.2 (owner-reported, 2026-09-21) widened H8's own test from EXACT equality to a WINDOW:
 * [morningAlarmAt] .. morningAlarmAt + (minAlarmLead + 1 min). The stated reasoning was that a NAP plan
 * carrying the morning alarm as its own `wakeAt` (`awakeNapTarget`) does not always fire at exactly that
 * instant - J1.1's own (now-fixed) bug could rearm the phone a minute or two LATER than `morningAlarmAt`, and
 * ordinary AlarmManager delivery jitter was assumed to be able to do the same regardless.
 *
 * J2 must-fix 2 (owner-reported, 2026-09-21) REVERTS J1.2 back to H8's original EXACT equality. The window's
 * premise about delivery jitter does not hold: [firedFor] is never read from when PhoneAlarmReceiver's intent
 * actually arrives, only from EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI, the instant the alarm was ARMED for
 * (verified directly in PhoneAlarmReceiver.recordRealAlarmFired - `Instant.ofEpochMilli(scheduledForMillis)`,
 * carried on the intent since the moment schedulePhoneAlarm ran). Delivery jitter can delay WHEN the receiver
 * runs; it cannot move what that extra says, so it can never shift [firedFor] by even a nanosecond, and J1.1
 * already removed the one thing (`pullForwardIfTooSoon`) that legitimately could. The window as shipped instead
 * OPENED a regression on an ordinary night with no J1.1-style bug in play at all: a NAP target computed from
 * `asleepNapTarget` (a genuine mid-night return to sleep, nothing to do with `awakeNapTarget`'s own deferral)
 * can land inside this same window purely by coincidence of timing - one minute after `morningAlarmAt` - and
 * get misattributed as the wake alarm: `wakeAlarmFiredAt` set from a NAP firing (which H8's own doc, and every
 * other doc in this file, says can never happen), `napAlarmsUsed` never incremented, `lastNapAlarmFiredAt` left
 * null, and the NEXT tick recomputing the same now-"unspent" nap target, finding it overdue, and ringing it a
 * SECOND time - the exact re-ring shape H8 exists to prevent, reopened by the fix meant to hardened it. Back to
 * exact equality: [morningAlarmAt] is the night's own morning target (H1's own latch - J5 CORRECTION: it is
 * protected from nap overwrites and from erasure, NOT immutable, and this line used to call it "a fixed
 * instant for the whole night"; see [latchMorningAlarmAt] and NightState.morningAlarmAt's own J5 note. The
 * argument here is unaffected, because what it actually needs is that a deferred morning alarm is armed for
 * and fires at whatever value this holds at that moment, not that the value never moved), `awakeNapTarget`
 * hands it back completely unchanged when it applies (WakeAlarm.kt's own doc: "leaves the phone armed exactly
 * at the instant it is already armed at, so nothing moves"), and J1.1 guarantees `pullForwardIfTooSoon` never
 * touches an already-future `raw` - so a firing that is genuinely the deferred morning alarm always arrives at
 * exactly `morningAlarmAt`, with nothing left to widen a window for.
 */
fun firedAlarmIsWakeAlarm(firedPlanMode: AlarmMode, firedFor: Instant, morningAlarmAt: Instant?): Boolean =
    firedPlanMode != AlarmMode.NAP || firedFor == morningAlarmAt

/**
 * Runs one full night cycle: load state, sync and read band data (keeping the previous segments and
 * marking the sync as not ok on failure or stale data), compute the plan, arm the phone alarm if needed,
 * save state, and schedule the next tick. [scheduledFor] and [receivedAt] are the tick's intended
 * instant and when TickReceiver actually received it (both null for a tick not triggered by the exact
 * alarm, e.g. an immediate UI-requested tick); both are logged as-is for diagnosing tick lateness. Returns
 * the new state, or null if no night was in progress (including when a concurrent endNight cleared it while
 * this call waited for the lock).
 */
suspend fun runNightTick(context: Context, now: Instant, scheduledFor: Instant? = null, receivedAt: Instant? = null): NightState? =
    withNightTransactionLock { runNightTickLocked(context, now, scheduledFor, receivedAt) }

private suspend fun runNightTickLocked(context: Context, now: Instant, scheduledFor: Instant?, receivedAt: Instant?): NightState? {
    // T4: virtual, like scheduledFor/receivedAt (both already virtual - see TickScheduling.kt/TickReceiver.kt)
    // - all three are the CALLER's own entry-time readings (sampled before this tick even reached the lock -
    // see NightService.onStartCommand/NightController.runImmediateTick), kept ONLY for the "tick" log line's
    // own lateness diagnosis below (how late this tick fired against its own schedule, how long it then waited
    // for the lock). FIX1: never used for anything written into state or handed to the engine any more - see
    // decisionNow below.
    val startedAt = nowInstant()
    val state = withContext(Dispatchers.IO) { loadNightState(context) }
    if (state == null) {
        Log.w(LOG_TAG, "runNightTick called with no night in progress")
        return null
    }
    val debugNight = state.debugOptions.isAnyEnabled
    appendNightLog(
        context, state.startedAt,
        NightLogEvent(
            now, "tick",
            mapOf(
                "scheduledFor" to formatTickTimeField(scheduledFor),
                "receivedAt" to formatTickTimeField(receivedAt),
                "startedAt" to startedAt.toString()
            )
        ),
        debugNight
    )

    // FIX1 (owner-reported, 2026-09-21) SUPERSEDES C1's own two-instant version of this: C1 re-read the clock
    // only after the data sync's own I/O, and only for the PLAN - the data path (segment build, right below)
    // still ran on the caller's stale pre-lock `now`. withNightTransactionLock serializes COMMITS, not ENTRY
    // order, so a tick that entered earlier could still win the lock LAST; acting on its own stale `now` then
    // rebuilt this tick's segments (buildSimulatedSegments depends purely on `now` - see BandDataSimulator.kt)
    // and plan from an OLDER instant than whatever the tick that already committed used - silently reverting a
    // freshly-detected AWAKE interval back to ASLEEP and walking lastSyncAt backwards with it (the owner's own
    // visible bug: the screen flips back to the asleep view with no input). Re-sampled HERE instead, once,
    // now that the lock is actually held and before either the data path or the plan runs, and reused for
    // BOTH - the data path (segment build) and the plan alike, so two ticks racing for the lock always commit
    // in non-decreasing `now` order.
    //
    // Accepted trade-off, real (unwarped) nights only: a real sync can itself take up to ~2 min (see
    // syncOrFail's own doc); the plan below no longer gets a SEPARATE, fresher post-sync clock read the way
    // C1 gave it. Bounded by the sync's own worst case, and it buys a single consistent instant across data
    // and plan instead of two that can disagree - worth it, since the sync-in-progress case this trades away
    // is only ever this reachable when simulatedBandData is off, and U1 guarantees the clock is never warped
    // then, so the two readings would have differed by at most that same real sync duration anyway.
    val decisionNow = nowInstant()

    val config = resolveEngineConfig(state.debugOptions)
    val appSettings = readAppSettings(context).first()
    val simulatedEvents = if (state.debugOptions.simulatedBandData) readSimulatedSleepEvents(context).first() else emptyList()
    val outcome = readBandDataForTick(context, state.debugOptions, appSettings, state, simulatedEvents, decisionNow)

    // J1.5: a stale sync (failed outright, or returned only stale data) with a real alarm already armed keeps
    // that plan untouched rather than re-planning stale segments against this tick's own moving decisionNow -
    // see shouldKeepPreviousPlan's own doc for the no-deadline-night-never-rings bug this closes. J2 must-fix 1:
    // decisionNow is now also part of the guard itself (a plan is only kept while its own alarm is still
    // ahead of decisionNow) - see shouldKeepPreviousPlan's own doc for the freeze-forever bug that closes.
    //
    // J3 SHOULD FIX 6 (reviewer note, 2026-09-21) RENAMES the log event below from "dead_band_keep_plan" to
    // "stale_sync_keep_plan": this guard triggers on mere STALENESS (including the ordinary "export file did
    // not change since the last successful sync" case, not only a genuine band fault), so "dead band" invited
    // the wrong mental model for anyone reading the night log - see shouldKeepPreviousPlan's own doc for the
    // full staleness-vs-fault distinction and why the behaviour itself is unchanged, only the name.
    val plan = if (shouldKeepPreviousPlan(outcome, state.lastPlan, state.phoneAlarmFiredFor, decisionNow)) {
        // shouldKeepPreviousPlan only returns true when previousPlan?.wakeAt is non-null, so state.lastPlan
        // itself is guaranteed non-null here too.
        val keptPlan = checkNotNull(state.lastPlan)
        appendNightLog(
            context, state.startedAt,
            NightLogEvent(
                decisionNow, "stale_sync_keep_plan",
                // S5/item 3c (reviewer note, 2026-09-21): reworded from "already has a real alarm armed" -
                // shouldKeepPreviousPlan's own S5 doc says plainly that this guard never actually knows whether
                // the previous plan's alarm is armed, only that its wakeAt is non-null and still ahead of now.
                // On a night where the exact-alarm permission was revoked, the old wording asserted something
                // false on exactly the log line someone diagnosing a missed alarm would read.
                mapOf("cause" to "sync not ok and the previous plan's target is still pending (unfired, still ahead of now) - keeping it rather than re-planning stale data", "wakeAt" to keptPlan.wakeAt.toString())
            ),
            state.debugOptions.isAnyEnabled
        )
        keptPlan
    } else {
        computeAlarmPlan(
            outcome.segments, state.settings, decisionNow, state.morningAlarmAt, currentZone(), config,
            state.wakeAlarmFiredAt, state.napAlarmsUsed, state.lastNapAlarmFiredAt, state.phoneAlarmFiredFor
        )
    }
    logDataAndPlan(context, state, outcome, plan, decisionNow)

    val napAlarmArmed = armPhoneAlarmIfNeeded(context, state, state.lastPlan, plan, decisionNow)
    // H7.2/FIX4: right after arming (or not) the phone alarm for this tick's own plan - a nap the tick just
    // armed supersedes any nudge still pending from an earlier alarm, but only once it is CONFIRMED - see
    // napSupersedesPendingNudge's own doc for what changed and why.
    cancelNudgeIfSupersededByNap(context, state, plan, outcome.segments, config, napAlarmArmed, decisionNow)
    // J5: the mirror image of the line above - a supersession must never end up being the LAST word while the
    // owner is still awake in bed with nothing armed at all. See rearmNudgeIfNapCancelledWhileAwake's own doc.
    rearmNudgeIfNapCancelledWhileAwake(context, state, plan, outcome.segments, config, decisionNow)

    // F2/F6/H2: phoneAlarmFiredFor, wakeAlarmFiredAt, napAlarmsUsed and lastNapAlarmFiredAt are
    // PhoneAlarmReceiver's own bookkeeping (see PhoneAlarmFiredStore.kt) - a tick only ever reads them
    // (already the freshest values, merged in by loadNightState above) and carries them through unchanged; it
    // never writes them itself. H1: morningAlarmAt IS this tick's own bookkeeping - latched here, from THIS
    // plan, for the next tick to read. FIX1: lastSyncAt is decisionNow now, never the caller's stale entry
    // `now` - decisionNow is the single fresh in-lock instant this whole tick's data and plan were built from,
    // so (unlike the stale entry `now`) it can never be older than an already-committed tick's own lastSyncAt.
    val newState = state.copy(
        lastPlan = plan,
        lastSyncAt = decisionNow,
        lastSyncOk = outcome.syncOk,
        lastSegments = outcome.segments,
        lastExportFileModifiedAt = outcome.exportFileModifiedAt,
        lastSyncFailureCause = outcome.failureCause,
        phoneAlarmFiredFor = state.phoneAlarmFiredFor,
        wakeAlarmFiredAt = state.wakeAlarmFiredAt,
        napAlarmsUsed = state.napAlarmsUsed,
        morningAlarmAt = latchMorningAlarmAt(state.morningAlarmAt, plan),
        lastNapAlarmFiredAt = state.lastNapAlarmFiredAt
    )
    if (!saveNightState(context, newState)) {
        // V9: decisionNow, not the tick's stale entry-time `now` - this line is appended after the data/plan/
        // phone_alarm_set lines above, which are all stamped with decisionNow; appendLogLine no longer
        // re-stamps every event to nowInstant() on write (V9), so this one must already carry an `at` at least
        // as late as what came before it, or a reader would see a "later" line with an earlier timestamp.
        appendNightLog(context, state.startedAt, NightLogEvent(decisionNow, "error", mapOf("step" to "save_night_state", "cause" to "failed to persist state after this tick")), debugNight)
    }
    // FIX2: booked from decisionNow - the instant this tick's own decision was actually made at - never the
    // caller's stale entry `now`. See nextTickAt's own doc for why, and TickScheduling.kt's own in-process
    // skip-arming guard for the defence-in-depth this pairs with.
    scheduleNextTick(context, plan, decisionNow, config)
    // FIX1: published from INSIDE this same lock - the one seam every committed tick's result now reaches
    // observedNightState through, whether it ran from NightService's own tick or an immediate UI-requested
    // one. NightService.handleTickResult and NightController.runImmediateTick used to each publish their own
    // snapshot AFTER the lock had already released, which could publish out of commit order for the exact same
    // reason the segment/plan mismatch above could commit out of order.
    publishNightState(newState)
    return newState
}

/**
 * H1: the night's own morning alarm time, latched - [plan]'s own `wakeAt` whenever its mode is FULL_CYCLES or
 * DEADLINE_ONLY (the alarm the owner is actually meant to wake up to), and [previous]'s unchanged value
 * otherwise (a NAP or FINISHED plan never overwrites it - see NightState.morningAlarmAt's own doc for why this
 * particular fact, rather than "the previous tick's own plan.wakeAt", is what fixes H1). `internal`, not
 * `private`: JVM-testable directly, and reused by `startNight`'s own initial plan.
 */
internal fun latchMorningAlarmAt(previous: Instant?, plan: AlarmPlan): Instant? = when (plan.mode) {
    // H8: `?: previous` - a FULL_CYCLES or DEADLINE_ONLY plan can now carry a null wakeAt (the morning alarm
    // has already rung, so there is nothing left to arm - see WakeAlarm.kt's `morningAlarmAlreadyRang`), and
    // that must never ERASE the latch. Erasing it would take away the one fact rule 7's AWAKE branch has left
    // once a firing goes unrecorded, which is the whole reason H1 introduced this latch.
    AlarmMode.FULL_CYCLES, AlarmMode.DEADLINE_ONLY -> plan.wakeAt ?: previous
    AlarmMode.NAP, AlarmMode.FINISHED -> previous
}

/**
 * H7.2 (owner decision, 2026-09-20): a nap supersedes a still-pending out-of-bed nudge. The nudge's whole
 * premise is that the owner is awake and not getting up; once a tick has detected them asleep again and armed
 * a nap, that premise is false, and letting the nudge ring anyway would wake them mid-nap at full alarm
 * volume ([com.nikita.sleepcycle.alarm.AlarmRingService.startRinging] is deliberately non-idempotent, F1).
 *
 * FIX4 (predates this branch, live on real nights - handle with care): the original version of this predicate
 * fired for ANY NAP-mode plan with a `wakeAt` and a pending nudge, without requiring the owner to actually be
 * ASLEEP. An awake tick can legitimately produce a NAP plan (rule 7 also covers "lying awake after an
 * awakening" states in its own reasoning, and a mis-synced/stale read could too) - cancelling the nudge and its
 * pre-check there means the nudge that should have got the owner out of bed never rings, with nothing left to
 * replace it. Now requires BOTH: [sleepState] is a confirmed [SleepState.ASLEEP] (the same data this tick's own
 * plan was computed from, per H7.2's own accepted staleness - the stronger, freshly-re-synced check is
 * [shouldCancelNudgeForPreCheck]'s own job, not this one's), AND [napAlarmArmed] is true - the replacement nap
 * this predicate is trading the nudge away for must itself be a real, successfully armed phone alarm, not a
 * plan whose own arm attempt silently failed (exact-alarm permission revoked) or never ran (F5's AWAKE safety
 * net gap, wakeAt == null - already excluded below).
 *
 * J4 must-fix (owner-reported, 2026-09-21) NARROWS what counts as "successfully armed" here: through J3,
 * [napAlarmArmed] was also true when the target had already fired (armPhoneAlarmIfNeeded's own exact-marker
 * match), which reached this predicate for a nap whose OWN firing had already armed its own fresh nudge -
 * superseding that fresh nudge with itself, losing it outright. [napAlarmArmed] no longer counts an
 * already-fired match as armed (see armPhoneAlarmIfNeeded's own J4 doc); this predicate's own logic is
 * unchanged, only what its caller now passes in. `internal`, not `private`: the one pure decision seam,
 * JVM-testable directly without a Context.
 *
 * H8 reached this same defect from the other side and guarded on `!plan.onsetIsProjected`. That guard is
 * subsumed here and deliberately not kept as well: a projected onset is `now + fallAsleepEstimate`, produced
 * precisely because the owner is NOT asleep, so a confirmed [SleepState.ASLEEP] already implies a real onset.
 * One reason for this predicate is worth more than two overlapping ones.
 *
 * J5 must-fix (owner-reported, 2026-09-21) ADDS the [pendingNudgeAt] < `plan.wakeAt` test, closing the OTHER
 * ordering of the same self-cancelling nap J4 fixed one half of. J4's own fix covers a firing that precedes
 * [armPhoneAlarmIfNeeded]'s own marker read (the match returns false, so [napAlarmArmed] is false). It does NOT
 * cover a firing that lands AFTER a genuinely successful arm: light 23:00 to 06:28, awake 06:28 to 06:30, light
 * again from 06:30, five cycles - the nap target is 06:50, a tick arms it just before 06:50 and [napAlarmArmed]
 * is legitimately true, then the receiver handles the 06:50 firing and writes its own fresh 07:05 nudge BEFORE
 * [cancelNudgeIfSupersededByNap] gets around to reading [readOutOfBedNudgePendingAt]. The tick then cancels
 * 07:05 on the strength of an arm result computed earlier in the same tick - the nap cancelling its own fresh
 * nudge again, by a different route.
 *
 * The fix is to stop trusting [napAlarmArmed] alone as evidence about the nudge and compare the nudge against
 * the TARGET it is being traded for. A nudge due at or after `plan.wakeAt` is not something this nap supersedes:
 * the nap rings FIRST and, per D4, arms its own fresh nudge unconditionally at that moment, so such a nudge is
 * either that firing's own (the race above) or something later still. Only a nudge strictly BEFORE the nap's
 * own target is the one that would ring mid-nap, which is the entire premise of this predicate. The equal case
 * is deliberately NOT cancelled: at worst the nudge rings alongside the nap it duplicates, which is the
 * direction this project errs in. [napAlarmArmed] stays as well - it still carries FIX4's own separate fact,
 * that a plan whose arm attempt FAILED is no replacement for anything.
 */
internal fun napSupersedesPendingNudge(plan: AlarmPlan, pendingNudgeAt: Instant?, sleepState: SleepState, napAlarmArmed: Boolean): Boolean {
    val napWakeAt = plan.wakeAt ?: return false
    if (pendingNudgeAt == null) return false
    return plan.mode == AlarmMode.NAP && sleepState == SleepState.ASLEEP && napAlarmArmed && pendingNudgeAt.isBefore(napWakeAt)
}

/**
 * H7.2: cancels a still-pending out-of-bed nudge once a tick arms a nap - see [napSupersedesPendingNudge]'s
 * own doc. Nothing is lost: every alarm that fires arms its own fresh nudge (D4), so when this nap's own
 * alarm rings, its nudge is armed 15 minutes after that. App-layer only, per the owner's own framing - the
 * engine's plan already says everything it needs to (NAP, non-null wakeAt); it does not need to know the
 * nudge exists.
 *
 * FIX4: [segments]/[config] recompute the same [SleepState] this tick's own plan was built from (the identical
 * `detectSleepState(normalizeSegments(...))` pair [computeAlarmPlan] itself runs internally, but [AlarmPlan]
 * does not carry the state back out) - cheap and pure, no extra I/O, reusing data already in hand this tick.
 */
private fun cancelNudgeIfSupersededByNap(
    context: Context,
    state: NightState,
    plan: AlarmPlan,
    segments: List<SleepSegment>,
    config: EngineConfig,
    napAlarmArmed: Boolean,
    now: Instant
) {
    val pendingNudgeAt = readOutOfBedNudgePendingAt(context)
    val sleepState = detectSleepState(normalizeSegments(segments, now, config))
    if (!napSupersedesPendingNudge(plan, pendingNudgeAt, sleepState, napAlarmArmed)) return
    cancelOutOfBedAlarm(context)
    cancelPreNudgeCheck(context)
    clearOutOfBedNudgePendingAt(context)
    appendNightLog(
        context, state.startedAt,
        NightLogEvent(
            now, "out_of_bed_nudge_cancelled",
            mapOf("cause" to "a_nap_was_armed", "pendingNudgeAt" to pendingNudgeAt.toString(), "napWakeAt" to plan.wakeAt.toString())
        ),
        state.debugOptions.isAnyEnabled
    )
}

/**
 * J5 must-fix (reviewer-reported, 2026-09-21): whether this tick, having just CANCELLED the phone alarm
 * because rule 7's AWAKE branch gave it nothing to arm, must put an out-of-bed nudge back. True exactly when
 * all four hold: [plan] is a NAP plan with a null `wakeAt` (rule 7's AWAKE branch returned null - see
 * WakeAlarm.kt's `awakeSlidingNapMustStop`), [previousPlan] DID have a `wakeAt` (so [armPhoneAlarmIfNeeded]
 * really did cancel a live alarm on this tick, rather than there having been nothing armed for several ticks
 * already), [sleepState] is [SleepState.AWAKE], and NO nudge is currently pending.
 *
 * The composed hole this closes, traced end to end: the 06:30 wake alarm fires and arms its own nudge for
 * 06:45. The band reads awake 06:30 to 06:33, then asleep from 06:33 (the owner dozed off). The 06:38:30 tick
 * sees a confirmed ASLEEP after an awakening, arms a rule 7 nap for 06:53 (`asleepNapTarget`, onset 06:33 plus
 * napLength) and - correctly, by H7.2 - supersedes the 06:45 nudge, cancelling it, its pre-check, and its
 * record. The owner then stirs and the band reads awake at 06:47. The 06:48:30 tick takes rule 7's AWAKE
 * branch, `awakeSlidingNapMustStop` returns null because the wake alarm has already fired, and
 * [armPhoneAlarmIfNeeded]'s own `wakeAt == null` branch cancels the 06:53 nap. The night is now left with NO
 * alarm, NO nudge and NO pre-check, and nothing re-arms a nudge except a firing, a boot restore or a speed
 * change - so if the band keeps reading awake (or reads light dozing as awake), nothing ever rings again. With
 * a deadline set the night reaches FINISHED in silence.
 *
 * What makes this worse than its parts: `napAlarm`'s own doc justifies ending the sliding nap by saying "D4's
 * own nudge covers the follow-up", and [napSupersedesPendingNudge]'s doc justifies cancelling the nudge by
 * saying the nap replaces it. Each is locally true; composed, the second removes exactly the safety net the
 * first is relying on. Re-arming here restores the invariant both comments already assume: after a wake alarm
 * has rung, an awake owner with nothing armed always has a nudge coming.
 *
 * This predates the whole J series - neither half is new, only their composition was never traced. `internal`,
 * not `private`: the one pure decision, JVM-testable directly without a Context.
 */
internal fun awakeNapCancellationNeedsNudge(plan: AlarmPlan, previousPlan: AlarmPlan?, sleepState: SleepState, pendingNudgeAt: Instant?): Boolean =
    plan.mode == AlarmMode.NAP && plan.wakeAt == null && previousPlan?.wakeAt != null &&
        sleepState == SleepState.AWAKE && pendingNudgeAt == null

/**
 * J5: arms a replacement out-of-bed nudge [EngineConfig.outOfBedDelay] after [now] when this tick's own
 * cancellation of a rule 7 nap would otherwise leave the night with nothing armed at all - see
 * [awakeNapCancellationNeedsNudge]'s own doc for the traced sequence.
 *
 * Deliberately mirrors PhoneAlarmReceiver.armOutOfBedNudge rather than sharing with it: that one is measured
 * from the FIRING it follows up, this one from this tick's own [now] (`decisionNow`), and it logs against a
 * state this function already holds. Two call sites is under this project's own "abstract at three" line; if a
 * third ever appears, they belong in one function in OutOfBedNudgeStore.kt's own neighbourhood. The H7.3
 * pre-check is armed here too, exactly as a firing would, so an owner who actually dozed off again in the
 * meantime still gets the nudge cancelled by the check's own re-sync rather than rung at full volume.
 */
private fun rearmNudgeIfNapCancelledWhileAwake(
    context: Context,
    state: NightState,
    plan: AlarmPlan,
    segments: List<SleepSegment>,
    config: EngineConfig,
    now: Instant
) {
    val sleepState = detectSleepState(normalizeSegments(segments, now, config))
    if (!awakeNapCancellationNeedsNudge(plan, state.lastPlan, sleepState, readOutOfBedNudgePendingAt(context))) return
    val at = now.plus(config.outOfBedDelay)
    if (!scheduleOutOfBedAlarm(context, at)) {
        appendNightLog(
            context, state.startedAt,
            NightLogEvent(now, "error", mapOf("step" to "out_of_bed_alarm", "cause" to "could not re-arm the nudge at $at after cancelling this night's nap - exact alarm permission was likely revoked")),
            state.debugOptions.isAnyEnabled
        )
        return
    }
    appendNightLog(
        context, state.startedAt,
        NightLogEvent(now, "out_of_bed_nudge_rearmed", mapOf("at" to at.toString(), "cause" to "rule 7's AWAKE branch cancelled this night's nap and no nudge was left pending")),
        state.debugOptions.isAnyEnabled
    )
    if (!saveOutOfBedNudgePendingAt(context, at)) {
        appendNightLog(
            context, state.startedAt,
            NightLogEvent(now, "error", mapOf("step" to "save_night_state", "cause" to "failed to persist the re-armed out-of-bed nudge instant")),
            state.debugOptions.isAnyEnabled
        )
    }
    if (!schedulePreNudgeCheck(context, at.minus(config.preNudgeCheckLead))) {
        appendNightLog(
            context, state.startedAt,
            NightLogEvent(now, "error", mapOf("step" to "pre_nudge_check", "cause" to "exact alarm permission was likely revoked - the re-armed nudge will still ring on schedule")),
            state.debugOptions.isAnyEnabled
        )
    }
}

/**
 * J1.6 (owner-reported, 2026-09-21): how far before [NightState.startedAt] the real sync queries for sleep
 * data. A Huawei row is a whole stage segment (20-60 min), keyed by its own START timestamp - querying with
 * `since` exactly at `startedAt` would drop an ENTIRE row whose stage began before the owner tapped Start
 * night even when the stage runs well past it (dozing off at 22:50, tapping Start night at 23:05: the SQL
 * `TIMESTAMP >= 23:05` excludes the whole 22:50-23:30 row, not just its first 15 minutes, and the next row
 * becomes the first one seen - the onset becomes whenever THAT starts, 40 minutes late, undercounting sleep).
 * Querying from this far back instead retrieves that row again; [clipSegmentsToNightStart] then turns it into
 * the RIGHT clipped remainder (23:05-23:30) once it reaches the engine - see that function's own doc.
 */
private val BAND_QUERY_LOOKBACK: Duration = Duration.ofHours(2)

/** `internal`, not `private`: DebugBandDataSource.kt's readBandDataForTick also calls this for every case that needs a real sync, and OutOfBedPreNudgeCheck.kt's own re-sync (unconditionally, see its own U4 audit note). */
internal suspend fun syncOrFail(context: Context, appSettings: AppSettings, state: NightState, now: Instant): BandDataResult {
    val deviceMac = appSettings.deviceMac
    val exportUri = appSettings.exportUri
    if (deviceMac == null || exportUri == null) {
        return BandDataResult.Failure("settings", "device MAC or export file not configured")
    }
    // J1.6: queried from BAND_QUERY_LOOKBACK before the night's own start, not state.startedAt itself - see
    // BAND_QUERY_LOOKBACK's own doc. resolveSyncOutcome clips the result back to state.startedAt afterward, so
    // every OTHER caller of this function (the pre-nudge check's own re-sync, which only ever reads the
    // LATEST sleep state and never the night's own onset) is unaffected either way.
    return syncAndReadBandData(context, exportUri, deviceMac, state.startedAt.minus(BAND_QUERY_LOOKBACK)) { event ->
        appendNightLog(context, state.startedAt, event, state.debugOptions.isAnyEnabled)
    }
}

/**
 * What this tick learned: the segments to act on, whether the sync counts as ok, the export file's modified
 * time to compare against next tick, and, only when the sync itself failed (as opposed to succeeding with
 * stale data), a plain-English cause for the UI to show. [source] says whether [segments] came from the real
 * band or the Debug screen's simulator.
 */
data class SyncOutcome(
    val segments: List<SleepSegment>,
    val newestSampleAt: Instant?,
    val syncOk: Boolean,
    val exportFileModifiedAt: Instant?,
    val failureCause: String?,
    val source: BandDataSource = BandDataSource.BAND
)

/**
 * A failed sync never removes the previous segments. Stale data (old samples, or an export file that did
 * not advance since the last successful read) is treated the same way: kept as not-ok so the UI can show
 * "last sync failed", without discarding the last known picture of the night. [SyncOutcome.failureCause] is
 * only set for an actual sync failure, so the UI can tell that case apart from merely-stale data. `internal`,
 * not `private`: also called from DebugBandDataSource.kt.
 */
// U4 audit flag: [now] here (and syncOrFail's own [now]) is virtual (T4), compared inside against
// [checkDataFreshness] with the real band's own newestSampleAt/exportFileModifiedAt. Safe only because this
// path is reached exclusively from readBandDataForTick's `!debugOptions.simulatedBandData` branch
// (DebugBandDataSource.kt) - real band data is being read, never simulated - and U1 guarantees a warp can only
// be live while simulatedBandData is ON, so whenever this runs the clock is guaranteed unwarped (nowInstant()
// == Instant.now()). Contrast OutOfBedPreNudgeCheck.kt's own re-sync, which is NOT similarly guarded.
internal fun resolveSyncOutcome(context: Context, state: NightState, syncResult: BandDataResult, now: Instant): SyncOutcome =
    when (syncResult) {
        is BandDataResult.Failure -> {
            appendNightLog(context, state.startedAt, NightLogEvent(now, "error", mapOf("step" to syncResult.step, "cause" to syncResult.cause)), state.debugOptions.isAnyEnabled)
            SyncOutcome(state.lastSegments, null, false, state.lastExportFileModifiedAt, syncResult.cause)
        }
        is BandDataResult.Success -> {
            val freshness = checkDataFreshness(syncResult.newestSampleAt, now, syncResult.exportFileModifiedAt, state.lastExportFileModifiedAt)
            if (!freshness.isFresh) {
                appendNightLog(
                    context, state.startedAt,
                    NightLogEvent(now, "stale_data", mapOf("reason" to (freshness.reason ?: ""), "newestSampleAt" to (syncResult.newestSampleAt?.toString() ?: ""))),
                    state.debugOptions.isAnyEnabled
                )
                SyncOutcome(state.lastSegments, syncResult.newestSampleAt, false, syncResult.exportFileModifiedAt, null)
            } else {
                // J1.6: clipped to the night's own start here, once, right where fresh segments first reach
                // the app - state.lastSegments (the failed/stale branches above) is always already-clipped
                // output from an earlier pass through this same branch, so it never needs clipping again.
                SyncOutcome(clipSegmentsToNightStart(syncResult.segments, state.startedAt), syncResult.newestSampleAt, true, syncResult.exportFileModifiedAt, null)
            }
        }
    }

/**
 * J1.5 (owner-reported, 2026-09-21): whether this tick should skip re-planning entirely and keep
 * [previousPlan] unchanged, rather than feeding [outcome]'s own (possibly long-stale) segments through
 * computeAlarmPlan again against this tick's own moving `now`.
 *
 * True exactly when the sync did not succeed ([SyncOutcome.syncOk] false - a failed sync or one that returned
 * only stale data, [resolveSyncOutcome]'s own two cases) AND [previousPlan] still has a non-null `wakeAt` of
 * its own ([AlarmPlan.wakeAt] non-null - see the S5 note further down for exactly what that does and does not
 * tell this function about whether an alarm was ever actually armed for it).
 *
 * S6 (reviewer note, 2026-09-21) RENAMES this guard's own framing from "dead band" to STALENESS throughout -
 * `!outcome.syncOk` is true for EITHER of [resolveSyncOutcome]'s not-ok branches, and one of those two is
 * ordinary staleness (an export file that simply did not change since the last successful sync), not a genuine
 * band fault. "Dead band" invited the wrong mental model: acceptable slack for the MORNING alarm, whose target
 * sits hours out, but mid-night during a nap stretch the target is only about 20 minutes out while ticks run
 * every 5 - a genuine return to sleep detected inside that window may not get re-planned for up to a nap
 * length before this guard lets go. Still bounded, and still erring toward keeping the alarm already armed
 * rather than dropping it, so the BEHAVIOUR is unchanged by this note - only the name (see the log event this
 * guard's own call site now writes, `stale_sync_keep_plan`, not `dead_band_keep_plan`). The specific traced
 * scenario immediately below (a genuinely dead band, the band literally unreachable) is one real CAUSE of
 * staleness, not the only one, and is left as originally written since it did in fact happen exactly that way:
 * on a night with NO deadline, if the band dies while the owner is marked AWAKE (before ever falling properly
 * asleep, or having woken mid-night), [outcome.segments] keeps being the SAME stale AWAKE-ending picture on
 * every tick, and the engine's own
 * `findReferenceOnset` has no choice but to project a fresh onset at `now + fallAsleepEstimate` from THAT
 * tick's own `now` - which, because `now` keeps moving forward tick after tick while the segments never do,
 * projects a LATER onset (and so a later `wakeAt`) every single time. The alarm never settles on a fixed
 * instant and so never actually rings - the night silently never ends. A deadline would have capped this (the
 * plan degrades to DEADLINE_ONLY, then FINISHED, once `now` approaches it), which is exactly why only the
 * no-deadline night is exposed.
 *
 * Keeping [previousPlan] untouched instead means the LAST GENUINELY COMPUTED alarm - the last one built from
 * data the band actually reported - stays armed and rings at its own fixed time, exactly as if the band had
 * simply gone silent for the rest of the night (which, from the alarm's point of view, is exactly what
 * happened). [previousPlan] with a null `wakeAt` (FINISHED, or a NAP plan that already used its own alarm) is
 * NOT covered here - false in that case, and the caller re-plans normally, since there is no real alarm to
 * protect by freezing the plan, and the usual engine logic (D4's nudge, D5/G8's naps) still needs a chance to
 * run from fresh (if stale) data.
 *
 * [phoneAlarmFiredFor] also ends the freeze once [previousPlan]'s own `wakeAt` matches it: PhoneAlarmReceiver
 * fires independently of whether ticks are stuck re-using a frozen plan (D1/D4's own nudge is armed directly
 * at fire time, not from a tick's own plan), so once the protected alarm has actually rung there is nothing
 * left here to protect - freezing further would only mean logging the same already-fired instant forever
 * instead of ever trying fresh data again, however unlikely fresh data is to arrive from a genuinely dead band.
 *
 * J2 must-fix 1 (owner-reported, 2026-09-21) CORRECTS J1.5: the version above shipped with no [now] at all, so
 * it could not tell "armed and still pending" apart from "its time came and went and nothing ever rang it" -
 * its only exit was [phoneAlarmFiredFor] catching up, which never happens once the alarm can no longer fire at
 * all (AlarmManager alarms do not survive the phone's battery dying, and BootReceiver.handleBoot correctly
 * refuses to re-arm a target already in the past - see its own F3 doc). The owner's traced sequence: night
 * starts 23:00, no deadline, 5 cycles, plan FULL_CYCLES wakeAt 06:30, armed; the phone dies at 04:10 (killing
 * every AlarmManager alarm with it); the owner plugs in and it boots at 07:41 with the band also dead
 * (Gadgetbridge unreachable); the first tick's sync fails, sees `lastPlan.wakeAt` 06:30 with nothing having
 * fired, and under the pre-J2 guard FROZE - forever, since the mode is not FINISHED so ticks keep running on
 * the ordinary cadence, each one re-freezing the same spent 06:30 target. Before J1.5 this same first tick
 * re-planned, found the target overdue, and pulled it forward to `now + minAlarmLead` via D8's own recovery
 * (the fix [BootReceiver.handleBoot]'s own doc already names as correct) - J1.5 silently disabled that
 * recovery for exactly the case it exists to catch. The same hole is reachable with no reboot at all: a
 * revoked exact-alarm permission while the band is dead, or a firing whose own [savePhoneAlarmFiredFor] write
 * fails, freezes the rest of the night identically.
 *
 * Requiring `wakeAt.isAfter(now)` fixes this without weakening the freeze's own job: [previousPlan] is kept
 * ONLY while its alarm is still ahead of [now] - a genuinely pending arm, not a promise that has already come
 * and gone unfulfilled. The moment `now` reaches or passes `wakeAt`, this returns false and the caller falls
 * straight back to the ordinary re-planning path below, which - fed the same stale segments a dead band always
 * returns - lands on D8's existing pull-forward recovery exactly as it did before J1.5 shipped. Nothing here
 * stops the RETRY of arming itself while the freeze holds: [armPhoneAlarmIfNeeded] is still called every tick
 * regardless of which branch produced [plan] (see its own doc, "retried on every tick instead of silently
 * sticking"), so a plan kept frozen by this guard still gets its own arm attempt retried tick after tick.
 *
 * S5 (reviewer note, addressed here rather than left for later): the docstring above said "already armed" as
 * shorthand, but this function never actually knows whether [previousPlan]'s alarm is armed - only that its
 * `wakeAt` is non-null and, now, still ahead of [now]. A plan whose every arm attempt keeps failing (permission
 * revoked) is "kept" by this guard exactly like a successfully armed one; what stops THAT case from also
 * freezing forever is the same arm-attempt retry above, not this function - once `now` catches up to `wakeAt`
 * this guard lets go regardless of whether arming ever actually succeeded.
 */
internal fun shouldKeepPreviousPlan(outcome: SyncOutcome, previousPlan: AlarmPlan?, phoneAlarmFiredFor: Instant?, now: Instant): Boolean {
    val wakeAt = previousPlan?.wakeAt ?: return false
    return !outcome.syncOk && wakeAt != phoneAlarmFiredFor && wakeAt.isAfter(now)
}

/**
 * Always (re)schedules the phone alarm when the plan has one, so a scheduling failure is retried on every
 * tick instead of silently sticking - except an instant that is at or before [now] (Android fires a past
 * exact alarm immediately) or that already fired once ([NightState.phoneAlarmFiredFor]), which are never
 * (re)armed. "phone_alarm_set" is only logged when the target time actually changed; a scheduling failure is
 * logged as an error on every tick until it succeeds.
 *
 * J2 must-fix 4: the "at or before [now]" past-check re-reads the real clock at the point it actually runs
 * (see its own call site doc below), never [now] itself - [now] here is `decisionNow`, still what every OTHER
 * decision in this function and its caller are built from.
 *
 * SUPERSEDED BY J3 (owner-reported, 2026-09-21): the paragraph directly above is left in place rather than
 * deleted (this function's history has already round-tripped on exactly this point once - see the J3 paragraph
 * further down for the regression the clock re-read it describes turned out to cause), but it is no longer
 * true. J3 removed every clock read from this function; the past-check compares against [now] (`decisionNow`)
 * alone again, like every other decision in this tick. What IS re-read fresh at commit time now is the FIRED
 * MARKER, not the clock - see [phoneAlarmFiredForNow] and its own J3 doc below for the corrected mechanism. A
 * reader who stops at this paragraph gets the wrong model of the one function that decides whether the phone is
 * armed; read the J3 paragraphs below before trusting anything above this line about what gets re-read.
 *
 * J5 (owner-approved revert, 2026-09-21) CONFIRMS the J3 paragraph above, which was itself briefly FALSE and
 * is true again. Between J4 and J5 this function did read the real clock once more, at the point of arming, to
 * feed `shouldRefuseStaleRearm` - so "J3 removed every clock read from this function" described the code for
 * exactly one round, stopped being true when J4 landed, and was never updated. The J5 revert removed that read
 * along with the guard, so [now] (`decisionNow`) really is the only instant every decision in this function
 * compares against again. Recorded here rather than silently re-asserted: a claim this doc has now been wrong
 * about once should be checked against the code, not trusted from the comment.
 *
 * F2 SUPERSEDES the original spec: this function no longer touches [NightState.napAlarmsUsed] at all - that
 * counter is now PhoneAlarmReceiver's own bookkeeping, incremented only when a nap alarm actually FIRES (see
 * PhoneAlarmReceiver.recordWakeOrNapFired), never at arm time here.
 *
 * FIX4: returns whether, after this call, [plan]'s own `wakeAt` was freshly armed by THIS tick - originally
 * (through J3) also true on an already-fired exact match; false when there is nothing to arm (`wakeAt == null`),
 * arming did not happen (already overdue), or failed (exact-alarm permission revoked).
 * [cancelNudgeIfSupersededByNap]'s own guard reads this, so a plan whose own alarm attempt failed never counts
 * as a replacement for the nudge it would otherwise cancel. (J4 briefly added a third refusal here,
 * `shouldRefuseStaleRearm`; J5 reverted it - see the J5 record above [shouldArmPhoneAlarm].)
 *
 * J4 must-fix (owner-reported, 2026-09-21) NARROWS what "already-fired" contributes to this return value: it no
 * longer counts as a replacement nap for the nudge guard - see [phoneAlarmFiredForNow]'s own J4 paragraph below
 * for the full reasoning and the behaviour this corrects (a real, observed loss of the out-of-bed nudge after a
 * mid-night nap rings).
 *
 * J1.3 (owner-reported, 2026-09-21) ADDED a second line of defence here, on top of WakeAlarm.kt's own
 * `morningAlarmAlreadyRang` (the primary fix for the same re-ring loop): a [wakeAt] landing strictly after
 * [NightState.phoneAlarmFiredFor] but still within [EngineConfig.minAlarmLead] of it was refused too, not just
 * an exact match - a backstop for a plan that, for whatever reason not yet accounted for, still computes
 * something close to an instant that JUST fired.
 *
 * S1 (reviewer note, adversarial review of J1.1-J1.6, addressed by DELETING that guard rather than keeping it):
 * it never actually caught the bug it was written for. J1.3's own re-ring (the morning path) always recomputes
 * the SAME spent instant, so it hits the exact-match check right above instead, never this one (`wakeAt ==
 * firedFor`, not `wakeAt.isAfter(firedFor)`). The mid-night nap re-ring this guard was meant to also backstop
 * (`asleepNapTarget`'s own pre-J2-must-fix-3 bug) produced `now + minAlarmLead` - which, by the time a tick
 * gets around to recomputing an overdue nap, sits MORE than `minAlarmLead` past the original firing (the tick
 * cadence and sync delay alone push `now` well past `firedFor + minAlarmLead` before this ever runs) - so this
 * guard's own window missed that case too, every time. The only thing it COULD ever catch: a genuinely
 * DIFFERENT, legitimate target that happens to land 1-2 minutes after an unrelated firing - refusing to arm a
 * real alarm, not a re-ring. J2 must-fix 3 (asleepNapTarget anchored on the later of lastNapAlarmFiredAt and
 * phoneAlarmFiredFor) now closes the nap-side hole at its actual source, the same way morningAlarmAlreadyRang
 * already closes the morning-side one - neither re-ring shape reaches this function with anything for a
 * "close but not exact" window to catch any more, so there is nothing left here worth keeping, only a
 * false-positive risk to remove.
 *
 * J3 CORRECTION (2026-09-21): "below" two sentences up is wrong - it names the SHORT paragraph ABOVE (the one
 * starting "J2 must-fix 4: the \"at or before [now]\" past-check..."), now marked superseded in place, which is
 * what was in fact kept so this history stays legible. What did NOT survive is different: J2 must-fix 4 also
 * carried a roughly twenty-line CALL-SITE comment, right above the old `val armTimeNow = nowInstant()` line
 * inside this function's own body, with its own worked trace ("A tick starting at 06:28:30 with a 3-minute
 * sync..."). J3's own diff deleted that block outright rather than keeping it - it described a mechanism (a
 * live clock re-read at arm time) that no longer exists in this function at all, so there was nothing left for
 * it to document in place. The paragraph immediately below is a fresh recap of what that deleted block used to
 * say, written from scratch for J3's own doc, not the original text carried forward.
 *
 * That original call-site block re-read the real CLOCK right before the past-check, in place of reusing
 * [now]/`decisionNow`. That closed the duplicate-ring bug it describes, but J3 (owner-reported, 2026-09-21)
 * found it opens the opposite one: D8's own pull-forward
 * target sits only [EngineConfig.minAlarmLead] (2 min, rounded up to the next whole minute) past `decisionNow`,
 * and a real dead-band sync can itself cost close to that much (`syncOrFail`'s own two 60 s timeouts) - so
 * whenever a sync outlives the very lead it just produced, the freshly re-read clock has already caught up to
 * a target that has NEVER FIRED, and this refused to arm it: logged as an error, dropped, with no self-heal -
 * the plan stays in the same near-alarm phase, so the identical tick 5 minutes later hits the identical
 * refusal, forever, until the owner picks up the phone. Traced sequence: night starts 23:00, no deadline, 5
 * cycles, plan FULL_CYCLES wakeAt 06:30, armed; the phone's battery dies at 04:10 (killing the armed alarm with
 * it, per D8's own precondition - it never actually rang) and boots at 07:41 with the band also dead
 * (Gadgetbridge unreachable, both of syncOrFail's own timeouts burned). The first tick samples `decisionNow` at
 * 07:41:57; [shouldKeepPreviousPlan] correctly releases (06:30 is no longer ahead of `decisionNow`, J2 must-fix
 * 1's own job) so the plan recomputes and D8 pulls 06:30 forward to 07:44:00 - but by the time arming runs, the
 * re-read clock already reads 07:44:01, one second past it. This also puts J2 must-fix 1 and must-fix 4 in
 * direct conflict: must-fix 1 exists precisely to let this pull-forward recovery run on a dead band, and
 * must-fix 4 could refuse the very target it produces.
 *
 * J3's fix: stop re-sampling the CLOCK and re-sample the FIRED MARKER instead - see [phoneAlarmFiredForNow]
 * below. The clock both checks compare against goes back to being [now] (`decisionNow`) throughout, matching
 * every other decision in this tick (FIX1's own reason still holds unchanged). The must-fix 4 scenario (a
 * target that rang DURING this tick's own sync) now resolves at the EARLIER check instead (`wakeAt ==
 * phoneAlarmFiredForNow`, right below): the fresh marker read at commit time already equals the fired target
 * exactly, so nothing is re-armed and there is still exactly one ring - `TickScheduleRaceTest.kt`'s own `J2
 * must-fix 4 replay` test still asserts this, unchanged. A target that has never fired is never refused,
 * whatever the sync costs - pinned by that same file's `J3 replay` test, which fails without this fix.
 *
 * J4 must-fix (owner-reported, 2026-09-21) CORRECTS what J3's own exact-match branch RETURNS, not whether it
 * re-arms (unchanged: still refuses to re-arm on a match, still one ring). Before J3 this branch compared
 * against the tick-start snapshot [NightState.phoneAlarmFiredFor], which a firing DURING this tick's own sync
 * could not yet have updated - so the match (and its `return true`) was reachable only for a firing that
 * predated the whole tick. J3's own fresh, live read makes it ALSO reachable for a firing that happens WHILE
 * this tick is pending, which is precisely the ordinary case for a mid-night rule 7 nap: the nap fires, its own
 * PhoneAlarmReceiver firing arms a FRESH out-of-bed nudge (D4) for itself, and THEN this same tick commits,
 * observes the live marker now matching its own plan's `wakeAt`, and used to return true here - which
 * [cancelNudgeIfSupersededByNap] reads as "a nap was successfully armed, supersede whatever nudge is pending".
 * The nudge that gets cancelled is not some stale leftover from an earlier, now-superseded alarm; it is the
 * SAME nap's own fresh nudge, armed by the SAME firing this match just confirmed - cancelling it loses the one
 * mechanism that gets the owner out of bed if they lie there after the nap. Traced sequence: a rule 7 nap armed
 * for 03:20, a tick starting at 03:18:30 with a 2-minute sync, the nap rings at 03:20 (arming a fresh nudge for
 * 03:35), the tick commits at 03:20:30, the live marker matches, `napSupersedesPendingNudge` fires, the fresh
 * 03:35 nudge is cancelled - no nudge rings for that nap at all.
 *
 * This branch now returns false instead: an exact marker match still means "do not re-arm" (nothing about the
 * ARM decision changes), but it no longer counts, to [cancelNudgeIfSupersededByNap]'s own guard, as a FRESH nap
 * this tick itself armed. The FIX4 docstring above is updated to match - this function's return value has
 * exactly one reader (`napAlarmArmed` at the call site in `runNightTickLocked`), so nothing else in the
 * codebase depends on the old "or already fired" half of that contract. `TickScheduleRaceTest.kt`'s own `J2
 * must-fix 4 replay` test is unaffected (it only asserts on ring count, never on the nudge), and a new `J4
 * nudge replay` test pins the corrected nudge behaviour directly, failing without this change.
 */
private fun armPhoneAlarmIfNeeded(context: Context, state: NightState, previousPlan: AlarmPlan?, plan: AlarmPlan, now: Instant): Boolean {
    val debugNight = state.debugOptions.isAnyEnabled
    val wakeAt = plan.wakeAt
    if (wakeAt == null) {
        if (previousPlan?.wakeAt != null) cancelPhoneAlarm(context)
        return false
    }
    // J3 (owner-reported, 2026-09-21) CORRECTS J2 must-fix 4: re-sampled HERE, right before BOTH checks below
    // (the exact-match early return just underneath, and shouldArmPhoneAlarm's own past-check), is the FIRED
    // MARKER - a small, fast file read ([readPhoneAlarmFiredFor], PhoneAlarmFiredStore.kt) - never the clock.
    // [now] (`decisionNow`) is what both checks compare against instead, unchanged from every other decision in
    // this tick. See this function's own J3 doc above for the missed-ring regression this replaces, and why the
    // marker, not the clock, is the thing that actually moves during a tick's own sync. Falls back to the
    // tick-start snapshot ([NightState.phoneAlarmFiredFor]) only if the fresh read itself fails (never throws,
    // per PhoneAlarmFiredStore.kt's own doc, but can still come back null on a transient I/O error) - so a
    // flaky read never regresses below what the pre-J3 code already knew.
    val phoneAlarmFiredForNow = readPhoneAlarmFiredFor(context) ?: state.phoneAlarmFiredFor
    if (wakeAt == phoneAlarmFiredForNow) {
        // J4 (owner-reported, 2026-09-21): false, not true - see this function's own J4 doc above. Still never
        // re-arms (this is an early return, schedulePhoneAlarm below never runs), but no longer tells the nudge
        // guard that a FRESH nap was armed this tick - the nap that fired already armed its own fresh nudge,
        // and this branch only confirms that same firing, never a new one.
        return false
    }
    if (!shouldArmPhoneAlarm(wakeAt, now, phoneAlarmFiredForNow)) {
        // NIT (reviewer note, 2026-09-21): the cause string now names the actual comparison instant ([now],
        // `decisionNow`) instead of the word "now" alone - before J3 this line was stamped with `decisionNow`
        // while the comparison itself ran against a different, later, re-read clock, so a log line timestamped
        // 07:41:57 could refuse a 07:44:00 target as "at or before now" - correct in wording, misleading to read,
        // and easy to mistake for a code bug rather than a slow sync. J3 removed the second clock entirely, so
        // the timestamp and the comparison instant are now always the same value - this just says so explicitly.
        appendNightLog(
            context, state.startedAt,
            NightLogEvent(now, "error", mapOf("step" to "phone_alarm", "cause" to "planned phone alarm $wakeAt is at or before $now, not arming - Android fires a past exact alarm immediately")),
            debugNight
        )
        return false
    }
    // J5: J4's own second guard (shouldRefuseStaleRearm, a fresh real-clock read plus equality with
    // previousPlan's own wakeAt) stood HERE and was REVERTED - see the J5 record above shouldArmPhoneAlarm for
    // the two permanent-silence counterexamples it opened and what a correct version of the idea would need.
    val armed = schedulePhoneAlarm(context, wakeAt, alarmLabelFor(plan.mode, wakeAt, state.morningAlarmAt))
    val changed = wakeAt != previousPlan?.wakeAt
    val event = when {
        !armed -> NightLogEvent(now, "error", mapOf("step" to "phone_alarm", "cause" to "exact alarm permission was likely revoked, will retry next tick"))
        changed -> NightLogEvent(now, "phone_alarm_set", mapOf("at" to wakeAt.toString()))
        else -> null
    }
    event?.let { appendNightLog(context, state.startedAt, it, debugNight) }
    return armed
}

/**
 * FIX2 (owner-reported, 2026-09-21): the virtual instant to book the next tick at, computed from
 * [decisionNow] - the instant THIS tick's own decision was actually made at (see runNightTickLocked's own
 * FIX1 doc) - rather than a tick's stale entry instant, which the original version of [scheduleNextTick] used.
 * At 600x a tick's own disk work costs 1 to 4 virtual minutes; booking from an instant that old meant
 * `nextTickAt` was frequently already in the virtual past by the time it was actually converted and armed,
 * which [inProcessTickDelay] then floored to [IN_PROCESS_TICK_MIN_DELAY] and armed anyway - a tight 250 ms
 * disk-IO loop where every iteration is itself an overlapping tick (the exact precondition FIX1 guards
 * against). [nextSyncDelay] always returns a strictly positive [config]-derived duration (or null, meaning the
 * night is over - see its own doc), so `decisionNow.plus(delay)` is always strictly after [decisionNow] by
 * construction; the `isAfter` check below is a defensive invariant, not dead code - it is what this function's
 * own test pins down, and it is what protects this call site if that guarantee about [nextSyncDelay] ever
 * changes. `internal`, not `private`: JVM-testable directly without a Context. See TickScheduling.kt's own
 * in-process skip-arming guard for the second, independent line of defence this pairs with (residual
 * real-time drift between this decision and the moment [scheduleTick] actually arms it - disk saves, logging -
 * that no choice of virtual instant alone can fully absorb).
 */
internal fun nextTickAt(plan: AlarmPlan, decisionNow: Instant, config: EngineConfig): Instant? {
    val delay = nextSyncDelay(plan, decisionNow, config) ?: return null
    val candidate = decisionNow.plus(delay)
    // Defensive floor only - unreachable today since nextSyncDelay's own duration is always strictly positive
    // (see this function's own doc) - the smallest possible strictly-after instant, not a meaningful duration
    // in its own right (contrast TickScheduling.kt's IN_PROCESS_TICK_MIN_DELAY, a real-time floor for a
    // different problem - see this function's own doc for how the two relate).
    return if (candidate.isAfter(decisionNow)) candidate else decisionNow.plusNanos(1)
}

/** null from [nextTickAt] means the engine considers the night over: stop scheduling ticks. [config] comes from [resolveEngineConfig], so a fast debug night's tick cadence matches its own EngineConfig. */
private fun scheduleNextTick(context: Context, plan: AlarmPlan, decisionNow: Instant, config: EngineConfig) {
    val at = nextTickAt(plan, decisionNow, config)
    if (at == null) {
        cancelTick(context)
    } else {
        scheduleTick(context, at)
    }
}
