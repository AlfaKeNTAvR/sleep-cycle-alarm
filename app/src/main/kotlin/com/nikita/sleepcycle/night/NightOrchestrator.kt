package com.nikita.sleepcycle.night

// File purpose: runNightTick - the one function that runs a whole sync-plan-alarm cycle, per app-spec.md.
// This is the app's main call site into the engine's plan computation (the other is NightUiSupport.kt).
// The entire transaction runs under withNightTransactionLock so a service tick, an immediate UI tick, and
// endNight can never interleave. D1/D2: the band is a sensor only now - every tick arms the phone alarm at
// plan.wakeAt, and no alarm command is ever sent to the band.

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
import com.nikita.sleepcycle.engine.computeAlarmPlan
import com.nikita.sleepcycle.engine.nextSyncDelay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
 */
fun firedAlarmIsWakeAlarm(firedPlanMode: AlarmMode): Boolean = firedPlanMode != AlarmMode.NAP

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
    // - all three must be apples-to-apples for the "tick" log line's own lateness diagnosis to mean anything.
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

    val config = resolveEngineConfig(state.debugOptions)
    val appSettings = readAppSettings(context).first()
    val simulatedEvents = if (state.debugOptions.simulatedBandData) readSimulatedSleepEvents(context).first() else emptyList()
    val outcome = readBandDataForTick(context, state.debugOptions, appSettings, state, simulatedEvents, now)

    // C1: the clock is re-read here, after the (up to ~2 min) Gadgetbridge I/O above, and used for the plan
    // itself - `now` at entry can already be stale by the time the I/O finishes. `now` (the tick's entry time)
    // still drives only this tick's own scheduledFor/receivedAt/startedAt log fields above, for diagnosing
    // tick lateness. T4: virtual - this is the instant the plan itself is computed against.
    val decisionNow = nowInstant()
    val plan = computeAlarmPlan(
        outcome.segments, state.settings, decisionNow, state.morningAlarmAt, currentZone(), config,
        state.wakeAlarmFiredAt, state.napAlarmsUsed, state.lastNapAlarmFiredAt
    )
    logDataAndPlan(context, state, outcome, plan, decisionNow)

    armPhoneAlarmIfNeeded(context, state, state.lastPlan, plan, decisionNow)
    // H7.2: right after arming (or not) the phone alarm for this tick's own plan - a nap the tick just armed
    // supersedes any nudge still pending from an earlier alarm.
    cancelNudgeIfSupersededByNap(context, state, plan, decisionNow)

    // F2/F6/H2: phoneAlarmFiredFor, wakeAlarmFiredAt, napAlarmsUsed and lastNapAlarmFiredAt are
    // PhoneAlarmReceiver's own bookkeeping (see PhoneAlarmFiredStore.kt) - a tick only ever reads them
    // (already the freshest values, merged in by loadNightState above) and carries them through unchanged; it
    // never writes them itself. H1: morningAlarmAt IS this tick's own bookkeeping - latched here, from THIS
    // plan, for the next tick to read.
    val newState = state.copy(
        lastPlan = plan,
        lastSyncAt = now,
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
    scheduleNextTick(context, plan, now, config)
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
    AlarmMode.FULL_CYCLES, AlarmMode.DEADLINE_ONLY -> plan.wakeAt
    AlarmMode.NAP, AlarmMode.FINISHED -> previous
}

/**
 * H7.2 (owner decision, 2026-09-20): a nap supersedes a still-pending out-of-bed nudge. The nudge's whole
 * premise is that the owner is awake and not getting up; once a tick has detected them asleep again and armed
 * a nap, that premise is false, and letting the nudge ring anyway would wake them mid-nap at full alarm
 * volume ([com.nikita.sleepcycle.alarm.AlarmRingService.startRinging] is deliberately non-idempotent, F1).
 * `internal`, not `private`: the one pure decision seam, JVM-testable directly without a Context.
 */
internal fun napSupersedesPendingNudge(plan: AlarmPlan, pendingNudgeAt: Instant?): Boolean =
    plan.mode == AlarmMode.NAP && plan.wakeAt != null && pendingNudgeAt != null

/**
 * H7.2: cancels a still-pending out-of-bed nudge once a tick arms a nap - see [napSupersedesPendingNudge]'s
 * own doc. Nothing is lost: every alarm that fires arms its own fresh nudge (D4), so when this nap's own
 * alarm rings, its nudge is armed 15 minutes after that. App-layer only, per the owner's own framing - the
 * engine's plan already says everything it needs to (NAP, non-null wakeAt); it does not need to know the
 * nudge exists.
 */
private fun cancelNudgeIfSupersededByNap(context: Context, state: NightState, plan: AlarmPlan, now: Instant) {
    val pendingNudgeAt = readOutOfBedNudgePendingAt(context)
    if (!napSupersedesPendingNudge(plan, pendingNudgeAt)) return
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
 * Always (re)schedules the phone alarm when the plan has one, so a scheduling failure is retried on every
 * tick instead of silently sticking - except an instant that is at or before [now] (Android fires a past
 * exact alarm immediately) or that already fired once ([NightState.phoneAlarmFiredFor]), which are never
 * (re)armed. "phone_alarm_set" is only logged when the target time actually changed; a scheduling failure is
 * logged as an error on every tick until it succeeds.
 *
 * F2 SUPERSEDES the original spec: this function no longer touches [NightState.napAlarmsUsed] at all - that
 * counter is now PhoneAlarmReceiver's own bookkeeping, incremented only when a nap alarm actually FIRES (see
 * PhoneAlarmReceiver.recordWakeOrNapFired), never at arm time here.
 */
private fun armPhoneAlarmIfNeeded(context: Context, state: NightState, previousPlan: AlarmPlan?, plan: AlarmPlan, now: Instant) {
    val debugNight = state.debugOptions.isAnyEnabled
    val wakeAt = plan.wakeAt
    if (wakeAt == null) {
        if (previousPlan?.wakeAt != null) cancelPhoneAlarm(context)
        return
    }
    if (wakeAt == state.phoneAlarmFiredFor) {
        return
    }
    if (!shouldArmPhoneAlarm(wakeAt, now, state.phoneAlarmFiredFor)) {
        appendNightLog(
            context, state.startedAt,
            NightLogEvent(now, "error", mapOf("step" to "phone_alarm", "cause" to "planned phone alarm $wakeAt is at or before now, not arming - Android fires a past exact alarm immediately")),
            debugNight
        )
        return
    }
    val armed = schedulePhoneAlarm(context, wakeAt, alarmLabelFor(plan.mode))
    val changed = wakeAt != previousPlan?.wakeAt
    val event = when {
        !armed -> NightLogEvent(now, "error", mapOf("step" to "phone_alarm", "cause" to "exact alarm permission was likely revoked, will retry next tick"))
        changed -> NightLogEvent(now, "phone_alarm_set", mapOf("at" to wakeAt.toString()))
        else -> null
    }
    event?.let { appendNightLog(context, state.startedAt, it, debugNight) }
}

/** null from [nextSyncDelay] means the engine considers the night over: stop scheduling ticks. [config] comes from [resolveEngineConfig], so a fast debug night's tick cadence matches its own EngineConfig. */
private fun scheduleNextTick(context: Context, plan: AlarmPlan, now: Instant, config: EngineConfig) {
    val delay = nextSyncDelay(plan, now, config)
    if (delay == null) {
        cancelTick(context)
    } else {
        scheduleTick(context, now.plus(delay))
    }
}
