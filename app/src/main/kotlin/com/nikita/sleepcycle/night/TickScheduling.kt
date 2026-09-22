package com.nikita.sleepcycle.night

// File purpose: arms and cancels the next sync tick - either through AlarmManager's exact alarm, or, while the
// clock is warped, an in-process coroutine delay (T6). OutOfBedPreNudgeCheck.kt's own pre-nudge check (V2)
// shares the exact same in-process-vs-AlarmManager choice, [shouldScheduleTickInProcess] - see its own header.
//
// T6: AlarmManager only ever fires in real time and `setExactAndAllowWhileIdle` is Doze-throttled to roughly
// 9 minutes, so a tick that is only a handful of REAL seconds out (a virtual-minutes-away sync at a high
// simulation speed) would be throttled into uselessness rather than firing anywhere near on time.
//
// V3 REPLACES T6's own duration threshold ("in-process below 60 s of real delay") with the honest predicate:
// in-process if and only if the clock is warped ([AppClock.warp] != null), full stop. The duration threshold
// left 10x stranded - `normalSyncDelay` (15 virtual minutes) at 10x is 90 REAL seconds, above the old 60 s
// threshold, so it fell to the Doze-throttled AlarmManager path and could stretch to 9 real minutes, which is
// 90 virtual minutes of missed syncs. At 1x-unwarped, [AppClock.warp] is always null (U1 guarantees a warp can
// only be live while simulatedBandData is on, and a real night never sets that), so every tick keeps going
// through AlarmManager exactly as before - the property that must not regress.
//
// V3 also floors the in-process delay at [IN_PROCESS_TICK_MIN_DELAY]: without it, a tick whose real delay has
// already overrun itself (a very short or even negative gap, most likely right after a mid-night speed change
// - see NightController.rearmAfterSpeedChange) would call `delay(0)`, spinning through its own disk I/O in a
// tight loop with no gap between iterations at all.
//
// FIX2 (2026-09-21) briefly added a SECOND guard on top of the flooring above, and N5 REVERTED it. It made
// [scheduleTickInProcess] skip arming entirely whenever the most recently STARTED in-process tick had begun
// less than [IN_PROCESS_TICK_MIN_DELAY] ago, on the reasoning that such a tick "will call [scheduleTick] again
// itself once it finishes". That reasoning is circular: the call it suppressed was, in the overwhelmingly
// common case, that very tick's own terminal re-arm. A tick that finished within 250 ms of its own start
// therefore armed nothing, nothing else was left to arm anything, and the whole warped night's tick chain
// died on the spot - no further syncs, a countdown frozen at its last committed plan, and no alarm. The only
// in-app action that cleared the stamp was [cancelInProcessTick], reached from `rearmAfterSpeedChange`, which
// is exactly the change-the-speed-and-change-it-back recovery the owner found by hand.
//
// N5 (owner-reported, 2026-09-21): the guard is removed rather than repaired, because this file cannot answer
// the question the guard was really asking. "Is a tick still working" is not knowable here at all:
// `startNightServiceForTick` hands off to a foreground service and returns immediately, so the coroutine's own
// lifetime says nothing about when the tick's work ends. What remains is V3's floor, which already bounds the
// start rate at one tick per [IN_PROCESS_TICK_MIN_DELAY] (the next tick is armed at least that far from NOW,
// and the previous one started no later than now), and FIX1's transaction lock in NightOrchestrator.kt, which
// is what actually serializes two ticks whose work overlaps. Accepted cost: while a run of freshly-computed
// targets keeps landing in the virtual past, ticks run at up to 4 per real second until the run ends. That is
// bounded, transient, debug-only, and vastly preferable to a silent night - see docs/decisions.md (N5).
//
// Accept and document: a warped night dies if the process dies, since the in-process job has no reboot- or
// process-death-recovery of its own (unlike the AlarmManager path, which BootReceiver re-arms). That is fine -
// this is a debug feature, exercised at a desk with the app in the foreground, not something a real night ever
// depends on.

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

private const val LOG_TAG = "TickScheduling"
private const val TICK_REQUEST_CODE = 1001

/** PendingIntent extra carrying the tick's intended VIRTUAL instant, read back by TickReceiver and logged as `scheduledFor` (C1) - today's `scheduledFor` was just the actual firing time, which always reads as zero delay. */
const val EXTRA_SCHEDULED_FOR_EPOCH_MILLI = "scheduledForEpochMilli"

/** V3: floors the in-process tick's own real delay - see this file's own header for why. */
val IN_PROCESS_TICK_MIN_DELAY: Duration = Duration.ofMillis(250)

/** T6: process-scoped - a warped night's in-process tick dies with the process, on purpose (see this file's own header). */
private val tickScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** T6/V3: guards every read AND write of [inProcessTickJob], so a [scheduleTickInProcess]/[cancelTick] call's own cancel-then-replace step is atomic with respect to any other one - see this file's own header ("Assign inProcessTickJob..."). */
private val inProcessTickLock = Any()

/** T6: at most one pending in-process tick job at a time, cancel-and-replace on every [scheduleTick] call - only ever read or written under [inProcessTickLock]. */
private var inProcessTickJob: Job? = null

/** T6/V3: the pure AlarmManager-vs-in-process choice, extracted so it is testable without Android - see this file's own header for why it must stay a named predicate rather than an inline check, and why it is now keyed on [warped] rather than a duration. */
fun shouldScheduleTickInProcess(warped: Boolean): Boolean = warped

/** V3: [realDelay] floored at [IN_PROCESS_TICK_MIN_DELAY] - the actual delay [scheduleTickInProcess] waits, extracted so the floor itself is testable without Android. */
fun inProcessTickDelay(realDelay: Duration): Duration = if (realDelay < IN_PROCESS_TICK_MIN_DELAY) IN_PROCESS_TICK_MIN_DELAY else realDelay

/**
 * Arms the next tick for the VIRTUAL instant [at] (T4): converts to the real instant this actually fires at,
 * then picks AlarmManager or the in-process path per [shouldScheduleTickInProcess] on whether the clock is
 * currently warped. Always cancels both paths' own pending work first, so at most one of the two is ever armed
 * at once, whichever this call chooses.
 */
@SuppressLint("MissingPermission")
fun scheduleTick(context: Context, at: Instant) {
    val realAt = AppClock.toRealInstant(at)
    if (shouldScheduleTickInProcess(AppClock.warp() != null)) {
        // Taking the in-process path, so any AlarmManager tick left from the unwarped path must go.
        cancelAlarmManagerTick(context)
        scheduleTickInProcess(context, Duration.between(Instant.now(), realAt), at)
    } else {
        // W19 (review finding): this branch deliberately does NOT cancel first. `setExactAndAllowWhileIdle`
        // with FLAG_UPDATE_CURRENT replaces the pending alarm atomically, by PendingIntent identity, so a
        // cancel-then-arm only creates a window in which a REAL night has no tick armed at all - and if the
        // process is killed inside it, or the arm below throws the SecurityException it already anticipates,
        // the night loses every remaining tick with no recovery short of a reboot. The in-process job is still
        // cancelled unconditionally, since nothing replaces that one implicitly.
        cancelInProcessTick()
        armTickAlarmManager(context, realAt, at)
    }
}

@SuppressLint("MissingPermission")
private fun armTickAlarmManager(context: Context, realAt: Instant, at: Instant) {
    val alarmManager = context.getSystemService<AlarmManager>() ?: return
    try {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, realAt.toEpochMilli(), tickPendingIntent(context, at))
    } catch (error: SecurityException) {
        Log.e(LOG_TAG, "cannot schedule the next tick: exact alarm permission was likely revoked", error)
    }
}

/**
 * T6: cancel-and-replace - holds at most one pending job, so a rapid run of ticks (e.g. at a high simulation
 * speed) never stacks up several in-process delays racing to run the same tick. V3: the cancel-then-launch-
 * then-assign sequence runs entirely under [inProcessTickLock], so a nested [scheduleTick] call - one this same
 * coroutine's own eventual `startNightServiceForTick` can trigger, by way of a tick that itself re-arms the
 * next one - can never complete ITS OWN assignment first, only to have this (outer, now-stale) call overwrite
 * it a moment later: whichever call acquires the lock LAST wins, deterministically, rather than whichever
 * coroutine happens to finish running its own launch() call first.
 *
 * N5 (owner-reported, 2026-09-21): every call that gets here ARMS. There is deliberately no condition under
 * which this returns without a job pending, because on a warped night the caller is almost always the previous
 * tick booking its own successor: refusing that one call ends the night's tick chain outright, with nothing
 * left anywhere to restart it. FIX2's own skip-arming debounce did exactly that and was removed - see this
 * file's own header for what it was trying to prevent and what covers that instead.
 */
private fun scheduleTickInProcess(context: Context, realDelay: Duration, at: Instant) {
    synchronized(inProcessTickLock) {
        inProcessTickJob?.cancel()
        inProcessTickJob = tickScope.launch {
            delay(inProcessTickDelay(realDelay).toMillis())
            startNightServiceForTick(context, scheduledFor = at, receivedAt = nowInstant())
        }
    }
}

/** Cancels a pending tick, if any - both the AlarmManager alarm and T6's in-process job, since a caller cancelling a tick must never leave the other path still armed. The two are cancelled independently, so a missing AlarmManager service (should not happen) can never skip cancelling the in-process job. */
fun cancelTick(context: Context) {
    cancelAlarmManagerTick(context)
    cancelInProcessTick()
}

/** One half of [cancelTick], on its own so [scheduleTick] can cancel the path it is NOT about to arm without opening a window on the path it is - see its own comment. */
private fun cancelAlarmManagerTick(context: Context) {
    context.getSystemService<AlarmManager>()?.cancel(tickPendingIntent(context, at = null))
}

/**
 * The other half of [cancelTick]; nothing replaces an in-process job implicitly, so every arming path cancels
 * this one first.
 */
private fun cancelInProcessTick() {
    synchronized(inProcessTickLock) {
        inProcessTickJob?.cancel()
        inProcessTickJob = null
    }
}

/** [at] is embedded as an extra so TickReceiver can read back the intended VIRTUAL instant (C1, T4); omitted (null) when only used to build a cancellation target, since extras do not affect a PendingIntent's identity. */
private fun tickPendingIntent(context: Context, at: Instant?): PendingIntent {
    val intent = Intent(context, TickReceiver::class.java)
    at?.let { intent.putExtra(EXTRA_SCHEDULED_FOR_EPOCH_MILLI, it.toEpochMilli()) }
    return PendingIntent.getBroadcast(context, TICK_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
