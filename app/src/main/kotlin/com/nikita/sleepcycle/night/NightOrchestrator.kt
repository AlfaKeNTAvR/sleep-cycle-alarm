package com.nikita.sleepcycle.night

// File purpose: runNightTick - the one function that runs a whole sync-plan-alarm cycle, per app-spec.md.
// This is the app's main call site into the engine's plan computation (the other is NightUiSupport.kt).
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
import com.nikita.sleepcycle.alarm.schedulePhoneAlarm
import com.nikita.sleepcycle.bridge.BandDataResult
import com.nikita.sleepcycle.bridge.checkDataFreshness
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
 * D1: the ONE guard for arming or re-arming the phone alarm - used by [armPhoneAlarmIfNeeded] (every tick),
 * `BootReceiver.handleBoot` (after a reboot) and `startNight` (the very first arm), so none of the three can
 * drift out of sync with the other two. Never arms a null alarm, one at or before [now] (Android fires a past
 * exact alarm immediately), or one that already fired ([phoneAlarmFiredFor]).
 */
fun shouldArmPhoneAlarm(wakeAt: Instant?, now: Instant, phoneAlarmFiredFor: Instant?): Boolean =
    wakeAt != null && wakeAt.isAfter(now) && wakeAt != phoneAlarmFiredFor

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
 * J1.2 (owner-reported, 2026-09-21) widens H8's own test from EXACT equality to a WINDOW:
 * [morningAlarmAt] .. [morningAlarmAt] + [WAKE_ALARM_FIRE_TOLERANCE]. H8's `firedFor == morningAlarmAt` assumed
 * a NAP plan carrying the morning alarm always fires at exactly that instant, but J1.1's own bug (now fixed)
 * proved that assumption false in exactly the case H8 exists for: waking in the last two minutes before the
 * morning alarm hands rule 7 the still-pending `morningAlarmAt` as `wakeAt` (`awakeNapTarget`), and the old
 * `pullForwardIfTooSoon` could then rearm the phone a minute or two LATER than `morningAlarmAt` - a NAP-mode
 * firing that never exactly matches it. Exact equality then missed it: `wakeAlarmFiredAt` stayed null, the ring
 * screen called it "Nap alarm" at the real wake-up, and one of the two nap alarms was spent recording it as a
 * nap. J1.1 removes the trigger, but this predicate is hardened independently rather than leaning on that fix
 * alone - a firing landing slightly after its own armed target is not unique to J1.1 (delivery jitter, Doze
 * deferral), and the window costs nothing: [WAKE_ALARM_FIRE_TOLERANCE] is [EngineConfig.minAlarmLead] (the
 * widest D8/J1.1 could ever legitimately push a target by) plus one more minute of slack for ordinary
 * AlarmManager delivery jitter, and nothing genuinely nap-shaped can land inside a 3-minute window right after
 * the latched morning alarm - a real rule 7 nap is always `napLength` (20 min) or more past whatever it is
 * measured from.
 */
private val WAKE_ALARM_FIRE_TOLERANCE: Duration = EngineConfig().minAlarmLead.plus(Duration.ofMinutes(1))

fun firedAlarmIsWakeAlarm(firedPlanMode: AlarmMode, firedFor: Instant, morningAlarmAt: Instant?): Boolean =
    firedPlanMode != AlarmMode.NAP ||
        (morningAlarmAt != null && !firedFor.isBefore(morningAlarmAt) && !firedFor.isAfter(morningAlarmAt.plus(WAKE_ALARM_FIRE_TOLERANCE)))

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

    // J1.5: a dead band (sync failed, or returned only stale data) with a real alarm already armed keeps that
    // plan untouched rather than re-planning stale segments against this tick's own moving decisionNow - see
    // shouldKeepPreviousPlan's own doc for the no-deadline-night-never-rings bug this closes.
    val plan = if (shouldKeepPreviousPlan(outcome, state.lastPlan, state.phoneAlarmFiredFor)) {
        // shouldKeepPreviousPlan only returns true when previousPlan?.wakeAt is non-null, so state.lastPlan
        // itself is guaranteed non-null here too.
        val keptPlan = checkNotNull(state.lastPlan)
        appendNightLog(
            context, state.startedAt,
            NightLogEvent(
                decisionNow, "dead_band_keep_plan",
                mapOf("cause" to "sync not ok and the previous plan already has a real alarm armed - keeping it rather than re-planning stale data", "wakeAt" to keptPlan.wakeAt.toString())
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

    val napAlarmArmed = armPhoneAlarmIfNeeded(context, state, state.lastPlan, plan, config, decisionNow)
    // H7.2/FIX4: right after arming (or not) the phone alarm for this tick's own plan - a nap the tick just
    // armed supersedes any nudge still pending from an earlier alarm, but only once it is CONFIRMED - see
    // napSupersedesPendingNudge's own doc for what changed and why.
    cancelNudgeIfSupersededByNap(context, state, plan, outcome.segments, config, napAlarmArmed, decisionNow)

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
 * this predicate is trading the nudge away for must itself be a real, successfully armed (or already fired)
 * phone alarm, not a plan whose own arm attempt silently failed (exact-alarm permission revoked) or never ran
 * (F5's AWAKE safety net gap, wakeAt == null - already excluded below). `internal`, not `private`: the one pure
 * decision seam, JVM-testable directly without a Context.
 *
 * H8 reached this same defect from the other side and guarded on `!plan.onsetIsProjected`. That guard is
 * subsumed here and deliberately not kept as well: a projected onset is `now + fallAsleepEstimate`, produced
 * precisely because the owner is NOT asleep, so a confirmed [SleepState.ASLEEP] already implies a real onset.
 * One reason for this predicate is worth more than two overlapping ones.
 */
internal fun napSupersedesPendingNudge(plan: AlarmPlan, pendingNudgeAt: Instant?, sleepState: SleepState, napAlarmArmed: Boolean): Boolean =
    plan.mode == AlarmMode.NAP && plan.wakeAt != null && pendingNudgeAt != null && sleepState == SleepState.ASLEEP && napAlarmArmed

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

/** `internal`, not `private`: DebugBandDataSource.kt's readBandDataForTick also calls this for every case that needs a real sync, and OutOfBedPreNudgeCheck.kt's own re-sync (unconditionally, see its own U4 audit note). */
internal suspend fun syncOrFail(context: Context, appSettings: AppSettings, state: NightState, now: Instant): BandDataResult {
    val deviceMac = appSettings.deviceMac
    val exportUri = appSettings.exportUri
    if (deviceMac == null || exportUri == null) {
        return BandDataResult.Failure("settings", "device MAC or export file not configured")
    }
    return syncAndReadBandData(context, exportUri, deviceMac, state.startedAt) { event ->
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
                SyncOutcome(syncResult.segments, syncResult.newestSampleAt, true, syncResult.exportFileModifiedAt, null)
            }
        }
    }

/**
 * J1.5 (owner-reported, 2026-09-21): whether this tick should skip re-planning entirely and keep
 * [previousPlan] unchanged, rather than feeding [outcome]'s own (possibly long-stale) segments through
 * computeAlarmPlan again against this tick's own moving `now`.
 *
 * True exactly when the sync did not succeed ([SyncOutcome.syncOk] false - a failed sync or one that returned
 * only stale data, [resolveSyncOutcome]'s own two cases) AND [previousPlan] already carries a real armed alarm
 * ([AlarmPlan.wakeAt] non-null). The dead band this guards against: on a night with NO deadline, if the band
 * dies while the owner is marked AWAKE (before ever falling properly asleep, or having woken mid-night),
 * [outcome.segments] keeps being the SAME stale AWAKE-ending picture on every tick, and the engine's own
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
 */
internal fun shouldKeepPreviousPlan(outcome: SyncOutcome, previousPlan: AlarmPlan?, phoneAlarmFiredFor: Instant?): Boolean {
    val wakeAt = previousPlan?.wakeAt ?: return false
    return !outcome.syncOk && wakeAt != phoneAlarmFiredFor
}

/**
 * Always (re)schedules the phone alarm when the plan has one, so a scheduling failure is retried on every
 * tick instead of silently sticking - except an instant that is at or before [now] (Android fires a past
 * exact alarm immediately) or that already fired once ([NightState.phoneAlarmFiredFor]), which are never
 * (re)armed. "phone_alarm_set" is only logged when the target time actually changed; a scheduling failure is
 * logged as an error on every tick until it succeeds.
 *
 * F2 SUPERSEDES the original spec: this function no longer touches [NightState.napAlarmsUsed] at all - that
 * counter is now PhoneAlarmReceiver's own bookkeeping, incremented only when a nap alarm actually FIRES (see
 * PhoneAlarmReceiver.recordWakeOrNapFired), never at arm time here.
 *
 * FIX4: returns whether, after this call, [plan]'s own `wakeAt` is backed by a real armed (or already-fired)
 * phone alarm - true when [schedulePhoneAlarm] itself succeeds this tick, or when it already fired for this
 * exact instant ([NightState.phoneAlarmFiredFor], which means D4 already armed a fresh nudge of its own for
 * it); false when there is nothing to arm (`wakeAt == null`) or arming did not happen (already overdue) or
 * failed (exact-alarm permission revoked). [cancelNudgeIfSupersededByNap]'s own guard reads this, so a plan
 * whose own alarm attempt failed never counts as a replacement for the nudge it would otherwise cancel.
 *
 * J1.3 (owner-reported, 2026-09-21) ADDS a second line of defence, on top of WakeAlarm.kt's own
 * `morningAlarmAlreadyRang` (the primary fix for the same re-ring loop): a [wakeAt] landing strictly after
 * [NightState.phoneAlarmFiredFor] but still within [EngineConfig.minAlarmLead] of it is refused too, not just
 * an exact match. The exact-match check right above already catches the ordinary case; this one is a backstop
 * for a plan that, for whatever reason not yet accounted for, still computes something close to an instant
 * that JUST fired - the shape every version of this bug has taken, whichever rule produced the recomputed
 * target. Nothing legitimate is ever this close: the shortest real gap between two consecutive alarms is
 * [EngineConfig.napLength] (20 min), far outside this window.
 */
private fun armPhoneAlarmIfNeeded(context: Context, state: NightState, previousPlan: AlarmPlan?, plan: AlarmPlan, config: EngineConfig, now: Instant): Boolean {
    val debugNight = state.debugOptions.isAnyEnabled
    val wakeAt = plan.wakeAt
    if (wakeAt == null) {
        if (previousPlan?.wakeAt != null) cancelPhoneAlarm(context)
        return false
    }
    if (wakeAt == state.phoneAlarmFiredFor) {
        return true
    }
    val firedFor = state.phoneAlarmFiredFor
    if (firedFor != null && wakeAt.isAfter(firedFor) && !wakeAt.isAfter(firedFor.plus(config.minAlarmLead))) {
        appendNightLog(
            context, state.startedAt,
            NightLogEvent(
                now, "error",
                mapOf(
                    "step" to "phone_alarm",
                    "cause" to "planned phone alarm $wakeAt is within minAlarmLead of the alarm that already fired at $firedFor - " +
                        "not arming (J1.3's second line of defence against the re-ring loop)"
                )
            ),
            debugNight
        )
        return false
    }
    if (!shouldArmPhoneAlarm(wakeAt, now, state.phoneAlarmFiredFor)) {
        appendNightLog(
            context, state.startedAt,
            NightLogEvent(now, "error", mapOf("step" to "phone_alarm", "cause" to "planned phone alarm $wakeAt is at or before now, not arming - Android fires a past exact alarm immediately")),
            debugNight
        )
        return false
    }
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
