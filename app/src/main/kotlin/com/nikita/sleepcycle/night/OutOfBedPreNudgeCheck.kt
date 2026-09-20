package com.nikita.sleepcycle.night

// File purpose: H7.3 (owner decision, 2026-09-20) - [EngineConfig.preNudgeCheckLead] (2 min real, shorter in a
// fast debug night) before the out-of-bed nudge is due to ring, a silent background check re-syncs the band
// and asks whether the owner is asleep RIGHT NOW, not merely what the last tick happened to know (which can
// be minutes stale - NightOrchestrator's H7.2 nap-supersedes-nudge check acts on exactly that stale data,
// deliberately; this is the owner's own, deliberately stronger, second line of defence). Asleep: the nudge is
// cancelled, the nap the owner is already in owns the wake-up. Anything else - awake, no data at all yet, a
// sync that failed, timed out, or returned only stale data - lets the nudge ring untouched: a nudge the owner
// did not need is a far smaller harm than one they needed and never got (owner's own framing, H7.3).
//
// Runs as its own tiny foreground service, mirroring NightService.kt's own tick pattern: a real band sync can
// take up to ~2 min (see NightOrchestrator.syncOrFail's own doc), too long to risk inside a plain
// BroadcastReceiver (contrast BootReceiver.kt, whose goAsync work is all fast disk/AlarmManager calls).
// [PRE_NUDGE_CHECK_TIMEOUT] bounds the whole check well inside its own lead time, so a slow or hung sync can
// never itself delay or block the nudge - the fail-open rule above already covers exactly this case.
// [shouldCancelNudgeForPreCheck] is the one pure decision seam, so the whole "when does this cancel the
// nudge" logic is JVM-testable without Android.
//
// U6: "re-syncs the band" above means the real band only on a real night. On a simulated night the check
// reads the Debug screen's simulated timeline instead - see [readFreshSleepStateOrNull] for why the real
// sync cannot work there.
//
// V2: [schedulePreNudgeCheck] shares T6/V3's own two-path shape ([shouldScheduleTickInProcess]) with
// TickScheduling.kt. It used to always go through AlarmManager's `setExactAndAllowWhileIdle` - the Doze-
// THROTTLED call - while the nudge it races arms through the Doze-EXEMPT `setAlarmClock`. At 600x that leaves
// it 1.3 REAL seconds to be delivered, start a foreground service, read night state off disk and read two
// DataStore flows: it systematically loses that race, so it always rings, and U6's whole point - making the
// cancel path exercisable in debug mode - was unmet. While the clock is warped, the check instead runs from an
// in-process coroutine timer that calls [runPreNudgeCheck] directly - no foreground service, no wake lock: a
// warped night is by definition being watched with the app open (see this file's own T6-derived reasoning), and
// U1 guarantees [readFreshSleepStateOrNull] can only take its simulated-timeline branch while warped (a
// DataStore read, not a real sync), so there is no slow I/O here to bound with a timeout the way the
// AlarmManager path's own foreground service bounds a real sync. Real (unwarped) nights keep the exact
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
import com.nikita.sleepcycle.bridge.BandDataResult
import com.nikita.sleepcycle.bridge.checkDataFreshness
import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.engine.SleepState
import com.nikita.sleepcycle.engine.detectSleepState
import com.nikita.sleepcycle.engine.normalizeSegments
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

/** Entry point for the pre-nudge check's own exact alarm: starts [OutOfBedPreNudgeCheckService] to do the actual sync and decide, since the work can be too slow for a plain receiver (see this file's own header). */
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
 * The actual check: re-syncs the band, and cancels the nudge only on a confirmed fresh ASLEEP reading -
 * anything else (no night in progress any more, a failed or stale sync, still awake, no data at all) leaves
 * the nudge alone. Needs a real Context (disk I/O, AlarmManager), so it is not itself unit-tested directly -
 * [shouldCancelNudgeForPreCheck] below is the pure decision seam tests use instead.
 */
private suspend fun runPreNudgeCheck(context: Context) {
    // T4: virtual - nowInstant() correctly recovers the intended virtual instant even though the exact alarm
    // that triggered this check fired at a T5-converted REAL instant.
    val now = nowInstant()
    val state = withContext(Dispatchers.IO) { loadNightState(context) } ?: return
    val appSettings = readAppSettings(context).first()
    val sleepState = readFreshSleepStateOrNull(context, appSettings, state, now)
    if (!shouldCancelNudgeForPreCheck(sleepState)) {
        appendNightLog(
            context, state.startedAt,
            NightLogEvent(now, "pre_nudge_check", mapOf("outcome" to (sleepState?.name ?: "unknown"), "result" to "rings")),
            state.debugOptions.isAnyEnabled
        )
        return
    }
    cancelOutOfBedAlarm(context)
    clearOutOfBedNudgePendingAt(context)
    appendNightLog(
        context, state.startedAt,
        NightLogEvent(now, "pre_nudge_check", mapOf("outcome" to SleepState.ASLEEP.name, "result" to "cancelled")),
        state.debugOptions.isAnyEnabled
    )
}

/**
 * A fresh sleep state from a re-sync, or null when the sync failed or its result is stale - the caller treats
 * null exactly like AWAKE (let the nudge ring).
 *
 * U6: on a simulated night this reads the Debug screen's own timeline rather than re-syncing the band. H7.3
 * originally made this check deliberately real, as a second line of defence against stale tick data, but a
 * simulated night has no real band data behind it at all: `now` is virtual while the band's samples carry
 * real timestamps, so [checkDataFreshness] would always read stale, [readFreshSleepStateOrNull] would always
 * return null, and the nudge would ring every time. That is safe (fail-open) but it makes the pre-nudge
 * cancel path - the very behaviour the owner asked for - impossible to exercise in debug mode. The simulated
 * timeline needs no freshness check of its own: [buildSimulatedSegments] always extends its last segment to
 * [now], so it is fresh by construction.
 */
private suspend fun readFreshSleepStateOrNull(context: Context, appSettings: AppSettings, state: NightState, now: Instant): SleepState? {
    if (state.debugOptions.simulatedBandData) {
        return simulatedSleepStateAt(readSimulatedSleepEvents(context).first(), now, resolveEngineConfig(state.debugOptions))
    }
    val syncResult = syncOrFail(context, appSettings, state, now)
    val segments = when (syncResult) {
        is BandDataResult.Failure -> return null
        is BandDataResult.Success -> {
            val freshness = checkDataFreshness(syncResult.newestSampleAt, now, syncResult.exportFileModifiedAt, state.lastExportFileModifiedAt)
            if (!freshness.isFresh) return null
            syncResult.segments
        }
    }
    val config = resolveEngineConfig(state.debugOptions)
    return detectSleepState(normalizeSegments(segments, now, config))
}

/**
 * U6's pure seam: the sleep state the Debug screen's simulated timeline implies at [now], or null when the
 * timeline is still empty - which the caller treats exactly like AWAKE, so an untouched simulator never
 * cancels a nudge.
 */
internal fun simulatedSleepStateAt(events: List<SimulatedSleepEvent>, now: Instant, config: EngineConfig): SleepState? {
    val segments = buildSimulatedSegments(events, now)
    if (segments.isEmpty()) return null
    return detectSleepState(normalizeSegments(segments, now, config))
}

/**
 * H7.3's one pure decision: cancel the nudge only for a confirmed ASLEEP reading. `null` (sync failed, timed
 * out, or its result was stale) and [SleepState.AWAKE]/[SleepState.NOT_YET_ASLEEP] all mean the same thing -
 * "not confidently asleep" - and all let the nudge ring, per the owner's own fail-open framing (H7.3): a nudge
 * that did not need to ring is a far smaller harm than one that was needed and never rang.
 */
internal fun shouldCancelNudgeForPreCheck(sleepState: SleepState?): Boolean = sleepState == SleepState.ASLEEP

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
