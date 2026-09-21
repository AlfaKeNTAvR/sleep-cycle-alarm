package com.nikita.sleepcycle.night

// File purpose: the UI-facing entry points - start/end a night, request a tick, and observe night state.
// endNight runs under the same withNightTransactionLock as every tick, so a tick that finishes after
// endNight can never resurrect state (runNightTick re-checks that a night is still active once it gets the lock).
// startNight computes the initial plan, arms the phone alarm from it, and saves state - all inside ONE lock
// acquisition - before the first real tick is even started, so there is no window where the tick's own work
// could be erased out of order (Opus review 3.2). D2: there is no band alarm left to dismiss on either end.

import android.content.Context
import com.nikita.sleepcycle.alarm.ALARM_STOP_REASON_NIGHT_ENDED
import com.nikita.sleepcycle.alarm.cancelOutOfBedAlarm
import com.nikita.sleepcycle.alarm.cancelPhoneAlarm
import com.nikita.sleepcycle.alarm.readAlarmNotificationReadiness
import com.nikita.sleepcycle.alarm.alarmLabelFor
import com.nikita.sleepcycle.alarm.schedulePhoneAlarm
import com.nikita.sleepcycle.alarm.scheduleOutOfBedAlarm
import com.nikita.sleepcycle.alarm.stopAlarmRinging
import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.computeAlarmPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private val nightStateFlow = MutableStateFlow<NightState?>(null)

/** The current night state for the UI to observe; updated by every call in this file. */
val observedNightState: StateFlow<NightState?> = nightStateFlow.asStateFlow()

/** Loads the persisted night state into [observedNightState] off the main thread; call once when the UI (re)attaches, e.g. after process death. */
fun refreshNightStateFromDisk(context: Context) {
    controllerScope.launch {
        nightStateFlow.value = withContext(Dispatchers.IO) { loadNightState(context) }
    }
}

/** Publishes a freshly committed state to [observedNightState] (D1) - the one seam NightService uses so a tick that ran from the service, not from an immediate UI-requested tick, still reaches the ViewModel. */
fun publishNightState(state: NightState) {
    nightStateFlow.value = state
}

/**
 * Begins a night as one ordered sequence, all under a single lock acquisition: computes the initial plan
 * from empty segments (decision 6 - a phone alarm must exist even if every sync later fails), arms the phone
 * alarm from it, saves state, and logs readiness - only once all of that has committed does it start the
 * tracking service, which runs the first real tick (with actual band data) on its own. [debugOptions]
 * (already passed through [resolveDebugOptions] by the caller) is captured into [NightState.debugOptions] for
 * the whole night and drives [resolveEngineConfig], so a fast debug night uses the same EngineConfig from its
 * very first plan onward.
 */
fun startNight(context: Context, settings: NightSettings, now: Instant, debugOptions: DebugOptions = DebugOptions()) {
    clearMorningReport(context)
    controllerScope.launch {
        withNightTransactionLock {
            // F8: a night that never reached endNight (both the state file and its backup failing to decode,
            // quarantining both) can leave PhoneAlarmReceiver's own fired/nap-count files behind from a PRIOR
            // night. clearNightState already does this when a night ends cleanly; starting one must too, or
            // the cap machinery would be live from tick 1 of a night whose own alarm has not fired. G4/H5: the
            // same reasoning applies to a pending out-of-bed nudge instant left over from that same failure -
            // it must not restore across a boot into a night it no longer belongs to. H5: clearing the record
            // alone is not enough - a nudge from the PRIOR night can still be armed in AlarmManager even when
            // its own record already failed to persist, so the alarm itself (and its H7.3 pre-check) must be
            // cancelled too, or it can still ring inside the new night.
            clearAlarmFiredStores(context)
            clearOutOfBedNudgePendingAt(context)
            cancelOutOfBedAlarm(context)
            cancelPreNudgeCheck(context)
            val zone = ZoneId.systemDefault()
            val initialPlan = computeAlarmPlan(
                emptyList(), settings, now, morningAlarmAt = null, zone, resolveEngineConfig(debugOptions),
                wakeAlarmFiredAt = null, napAlarmsUsed = 0, lastNapAlarmFiredAt = null
            )
            val state = NightState(
                startedAt = now,
                settings = settings,
                lastPlan = initialPlan,
                lastSyncAt = null,
                lastSyncOk = null,
                lastSegments = emptyList(),
                lastExportFileModifiedAt = null,
                lastSyncFailureCause = null,
                debugOptions = debugOptions,
                // H1: latched from this very first plan too, so a sliding AWAKE nap (rule 7, mid-night) later
                // this same night has a real morning alarm time to stop against from tick 1 onward.
                morningAlarmAt = latchMorningAlarmAt(previous = null, initialPlan)
            )
            // D1: the same guard used every tick and after a reboot - degenerate but possible with a deadline
            // already at or before "now" (a deadline always drives the phone alarm, in every mode).
            if (shouldArmPhoneAlarm(initialPlan.wakeAt, now, phoneAlarmFiredFor = null)) {
                val wakeAt = requireNotNull(initialPlan.wakeAt)
                schedulePhoneAlarm(context, wakeAt, alarmLabelFor(initialPlan.mode, wakeAt, state.morningAlarmAt))
            }
            if (!saveNightState(context, state)) {
                appendNightLog(context, now, NightLogEvent(now, "error", mapOf("step" to "save_night_state", "cause" to "failed to save the initial night state")), debugOptions.isAnyEnabled)
            }
            nightStateFlow.value = state
            appendNightLog(
                context, now,
                NightLogEvent(
                    now, "night_start",
                    // Every other instant in the log is UTC, and nothing else in the file says which zone the
                    // owner was actually in - so reading a log meant inferring the offset from the filename.
                    // Recorded at `now` rather than at read time, so a log written either side of a DST change
                    // keeps the offset that really applied to that night.
                    mapOf(
                        "pickedCycles" to settings.pickedCycles.toString(),
                        "deadline" to (settings.deadline?.toString() ?: "none"),
                        "simulatedBandData" to debugOptions.simulatedBandData.toString(),
                        "speed" to debugOptions.speed.toString()
                    ) + nightStartTimezoneFields(now, zone)
                ),
                debugOptions.isAnyEnabled
            )
            logAlarmReadiness(context, now, debugOptions.isAnyEnabled)
        }
        startNightServiceForTick(context)
    }
}

/** The night state ending produced, built inside the same lock that committed it - what the caller should render, not whatever snapshot it took before calling [endNight] (D1). Null when there was no night in progress to end. */
data class EndNightReport(val nightState: NightState, val endedAt: Instant)

/** D6: records [awakeConfirmedAt] onto [state] before endNight logs and snapshots it, or passes it through unchanged when null (every ending other than "I'm awake") or when there was no state to begin with. Pure, so it is directly testable without a Context. */
internal fun applyAwakeConfirmation(state: NightState?, awakeConfirmedAt: Instant?): NightState? =
    if (awakeConfirmedAt != null) state?.copy(awakeConfirmedAt = awakeConfirmedAt) else state

// F1: endNight used to take ~3 s for a verification sync inside the lock, with nothing on screen to show it
// was running, so the owner double-tapped "Stop night" on both real nights before that sync was removed along
// with the rest of the band alarm machinery (D2). The ViewModel still disables the button and shows an
// in-progress state on the first tap (see NightViewModel.confirmEndNight and ui/state/EndNightFlowState.kt) so
// a second tap should never reach here in the first place - but endNight itself stays idempotent, independent
// of the UI guard: a second concurrent call (any caller, not just a double tap) reuses the SAME in-flight (or
// just-completed) result rather than repeating the work a second time, which would otherwise log/save/clear a
// second time for one owner action.
private var endNightInFlight: Deferred<EndNightReport?>? = null
private val endNightInFlightGuard = Mutex()

/**
 * Ends the night: stops the alarm if it is ringing, cancels the phone alarm, the out-of-bed nudge (D4) and
 * future ticks, writes the summary, persists a morning-report snapshot of the state (so it survives leaving
 * and reopening the app once, even after process death), then clears the live state. Suspends until all of
 * that has committed and returns the definitive [EndNightReport] to render - a tick that finished a moment
 * before this call could have produced a state newer than whatever the caller last observed, so the caller
 * must render this return value, not its own earlier snapshot.
 *
 * [awakeConfirmedAt] is D6's "I'm awake" action on the ring screen recording [NightState.awakeConfirmedAt]
 * before the state is logged and snapshotted; the Night screen's "Stop night"/"I'm up, end night" - the only
 * other caller now that G1 no longer routes the engine reaching FINISHED (the deadline, or D5/G8's nap cap)
 * through this function at all, see [finishNightIfNeeded] - leaves it null, which is why this is the one
 * caller-supplied fact endNight itself applies to the loaded state, rather than requiring every caller to
 * persist it beforehand.
 *
 * F1: idempotent - a call made while a previous one is still running (or has just finished) is given that
 * same call's result instead of starting a second run. [now] and [awakeConfirmedAt] are only meaningful for
 * the run that actually starts the work; a call that joins an in-flight run gets that run's own values, not its own.
 */
suspend fun endNight(context: Context, now: Instant, awakeConfirmedAt: Instant? = null): EndNightReport? {
    val deferred = endNightInFlightGuard.withLock {
        val existing = endNightInFlight
        if (existing != null && existing.isActive) {
            existing
        } else {
            controllerScope.async { endNightLocked(context, now, awakeConfirmedAt) }.also { endNightInFlight = it }
        }
    }
    return deferred.await()
}

private suspend fun endNightLocked(context: Context, now: Instant, awakeConfirmedAt: Instant?): EndNightReport? = withNightTransactionLock {
    stopAlarmRinging(context, ALARM_STOP_REASON_NIGHT_ENDED)
    val loaded = withContext(Dispatchers.IO) { loadNightState(context) }
    val state = applyAwakeConfirmation(loaded, awakeConfirmedAt)
    if (state != null) {
        appendNightLog(context, state.startedAt, NightLogEvent(now, "night_end", nightEndFields(state, now)), state.debugOptions.isAnyEnabled)
        logNightClosingSummary(context, state, now)
        withContext(Dispatchers.IO) { saveMorningReport(context, MorningReportSnapshot(state, now)) }
    }
    cancelTick(context)
    cancelPhoneAlarm(context)
    cancelOutOfBedAlarm(context)
    // H7.3: the pre-nudge check has no job left once the night has ended - cancelled alongside the nudge
    // itself. Harmless even if it somehow still fired: runPreNudgeCheck's own first step is loadNightState,
    // which is already null by the time this function returns.
    cancelPreNudgeCheck(context)
    // G4: the owner ending the night is one of the two places the nudge's own pending-instant record is
    // cleared (the other is the nudge firing itself) - see OutOfBedNudgeStore.kt's own header for why
    // G1's FINISHED-bookkeeping path below must NOT do the same.
    clearOutOfBedNudgePendingAt(context)
    clearNightState(context)
    stopNightService(context)
    nightStateFlow.value = null
    // A1/T7: every debug switch (and any active clock warp) resets when a night ends, so a simulated night can
    // never leak into the next one just because the Debug screen was left as it was. T4: `changedAt` feeds the
    // REAL-world idle-reset guard (shouldAutoResetDebugOptions), so this is Instant.now() on purpose, not the
    // virtual `now` this function's every other write uses.
    resetDebugOptionsAndClock(context, Instant.now())
    state?.let { EndNightReport(it, now) }
}

/**
 * V5: whether a pending out-of-bed nudge, armed under the OLD [ClockWarp] mapping, should be re-armed after a
 * speed change - true exactly when one is pending AND its own virtual instant is still ahead of [now] (which
 * must already be recomputed under the NEW warp - see [rearmAfterSpeedChange]'s own doc on ordering). Mirrors
 * [BootReceiver]'s own `restorePendingOutOfBedNudge` convention for a reboot: an already-overdue pending nudge
 * is left alone rather than force-armed at whatever stale real instant AlarmManager still holds for it - the
 * same accepted trade-off, now applied to a speed change instead of a reboot. `internal`, not `private`: the
 * one pure decision seam, JVM-testable directly without a Context.
 */
internal fun shouldRearmPendingNudge(pendingNudgeAt: Instant?, now: Instant): Boolean =
    pendingNudgeAt != null && pendingNudgeAt.isAfter(now)

/**
 * V5: after a mid-night speed change has re-anchored [AppClock]'s own warp
 * ([com.nikita.sleepcycle.ui.DebugScreenController.setSpeed]), re-arms every outstanding virtual-time event that
 * was armed under the OLD mapping and does not self-heal on its own:
 *
 * - The wake alarm and the next tick DO self-heal, but only once the NEXT tick actually runs - which is itself
 *   armed on the stale mapping. [cancelTick] then [requestImmediateTick] forces that tick to happen right now,
 *   under the new mapping, replanning and re-arming both.
 * - The out-of-bed nudge and its own pre-nudge check are armed ONCE, when the alarm that started them fires
 *   (PhoneAlarmReceiver.armOutOfBedNudge), and never recomputed after that on their own - re-armed here from
 *   the nudge's own stored virtual instant ([readOutOfBedNudgePendingAt]) via [shouldRearmPendingNudge].
 *   AlarmManager replaces by request code, so re-arming an already-armed alarm is idempotent (see
 *   PhoneAlarmScheduler.kt).
 *
 * Order matters (the caller's own responsibility): [AppClock]'s warp must already be live before this runs, so
 * every `nowInstant()`/`AppClock.toRealInstant` call below sees the NEW mapping, never the one this speed
 * change just replaced.
 */
suspend fun rearmAfterSpeedChange(context: Context) {
    val now = nowInstant()
    val pendingNudgeAt = readOutOfBedNudgePendingAt(context)
    if (shouldRearmPendingNudge(pendingNudgeAt, now)) {
        val at = requireNotNull(pendingNudgeAt)
        scheduleOutOfBedAlarm(context, at)
        // resolveEngineConfig always returns the real EngineConfig regardless of debug options (T7), so reading
        // it plain here is equivalent and avoids a night-state load just for one duration.
        schedulePreNudgeCheck(context, at.minus(EngineConfig().preNudgeCheckLead))
    }
    cancelTick(context)
    requestImmediateTick(context)
}

/** Asks the tracking service to run one sync-plan cycle right away, e.g. when the user opens the Night screen. Goes through the same transaction lock as every other tick. */
fun requestImmediateTick(context: Context) {
    controllerScope.launch { runImmediateTick(context) }
}

/**
 * The awaitable body of [requestImmediateTick].
 *
 * W10 (owner-reported, 2026-09-20): a caller that arms a tick of its own AFTER asking for an immediate one -
 * the simulator's debounce re-tick, DebugScreenController.recordEvent, is the only one today - must await this
 * rather than fire [requestImmediateTick] and carry on. A tick re-arms the next tick itself, and tick
 * scheduling is cancel-and-replace, so anything armed while a tick is still in flight is thrown away the
 * moment that tick lands, leaving only the ordinary 5-to-15-minute cadence behind.
 *
 * Runs its own work on [Dispatchers.Default], the same dispatcher [requestImmediateTick]'s own scope uses, so
 * a caller awaiting this from the main thread (the Debug controller does) never blocks it on a tick's disk
 * reads and writes.
 */
suspend fun runImmediateTick(context: Context) = withContext(Dispatchers.Default) {
    val newState = runNightTick(context, nowInstant())
    if (newState != null) {
        nightStateFlow.value = newState
        finishNightIfNeeded(context, newState)
    }
}

/**
 * G1 SUPERSEDES the first fix round's own instruction that a plan reaching FINISHED must run the full
 * end-of-night path (F7) - that instruction was wrong. Reaching FINISHED means only that there is nothing
 * left for the engine to PLAN: it ends the night's own bookkeeping (persisted state, the tick alarm, the
 * tracking notification) but must leave any alarm sequence already in flight completely alone - a ringing
 * alarm keeps ringing until its own auto-stop or the owner's Stop, and an out-of-bed nudge that is already
 * armed still fires. On a deadline night this collision is systematic, not a rare race: the DEADLINE_ONLY
 * alarm fires exactly at the deadline and starts its ring, and the very next tick (up to a few minutes later,
 * not aligned to it) sees the deadline has passed and reaches FINISHED - running the old full end-of-night
 * path there cut the ring to under a minute roughly one morning in five, and cancelled the 09:10 nudge with
 * it. Only the owner ending the night themselves ([endNight], via "I'm awake" on the ring screen or ending it
 * from the Night screen) cancels a live ring and a pending nudge - their tap means "I am up", FINISHED does
 * not.
 *
 * Deliberately NOT routed through [endNight]'s own single-flight guard: that guard exists so two OWNER
 * actions (or a UI double-tap) collapse into one run sharing one intent (stop the ring, cancel the nudge).
 * This path has the opposite intent (leave both alone), so it must never let a same-moment owner tap join
 * ITS result instead of running its own - "I'm awake" must always actually stop the ring, whatever a
 * concurrent FINISHED tick is doing. Still runs under [withNightTransactionLock], so it cannot race a
 * concurrent tick's own read-modify-write of the state file, and tolerates the state already being gone (a
 * concurrent owner-ended call that got there first) by simply doing nothing. `internal`, not `private`:
 * NightService.kt's own tick path calls this too, so both places a tick's result surfaces end the night's
 * bookkeeping the same way.
 *
 * H3: every piece of bookkeeping below - including the suspending [writeDebugOptions] and publishing the null
 * state - runs BEFORE [stopNightService], not after. This function is invoked from NightService's own
 * `handleTickResult`, which runs inside that service's own `serviceScope`; [stopNightService] calls
 * `Context.stopService`, which tears the service down and cancels `serviceScope` in turn. Calling it before
 * the rest of the bookkeeping used to cancel THIS SAME coroutine at its own next suspension point
 * ([writeDebugOptions]), so the debug switches were never reset (a debug build's next night, started within
 * two hours, could silently inherit a 5-minute cycle length or simulated band data) - and the resulting
 * `CancellationException` reached [com.nikita.sleepcycle.night.NightService]'s own tick `catch (error:
 * Exception)` (CancellationException IS an Exception), which unconditionally scheduled a fallback tick 15 min
 * out for a night that no longer existed. [stopNightService] is now last precisely because nothing after it
 * needs this coroutine to still be alive.
 */
internal suspend fun finishNightIfNeeded(context: Context, state: NightState) {
    if (state.lastPlan?.mode != AlarmMode.FINISHED) return
    withNightTransactionLock {
        val loaded = withContext(Dispatchers.IO) { loadNightState(context) } ?: return@withNightTransactionLock
        // T4: virtual - see nightEndFields/logNightClosingSummary/MorningReportSnapshot's own doc.
        val now = nowInstant()
        appendNightLog(context, loaded.startedAt, NightLogEvent(now, "night_end", nightEndFields(loaded, now)), loaded.debugOptions.isAnyEnabled)
        logNightClosingSummary(context, loaded, now)
        withContext(Dispatchers.IO) { saveMorningReport(context, MorningReportSnapshot(loaded, now)) }
        // Bookkeeping only: no stopAlarmRinging, no cancelOutOfBedAlarm/clearOutOfBedNudgePendingAt, no
        // cancelPhoneAlarm - the tick that produced this FINISHED plan already cancelled the phone alarm's own
        // slot (armPhoneAlarmIfNeeded, plan.wakeAt == null) and the tick alarm (scheduleNextTick, nextSyncDelay
        // returns null for FINISHED) before finishNightIfNeeded was even called; cancelTick below is a cheap,
        // defensive repeat of that, not new work.
        cancelTick(context)
        clearNightState(context)
        nightStateFlow.value = null
        // A1/T7: see endNightLocked's own comment on resetDebugOptionsAndClock - same reasoning applies here.
        resetDebugOptionsAndClock(context, Instant.now())
        // H3: last on purpose - see this function's own doc.
        stopNightService(context)
    }
}

private fun logAlarmReadiness(context: Context, now: Instant, debugNight: Boolean) {
    val readiness = readAlarmNotificationReadiness(context)
    appendNightLog(
        context, now,
        NightLogEvent(
            now, "alarm_readiness",
            mapOf(
                "canUseFullScreenIntent" to readiness.canUseFullScreenIntent.toString(),
                "notificationsEnabled" to readiness.notificationsEnabled.toString(),
                "alarmChannelBlocked" to readiness.alarmChannelBlocked.toString(),
                "alarmChannelDowngraded" to readiness.alarmChannelDowngraded.toString()
            )
        ),
        debugNight
    )
}

/**
 * The night's whole summary, written into night_end as structured fields (see PastNightLog.kt) so the Logs
 * screen can redraw this night later from its log alone - the old single `summary=total=... stretches=...`
 * line said how much was slept but not when, which is not enough to draw a morning report.
 */
private fun nightEndFields(state: NightState, now: Instant): Map<String, String> =
    encodeNightEndFields(buildNightEngineView(state, now).summary, state.settings)

/**
 * The `night_summary` event, written straight after `night_end` on both paths that end a night. `night_end`
 * records the raw shape of the night; this records the questions a reader actually asks of it afterwards -
 * how long it took to fall asleep, how long each awakening lasted, and above all how long the owner stayed
 * in bed after the wake alarm rang. Those were all derivable from the old log only by computing gaps between
 * stretches by hand and cross-referencing them against alarm timestamps.
 */
private suspend fun logNightClosingSummary(context: Context, state: NightState, now: Instant) {
    val summary = buildNightClosingSummary(
        startedAt = state.startedAt,
        segments = state.lastSegments,
        endedAt = now,
        config = resolveEngineConfig(state.debugOptions),
        wakeAlarmFiredAt = state.wakeAlarmFiredAt
    )
    appendNightLog(
        context, state.startedAt,
        NightLogEvent(now, "night_summary", encodeNightClosingSummaryFields(summary)),
        state.debugOptions.isAnyEnabled
    )
}
