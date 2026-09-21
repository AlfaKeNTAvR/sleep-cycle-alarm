package com.nikita.sleepcycle.night

// File purpose: H7.3 (owner decision, 2026-09-20) - [EngineConfig.preNudgeCheckLead] (2 min real, shorter in a
// fast debug night) before the out-of-bed nudge is due to ring, a silent background check asks whether another
// alarm is actually armed and still ahead of now - the thing that would genuinely take the wake-up over from
// the nudge. Armed and ahead: the nudge is cancelled, that other alarm owns the wake-up. Anything else - no
// plan target, or one that already fired or has already passed - leaves the nudge ringing untouched: a nudge
// the owner did not need is a far smaller harm than one they needed and never got (owner's own framing, H7.3).
//
// M1 (owner-reported, 2026-09-21) REPLACES H7.3's original premise outright, rather than patching it. The
// original check asked "is the owner asleep right now" and cancelled on a confirmed-ASLEEP re-sync, on the
// theory that a genuine return to sleep hands the wake-up to the nap logic (H7.2). That theory is false
// whenever there was no awakening in between: the owner's own night log
// (files/nightlogs/night-sim-20260921-1719.jsonl, 2026-09-21) shows the wake alarm firing at 01:56, "stop"
// pressed at 02:00:43 without "I'm awake", the band reading ASLEEP on every single tick with
// awakeningCount/awakeningsAfterWakeAlarmCount staying 0 for the whole night, and the pre-check at 02:12:01
// cancelling the nudge on exactly that ASLEEP reading - handing the wake-up to a nap rule 7 can never arm
// without an awakening first, so nothing ever rang again. Being asleep was only ever meant as a PROXY for
// "something else will take over"; M1 asks that question directly instead - [shouldCancelNudgeForPreCheck] now
// reuses NightOrchestrator.shouldArmPhoneAlarm's own "armed and still ahead" predicate against
// [NightState.lastPlan]'s own `wakeAt` and the freshest `phoneAlarmFiredFor` marker, the same positive-evidence
// test every other arming decision in this codebase already trusts, rather than predicting whether a nap WOULD
// be armed on a fresh replan - a predicted nap that then failed to actually arm would reopen this exact bug, so
// only a genuinely armed target counts. See docs/decisions.md's M1 record for the owner's own reasoning and why
// this reading is deliberately stricter than his own phrasing ("would a nap alarm actually be set").
//
// M1 also removes the band re-sync this check used to do on every run: the decision above never reads sleep
// state at all any more, so re-syncing bought nothing for it - see [shouldCancelNudgeForPreCheck]'s own doc for
// the full accounting. U6's simulated-timeline seam goes with it: a simulated night now reaches the exact same
// decision as a real one, from the exact same on-disk state (`NightState.lastPlan`/`phoneAlarmFiredFor`, both
// populated identically on a real or simulated night), so nothing here needs a special case to be exercisable
// at a desk any more.
//
// Runs as its own tiny foreground service, mirroring NightService.kt's own tick pattern - kept even though the
// decision itself is now a fast disk read with no band I/O at all, since a future check could again need work
// too slow for a plain BroadcastReceiver's own execution budget (contrast BootReceiver.kt, whose goAsync work
// is all fast disk/AlarmManager calls). [PRE_NUDGE_CHECK_TIMEOUT] still bounds the whole check well inside its
// own lead time; the fail-open rule above already covers a check that does not finish in time.
// [shouldCancelNudgeForPreCheck] is the one pure decision seam, so the whole "when does this cancel the
// nudge" logic is JVM-testable without Android.
//
// V2: [schedulePreNudgeCheck] shares T6/V3's own two-path shape ([shouldScheduleTickInProcess]) with
// TickScheduling.kt. It used to always go through AlarmManager's `setExactAndAllowWhileIdle` - the Doze-
// THROTTLED call - while the nudge it races arms through the Doze-EXEMPT `setAlarmClock`. At 600x that leaves
// it 1.3 REAL seconds to be delivered, start a foreground service, read night state off disk and decide: it
// systematically loses that race, so the check would never even get to run its own decision before the nudge
// fires. While the clock is warped, the check instead runs from an in-process coroutine timer that calls
// [runPreNudgeCheck] directly - no foreground service, no wake lock: a warped night is by definition being
// watched with the app open (see this file's own T6-derived reasoning). Real (unwarped) nights keep the exact
// AlarmManager-plus-foreground-service path they always had.

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.alarm.cancelOutOfBedAlarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant

private const val LOG_TAG = "OutOfBedPreNudgeCheck"
private const val PRE_NUDGE_CHECK_REQUEST_CODE = 2004
private const val NOTIFICATION_ID = 3
private const val NOTIFICATION_CHANNEL_ID = "night_tracking"
private val PRE_NUDGE_CHECK_TIMEOUT: Duration = Duration.ofSeconds(90)
private val WAKE_LOCK_TIMEOUT: Duration = Duration.ofMinutes(2)
private const val WAKE_LOCK_TAG = "SleepCycleAlarm:PreNudgeCheck"

/** V2: process-scoped, same lifetime rule as TickScheduling.kt's own in-process job - dies with the process, which is fine, this only ever runs while warped (a debug feature, see this file's own header). */
private val preNudgeCheckScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** V2: guards every read AND write of [inProcessPreNudgeCheckJob] - same reasoning as TickScheduling.kt's own [inProcessTickLock]. */
private val inProcessPreNudgeCheckLock = Any()

/** V2: at most one pending in-process pre-nudge check at a time, cancel-and-replace - only ever read or written under [inProcessPreNudgeCheckLock]. */
private var inProcessPreNudgeCheckJob: Job? = null

/**
 * Arms the silent pre-nudge check for the virtual instant [at] - through AlarmManager on a real night, or (V2)
 * an in-process coroutine timer while the clock is warped, per [shouldScheduleTickInProcess] (shared with
 * TickScheduling.kt, see this file's own header). Returns whether scheduling succeeded - a failure here is not
 * fatal, see this file's own header: the nudge still rings on schedule either way. T5: AlarmManager only ever
 * fires in real time, so [at] is converted through [AppClock.toRealInstant] on that path - one of T5's three
 * named conversion points.
 */
@SuppressLint("MissingPermission")
fun schedulePreNudgeCheck(context: Context, at: Instant): Boolean {
    if (shouldScheduleTickInProcess(AppClock.warp() != null)) {
        scheduleInProcessPreNudgeCheck(context, at)
        return true
    }
    val alarmManager = context.getSystemService<AlarmManager>() ?: return false
    return try {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, AppClock.toRealInstant(at).toEpochMilli(), preNudgeCheckPendingIntent(context))
        true
    } catch (error: SecurityException) {
        Log.e(LOG_TAG, "cannot schedule the pre-nudge check: exact alarm permission was likely revoked", error)
        false
    }
}

/** V2: cancel-and-replace, same pattern as TickScheduling.scheduleTickInProcess - calls [runPreNudgeCheck] directly once the delay elapses, no foreground service or wake lock (see this file's own header for why neither is needed here). */
private fun scheduleInProcessPreNudgeCheck(context: Context, at: Instant) {
    val delayMillis = inProcessTickDelay(Duration.between(Instant.now(), AppClock.toRealInstant(at))).toMillis()
    synchronized(inProcessPreNudgeCheckLock) {
        inProcessPreNudgeCheckJob?.cancel()
        inProcessPreNudgeCheckJob = preNudgeCheckScope.launch {
            delay(delayMillis)
            runPreNudgeCheck(context)
        }
    }
}

/** Cancels a pending pre-nudge check, if any - both the AlarmManager alarm and V2's in-process job, called everywhere the nudge itself is cancelled (H7.2's nap supersession, endNight, startNight's own leftover-nudge cleanup). */
fun cancelPreNudgeCheck(context: Context) {
    // An unavailable AlarmManager must not skip the in-process cancel below, so this is a safe call rather
    // than an early return - the same shape cancelTick already uses, and for the same reason.
    context.getSystemService<AlarmManager>()?.cancel(preNudgeCheckPendingIntent(context))
    synchronized(inProcessPreNudgeCheckLock) {
        inProcessPreNudgeCheckJob?.cancel()
        inProcessPreNudgeCheckJob = null
    }
}

private fun preNudgeCheckPendingIntent(context: Context): PendingIntent =
    PendingIntent.getBroadcast(
        context, PRE_NUDGE_CHECK_REQUEST_CODE, Intent(context, OutOfBedPreNudgeCheckReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

/** Entry point for the pre-nudge check's own exact alarm: starts [OutOfBedPreNudgeCheckService] to run the actual check, since a future check could again need work too slow for a plain receiver (see this file's own header). */
class OutOfBedPreNudgeCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            context.startForegroundService(Intent(context, OutOfBedPreNudgeCheckService::class.java))
        } catch (error: Exception) {
            Log.e(LOG_TAG, "failed to start the pre-nudge check service - the nudge will still ring on schedule", error)
        }
    }
}

/** Foreground service (type `specialUse`) that runs the pre-nudge check under a wake lock, bounded by [PRE_NUDGE_CHECK_TIMEOUT] - see this file's own header for the fail-open rule a timeout falls under. */
class OutOfBedPreNudgeCheckService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Idempotent: NightService.kt already creates this same channel at the start of every night, but this
        // service can in principle be reached without that having happened yet, and creating an existing
        // channel again is a harmless no-op.
        val manager = getSystemService<NotificationManager>()
        manager?.createNotificationChannel(NotificationChannel(NOTIFICATION_CHANNEL_ID, "Overnight tracking", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildPreNudgeCheckNotification(this))
        val wakeLock = acquirePreNudgeCheckWakeLock(this)
        serviceScope.launch {
            try {
                val completed = withTimeoutOrNull(PRE_NUDGE_CHECK_TIMEOUT.toMillis()) { runPreNudgeCheck(this@OutOfBedPreNudgeCheckService) }
                if (completed == null) Log.w(LOG_TAG, "pre-nudge check timed out - the nudge will still ring on schedule")
            } catch (error: Exception) {
                Log.e(LOG_TAG, "pre-nudge check failed - the nudge will still ring on schedule", error)
            } finally {
                releasePreNudgeCheckWakeLockSafely(wakeLock)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }
}

/**
 * The actual check (M1): cancels the nudge only when the night's own current plan has a live alarm still armed
 * and ahead of [now] - anything else (no plan target, or one that already fired or has already passed) leaves
 * the nudge alone. Pure disk I/O only now, no AlarmManager and no band sync (see this file's own M1 header
 * note), so it is not itself unit-tested directly - [shouldCancelNudgeForPreCheck] below is the pure decision
 * seam tests use instead.
 */
private suspend fun runPreNudgeCheck(context: Context) {
    // T4: virtual - nowInstant() correctly recovers the intended virtual instant even though the exact alarm
    // that triggered this check fired at a T5-converted REAL instant.
    val now = nowInstant()
    val state = withContext(Dispatchers.IO) { loadNightState(context) } ?: return
    val plannedWakeAt = state.lastPlan?.wakeAt
    if (!shouldCancelNudgeForPreCheck(now, plannedWakeAt, state.phoneAlarmFiredFor)) {
        appendNightLog(
            context, state.startedAt,
            NightLogEvent(now, "pre_nudge_check", mapOf("armedWakeAt" to (plannedWakeAt?.toString() ?: "none"), "result" to "rings")),
            state.debugOptions.isAnyEnabled
        )
        return
    }
    cancelOutOfBedAlarm(context)
    clearOutOfBedNudgePendingAt(context)
    appendNightLog(
        context, state.startedAt,
        NightLogEvent(now, "pre_nudge_check", mapOf("armedWakeAt" to plannedWakeAt.toString(), "result" to "cancelled")),
        state.debugOptions.isAnyEnabled
    )
}

/**
 * M1's one pure decision (owner-reported, 2026-09-21, REPLACES H7.3's own confirmed-ASLEEP test and SUBSUMES
 * L2.2's separate FINISHED carve-out - see this file's own M1 header note and docs/decisions.md's M1 record for
 * the full reasoning). Cancel the pending nudge only when the night's own current plan has a live alarm still
 * ahead of [now] - exactly NightOrchestrator.shouldArmPhoneAlarm's own "armed and still ahead" test, reused
 * directly rather than restated, so the pre-check can never disagree with the one mechanism that actually
 * decides what is armed on the phone. [plannedWakeAt] is [NightState.lastPlan]'s own `wakeAt` - the night's own
 * CURRENT plan, never a fresh prediction of what a replan would produce: the owner's own phrasing asked whether
 * a nap alarm "would actually be set", but a predicted alarm that then fails to actually arm would reopen the
 * exact silence this fix closes, so this is the strictly safer reading - an alarm that is genuinely armed
 * cannot evaporate. [phoneAlarmFiredFor] is the freshest fired marker ([NightState.phoneAlarmFiredFor], merged
 * from disk on every [loadNightState] call - see PhoneAlarmFiredStore.kt), so a target that already fired is
 * never mistaken for one still coming.
 *
 * L2.2's own FINISHED carve-out needs no restating here: a FINISHED plan's `wakeAt` is always null
 * ([com.nikita.sleepcycle.engine.computeWakeAlarm] returns null exactly when the rule is FINISHED), so
 * [plannedWakeAt] is null on every FINISHED night and this already returns false - the same answer L2.2
 * special-cased by hand on `mode`. Folded in rather than kept alongside: the two were never two different
 * rules, only two ways of noticing the same fact (nothing is armed on a FINISHED night).
 *
 * Being asleep, by itself, is no longer read here at all - it was only ever a PROXY for "something else will
 * take over", and a proxy that can read true with nothing actually armed (the owner's own traced night: the
 * wake alarm fired, he silenced it without pressing "I'm awake", the band read ASLEEP the whole time with no
 * awakening ever recorded, and the old proxy cancelled the nudge into total silence) is exactly the bug M1
 * replaces. If the owner never pressed "I'm awake" and nothing else is genuinely armed to take over, the nudge
 * rings - L1's own rule, unconditionally now.
 */
internal fun shouldCancelNudgeForPreCheck(now: Instant, plannedWakeAt: Instant?, phoneAlarmFiredFor: Instant?): Boolean =
    shouldArmPhoneAlarm(plannedWakeAt, now, phoneAlarmFiredFor)

private fun buildPreNudgeCheckNotification(context: Context) =
    NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
        .setContentTitle("Tracking your sleep")
        .setContentText("Checking before the out-of-bed alarm")
        .setSmallIcon(R.mipmap.ic_launcher)
        .setOngoing(true)
        .setSilent(true)
        .build()

private fun acquirePreNudgeCheckWakeLock(context: Context): PowerManager.WakeLock {
    val powerManager = context.getSystemService<PowerManager>() ?: error("no PowerManager available")
    val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
    wakeLock.acquire(WAKE_LOCK_TIMEOUT.toMillis())
    return wakeLock
}

private fun releasePreNudgeCheckWakeLockSafely(wakeLock: PowerManager.WakeLock) {
    try {
        if (wakeLock.isHeld) wakeLock.release()
    } catch (error: RuntimeException) {
        Log.e(LOG_TAG, "failed to release the pre-nudge check wake lock", error)
    }
}
