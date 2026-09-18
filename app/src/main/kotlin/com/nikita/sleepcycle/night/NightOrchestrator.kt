package com.nikita.sleepcycle.night

// File purpose: runNightTick - the one function that runs a whole sync-plan-alarm cycle, per app-spec.md.
// This is the app's main call site into the engine's plan computation (the other is NightUiSupport.kt).
// The entire transaction runs under withNightTransactionLock so a service tick, an immediate UI tick, and
// endNight can never interleave. The band alarm decision is made against a clock re-read AFTER the
// Gadgetbridge I/O (BandAlarmDecision.kt rule 6): `now` at entry only drives the plan and the tick's own
// scheduledFor/receivedAt/startedAt log fields, never the band alarm's minute-lead check.

import android.content.Context
import android.util.Log
import com.nikita.sleepcycle.alarm.cancelPhoneAlarm
import com.nikita.sleepcycle.alarm.schedulePhoneAlarm
import com.nikita.sleepcycle.bridge.BandAlarmSlot
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
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

// Debug/simulation mode (see DebugOptions.kt, DebugBandDataSource.kt): band data source and EngineConfig are
// both resolved from state.debugOptions through the one seams readBandDataForTick/resolveEngineConfig, so this
// file's own control flow never branches on debug options itself.

private const val LOG_TAG = "NightOrchestrator"

/** The zone to interpret band-alarm hour/minute in, read fresh on every call so a timezone change takes effect on the next tick. */
private fun currentZone(): ZoneId = ZoneId.systemDefault()

/**
 * D2: the ONE guard for arming or re-arming the phone alarm - used by [armPhoneAlarmIfNeeded] (every tick),
 * `BootReceiver.handleBoot` (after a reboot) and `startNight` (the very first arm), so none of the three can
 * drift out of sync with the other two. Never arms a null alarm, one at or before [now] (Android fires a past
 * exact alarm immediately), or one that already fired ([phoneAlarmFiredFor]).
 */
fun shouldArmPhoneAlarm(phoneAlarm: Instant?, now: Instant, phoneAlarmFiredFor: Instant?): Boolean =
    phoneAlarm != null && phoneAlarm.isAfter(now) && phoneAlarm != phoneAlarmFiredFor

/**
 * Runs one full night cycle: load state, sync and read band data (keeping the previous segments and
 * marking the sync as not ok on failure or stale data), compute the plan, move the band and phone alarms
 * if needed, save state, and schedule the next tick. [scheduledFor] and [receivedAt] are the tick's intended
 * instant and when TickReceiver actually received it (both null for a tick not triggered by the exact
 * alarm, e.g. an immediate UI-requested tick); both are logged as-is for diagnosing tick lateness. Returns
 * the new state, or null if no night was in progress (including when a concurrent endNight cleared it while
 * this call waited for the lock).
 */
suspend fun runNightTick(context: Context, now: Instant, scheduledFor: Instant? = null, receivedAt: Instant? = null): NightState? =
    withNightTransactionLock { runNightTickLocked(context, now, scheduledFor, receivedAt) }

private suspend fun runNightTickLocked(context: Context, now: Instant, scheduledFor: Instant?, receivedAt: Instant?): NightState? {
    val startedAt = Instant.now()
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
    // itself, not just the band alarm decision below - `now` at entry can already be stale by the time the
    // I/O finishes, and computing the plan against a stale clock is what made the app's own band-alarm lead
    // check fail almost every time on a real OVERDUE night. `now` (the tick's entry time) still drives only
    // this tick's own scheduledFor/receivedAt/startedAt log fields above, for diagnosing tick lateness.
    val decisionNow = Instant.now()
    val plan = computeAlarmPlan(outcome.segments, state.settings, decisionNow, state.lastPlan, currentZone(), config)
    logDataAndPlan(context, state, outcome, plan, decisionNow)

    val bandAlarmResolution = resolveBandAlarmState(context, state, appSettings, plan, outcome, decisionNow)

    armPhoneAlarmIfNeeded(context, state, state.lastPlan, plan, decisionNow)

    // C4: FINISHED normally stops ticking outright; this counts how many of those FINISHED ticks were spent
    // verifying a dismissal that had not yet been confirmed gone, so scheduleNextTick can bound it.
    val cleanupTicksUsed = nextFinishedCleanupTickCount(plan.mode, state.lastPlan?.mode, state.finishedCleanupTicksUsed)

    val newState = state.copy(
        lastPlan = plan,
        requestedBandAlarm = bandAlarmResolution.requested,
        confirmedBandAlarm = bandAlarmResolution.confirmed,
        lastSyncAt = now,
        lastSyncOk = outcome.syncOk,
        lastSegments = outcome.segments,
        lastExportFileModifiedAt = outcome.exportFileModifiedAt,
        lastSyncFailureCause = outcome.failureCause,
        pendingDismissTitles = bandAlarmResolution.pendingDismissTitles,
        phoneAlarmFiredFor = state.phoneAlarmFiredFor,
        finishedCleanupTicksUsed = cleanupTicksUsed,
        smartWakeupWarning = bandAlarmResolution.smartWakeupWarning,
        lastBandAlarmSet = bandAlarmResolution.lastBandAlarmSet,
        singleSlotResendsUsed = bandAlarmResolution.singleSlotResendsUsed
    )
    if (!saveNightState(context, newState)) {
        appendNightLog(context, state.startedAt, NightLogEvent(now, "error", mapOf("step" to "save_night_state", "cause" to "failed to persist state after this tick")), debugNight)
    }
    scheduleNextTick(context, plan, bandAlarmResolution.pendingDismissTitles, cleanupTicksUsed, now, config)
    return newState
}

/** `internal`, not `private`: DebugBandDataSource.kt's readBandDataForTick also calls this for every case that needs a real sync. */
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
 * What this tick learned: the segments and band alarm table to act on, whether the sync counts as ok, the
 * export file's modified time to compare against next tick, and, only when the sync itself failed (as
 * opposed to succeeding with stale data), a plain-English cause for the UI to show. [bandAlarms] is null
 * exactly when there is no table to confirm a band alarm against this tick (BandAlarmDecision.kt rule 4).
 * [source] says whether [segments] came from the real band or the Debug screen's simulator.
 */
data class SyncOutcome(
    val segments: List<SleepSegment>,
    val newestSampleAt: Instant?,
    val syncOk: Boolean,
    val exportFileModifiedAt: Instant?,
    val bandAlarms: List<BandAlarmSlot>?,
    val failureCause: String?,
    val source: BandDataSource = BandDataSource.BAND
)

/**
 * A failed sync never removes the previous segments. Stale data (old samples, or an export file that did
 * not advance since the last successful read) is treated the same way: kept as not-ok so the UI can show
 * "last sync failed", without discarding the last known picture of the night. [SyncOutcome.failureCause] is
 * only set for an actual sync failure, so the UI can tell that case apart from merely-stale data. Either way,
 * `!syncOk` means the band alarm table this tick is not trustworthy (BandAlarmDecision.kt rule 4 groups a
 * failed sync and stale-but-successful data together as "no table available"), so [SyncOutcome.bandAlarms] is
 * null in both cases. `internal`, not `private`: also called from DebugBandDataSource.kt.
 */
internal fun resolveSyncOutcome(context: Context, state: NightState, syncResult: BandDataResult, now: Instant): SyncOutcome =
    when (syncResult) {
        is BandDataResult.Failure -> {
            appendNightLog(context, state.startedAt, NightLogEvent(now, "error", mapOf("step" to syncResult.step, "cause" to syncResult.cause)), state.debugOptions.isAnyEnabled)
            SyncOutcome(state.lastSegments, null, false, state.lastExportFileModifiedAt, null, syncResult.cause)
        }
        is BandDataResult.Success -> {
            val freshness = checkDataFreshness(syncResult.newestSampleAt, now, syncResult.exportFileModifiedAt, state.lastExportFileModifiedAt)
            if (!freshness.isFresh) {
                appendNightLog(
                    context, state.startedAt,
                    NightLogEvent(now, "stale_data", mapOf("reason" to (freshness.reason ?: ""), "newestSampleAt" to (syncResult.newestSampleAt?.toString() ?: ""))),
                    state.debugOptions.isAnyEnabled
                )
                SyncOutcome(state.lastSegments, syncResult.newestSampleAt, false, syncResult.exportFileModifiedAt, null, null)
            } else {
                SyncOutcome(syncResult.segments, syncResult.newestSampleAt, true, syncResult.exportFileModifiedAt, syncResult.bandAlarms, null)
            }
        }
    }

/** Everything the band alarm decision produced, to persist into NightState. [smartWakeupWarning] is held across a blind tick (no table this tick) rather than cleared, mirroring how [requested]/[confirmed] are held blind - see [resolveBandAlarmState]. [lastBandAlarmSet] is the last minute this app really wrote to the band tonight, carried forward when this tick wrote nothing. */
private data class BandAlarmResolution(
    val requested: BandAlarmCommitment?,
    val confirmed: BandAlarmCommitment?,
    val pendingDismissTitles: Set<String>,
    val smartWakeupWarning: BandAlarmSmartWakeupWarning?,
    val lastBandAlarmSet: BandAlarmCommitment?,
    val singleSlotResendsUsed: Int
)

/**
 * Runs the band alarm decision every tick (BandAlarmDecision.kt owns the "no table" case itself - rule 4 -
 * so this never skips calling it just because the sync failed or the data was stale; that is exactly the
 * case a blind first alarm has to cover). [now] must already be the post-I/O, C1-refreshed clock. The send
 * margin is [MIN_SEND_MARGIN] (BandAlarmDecision.kt's own default) - a physical "is there still time to send
 * this" check, deliberately independent of the engine's own `EngineConfig.minAlarmLead`.
 */
private fun resolveBandAlarmState(
    context: Context,
    state: NightState,
    appSettings: AppSettings,
    plan: AlarmPlan,
    outcome: SyncOutcome,
    now: Instant
): BandAlarmResolution {
    val deviceMac = appSettings.deviceMac
        ?: return BandAlarmResolution(
            state.requestedBandAlarm, state.confirmedBandAlarm, state.pendingDismissTitles, state.smartWakeupWarning,
            state.lastBandAlarmSet, state.singleSlotResendsUsed
        )

    // outcome.bandAlarms is already null exactly when there is no table to trust this tick (real sync failed
    // or was stale, or - in debug dry-run mode - the simulated table, which is always available; see
    // DebugBandDataSource.kt and resolveSyncOutcome above), so no further gating is needed here.
    val slots = outcome.bandAlarms
    // Bug 2 (night 1): while the sleeper is not asleep, an existing commitment is held rather than chased
    // across a projected onset that slides with the clock. The plan itself is untouched, so the phone alarm
    // and the Night screen keep following it - see BandAlarmRetargeting.kt for why this is not in the engine.
    val sleepState = buildNightEngineView(state.copy(lastSegments = outcome.segments), now).sleepState
    val desiredBandAlarm = resolveDesiredBandAlarm(
        plan, sleepState, state.requestedBandAlarm ?: state.confirmedBandAlarm, now, currentZone(), resolveEngineConfig(state.debugOptions)
    )
    if (isBandAlarmHeld(plan, desiredBandAlarm)) {
        appendNightLog(
            context, state.startedAt,
            NightLogEvent(
                now, "band_alarm_held",
                mapOf(
                    "held" to (desiredBandAlarm?.toString() ?: ""),
                    "planned" to (plan.bandAlarm?.toString() ?: ""),
                    "sleepState" to sleepState.name,
                    "cause" to "not asleep: the band keeps the time it already has until a real onset moves it"
                )
            ),
            state.debugOptions.isAnyEnabled
        )
    }
    val decision = decideBandAlarmCommands(
        desiredBandAlarm, state.requestedBandAlarm, state.confirmedBandAlarm, state.pendingDismissTitles,
        slots, now, currentZone(), singleSlotResendsUsed = state.singleSlotResendsUsed
    )
    // The move protocol only overwrites what the band is armed with when the SET reclaims the slot its own
    // DISMISS just freed. Reported, never fixed from here: Gadgetbridge's picker cannot be steered.
    if (slots != null) {
        findBandAlarmSlotRelocationRisk(slots, BAND_ALARM_TITLE)?.let { risk ->
            appendNightLog(
                context, state.startedAt,
                NightLogEvent(
                    now, "band_alarm_slot_risk",
                    mapOf(
                        "ourSlot" to (risk.ourPosition + SLOT_DISPLAY_OFFSET).toString(),
                        "wouldLandInSlot" to (risk.wouldLandAtPosition + SLOT_DISPLAY_OFFSET).toString(),
                        "cause" to "a free alarm slot sits above ours, so the next move would leave our slot armed at the old time"
                    )
                ),
                state.debugOptions.isAnyEnabled
            )
        }
    }
    // C1: skipped for physical send-margin reasons (BandAlarmOutcome.TOO_SOON) is expected and routine, not a
    // planning failure - the engine itself already guarantees a real minAlarmLead cushion - so it is logged
    // at info with both times, never as an error. Logged here, not in NightTickLogging.kt's generic outcome
    // mapping, because only this call site has both the target and the refreshed clock on hand.
    if (decision.outcome == BandAlarmOutcome.TOO_SOON) {
        appendNightLog(
            context, state.startedAt,
            NightLogEvent(now, "band_alarm_send_skipped", mapOf("target" to (plan.bandAlarm?.toString() ?: ""), "now" to now.toString())),
            state.debugOptions.isAnyEnabled
        )
    }
    // The band alarm table could not be read, so the alarm already asked for is kept as it is. Logged with the
    // held and the desired time so the owner can see from the night log how far the band drifted from the plan.
    if (decision.outcome == BandAlarmOutcome.BLIND_FROZEN) {
        val heldBandAlarm = decision.confirmedBandAlarm ?: decision.requestedBandAlarm
        appendNightLog(
            context, state.startedAt,
            NightLogEvent(
                now, "band_alarm_frozen",
                mapOf(
                    "held" to (heldBandAlarm?.let { "%02d:%02d".format(it.hour, it.minute) } ?: ""),
                    "desired" to (plan.bandAlarm?.toString() ?: ""),
                    "cause" to "no band alarm table available"
                )
            ),
            state.debugOptions.isAnyEnabled
        )
    }
    // C3: write-ahead - persist what we are ABOUT to ask the band for before sending any command, so a kill
    // between sending and this tick's normal end-of-tick save still leaves night_state.json knowing what was
    // asked. A no-op (no extra disk write) when this tick has nothing to send.
    persistBandAlarmCommitmentWriteAhead(context, state, decision, now)
    applyBandAlarmDecision(context, state, deviceMac, decision, slots, now)
    // Held across a blind tick rather than cleared: [slots] null means this tick could not resolve it fresh,
    // and the underlying condition does not stop being true just because we cannot observe it right now - the
    // same reasoning [decision.requestedBandAlarm]/[decision.confirmedBandAlarm] already follow while blind
    // (see BandAlarmBlindMode.kt).
    val smartWakeupWarning = if (slots != null) decision.smartWakeupWarning else state.smartWakeupWarning
    return BandAlarmResolution(
        decision.requestedBandAlarm, decision.confirmedBandAlarm, decision.pendingDismissTitles, smartWakeupWarning,
        lastBandAlarmSetAfter(state.lastBandAlarmSet, decision, now), decision.singleSlotResendsUsed
    )
}

/** Gadgetbridge's alarm list shows no numbers, so every owner-facing slot reference counts from 1, not from the exported table's 0. */
private const val SLOT_DISPLAY_OFFSET = 1

/**
 * The last minute this app really wrote to the band tonight. A DISMISS never clears it, because a DISMISS
 * never disarms the band (BandAlarmSingleSlotMode.kt) - this is exactly the value the morning report needs in
 * order to name what is still armed after the night ends. `internal`, not `private`, so it is JVM-testable.
 */
internal fun lastBandAlarmSetAfter(previous: BandAlarmCommitment?, decision: BandAlarmDecision, now: Instant): BandAlarmCommitment? {
    val lastSet = decision.commands.filterIsInstance<BandAlarmCommand.Set>().lastOrNull() ?: return previous
    return BandAlarmCommitment(lastSet.title, lastSet.hour, lastSet.minute, now)
}

private fun persistBandAlarmCommitmentWriteAhead(context: Context, state: NightState, decision: BandAlarmDecision, now: Instant) {
    if (decision.commands.isEmpty()) return
    val writeAheadState = state.copy(
        requestedBandAlarm = decision.requestedBandAlarm,
        confirmedBandAlarm = decision.confirmedBandAlarm,
        pendingDismissTitles = decision.pendingDismissTitles,
        lastBandAlarmSet = lastBandAlarmSetAfter(state.lastBandAlarmSet, decision, now),
        singleSlotResendsUsed = decision.singleSlotResendsUsed
    )
    if (!saveNightState(context, writeAheadState)) {
        appendNightLog(
            context, state.startedAt,
            NightLogEvent(Instant.now(), "error", mapOf("step" to "save_night_state", "cause" to "failed to persist the band alarm commitment write-ahead, before sending commands")),
            state.debugOptions.isAnyEnabled
        )
    }
}

/**
 * Always (re)schedules the phone alarm when the plan has one, so a scheduling failure is retried on every
 * tick instead of silently sticking - except an instant that is at or before [now] (Android fires a past
 * exact alarm immediately) or that already fired once ([NightState.phoneAlarmFiredFor]), which are never
 * (re)armed. "phone_alarm_set" is only logged when the target time actually changed; a scheduling failure is
 * logged as an error on every tick until it succeeds.
 */
private fun armPhoneAlarmIfNeeded(context: Context, state: NightState, previousPlan: AlarmPlan?, plan: AlarmPlan, now: Instant) {
    val debugNight = state.debugOptions.isAnyEnabled
    val phoneAlarm = plan.phoneAlarm
    if (phoneAlarm == null) {
        if (previousPlan?.phoneAlarm != null) cancelPhoneAlarm(context)
        return
    }
    if (phoneAlarm == state.phoneAlarmFiredFor) {
        return
    }
    if (!shouldArmPhoneAlarm(phoneAlarm, now, state.phoneAlarmFiredFor)) {
        appendNightLog(
            context, state.startedAt,
            NightLogEvent(now, "error", mapOf("step" to "phone_alarm", "cause" to "planned phone alarm $phoneAlarm is at or before now, not arming - Android fires a past exact alarm immediately")),
            debugNight
        )
        return
    }
    val armed = schedulePhoneAlarm(context, phoneAlarm)
    val changed = phoneAlarm != previousPlan?.phoneAlarm
    val event = when {
        !armed -> NightLogEvent(now, "error", mapOf("step" to "phone_alarm", "cause" to "exact alarm permission was likely revoked, will retry next tick"))
        changed -> NightLogEvent(now, "phone_alarm_set", mapOf("at" to phoneAlarm.toString()))
        else -> null
    }
    event?.let { appendNightLog(context, state.startedAt, it, debugNight) }
}

/** C4: at most this many extra FINISHED ticks are spent verifying that a dismissal queued on the terminal tick was really confirmed gone, before giving up and stopping for good. */
const val MAX_FINISHED_CLEANUP_TICKS = 6

/**
 * C4: how many of the ticks spent in [AlarmMode.FINISHED] so far were "extra" ones kept alive only to verify
 * a dismissal (not the terminal tick itself, which always runs once regardless). Resets to 0 the moment the
 * plan leaves FINISHED (a clock/timezone change can, in principle, un-finish a night) or is FINISHED for the
 * first time this tick.
 */
internal fun nextFinishedCleanupTickCount(mode: AlarmMode, previousMode: AlarmMode?, previousCount: Int): Int = when {
    mode != AlarmMode.FINISHED -> 0
    previousMode != AlarmMode.FINISHED -> 0
    else -> previousCount + 1
}

/**
 * C4: normally FINISHED means [nextSyncDelay] returns null and ticking stops outright - but a dismissal
 * queued on that very terminal tick can never be verified by read-back if nothing ticks again. Keeps ticking
 * at the engine's own [EngineConfig.normalSyncDelay] cadence while [pendingDismissTitles] is non-empty,
 * bounded by [MAX_FINISHED_CLEANUP_TICKS] so a permanently broken export cannot tick forever.
 */
internal fun finishedCleanupSyncDelay(pendingDismissTitles: Set<String>, cleanupTicksUsed: Int, config: EngineConfig): Duration? =
    if (pendingDismissTitles.isNotEmpty() && cleanupTicksUsed < MAX_FINISHED_CLEANUP_TICKS) config.normalSyncDelay else null

/** null from [nextSyncDelay] means the engine considers the night over: stop scheduling ticks, unless [finishedCleanupSyncDelay] (C4) says a queued dismissal still needs verifying. [config] comes from [resolveEngineConfig], so a fast debug night's tick cadence matches its own EngineConfig. */
private fun scheduleNextTick(context: Context, plan: AlarmPlan, pendingDismissTitles: Set<String>, cleanupTicksUsed: Int, now: Instant, config: EngineConfig) {
    val delay = nextSyncDelay(plan, now, config) ?: finishedCleanupSyncDelay(pendingDismissTitles, cleanupTicksUsed, config)
    if (delay == null) {
        cancelTick(context)
    } else {
        scheduleTick(context, now.plus(delay))
    }
}
