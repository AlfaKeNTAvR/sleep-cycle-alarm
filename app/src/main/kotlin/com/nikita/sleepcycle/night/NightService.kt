package com.nikita.sleepcycle.night

// File purpose: foreground service that keeps the night alive, runs each tick under a wake lock, and
// shows a low-importance notification with the planned wake time. Any exception during a tick is logged
// and a fallback tick is scheduled, so one bad tick cannot silently end syncing for the rest of the night.

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.engine.AlarmMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val LOG_TAG = "NightService"
private const val NOTIFICATION_CHANNEL_ID = "night_tracking"
private const val NOTIFICATION_ID = 1
private const val WAKE_LOCK_TAG = "SleepCycleAlarm:NightTick"

/** Longer than the worst case a tick can take: the sync (60 s) and export (60 s) waits, plus time to copy and read the database. */
private val WAKE_LOCK_TIMEOUT: Duration = Duration.ofMinutes(5)
private val NOTIFICATION_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** Foreground service (type `specialUse`) that owns the overnight tracking lifetime: one tick per start command. */
class NightService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // No disk read here: loadNightState happens inside runNightTick, off the main thread. The
        // notification is refreshed with the real planned wake time as soon as that tick completes.
        startForeground(NOTIFICATION_ID, buildNotification(this, plannedWake = null))
        val scheduledForMillis = intent?.getLongExtra(EXTRA_SCHEDULED_FOR_EPOCH_MILLI, -1L) ?: -1L
        val scheduledFor = if (scheduledForMillis >= 0) Instant.ofEpochMilli(scheduledForMillis) else null
        val receivedAtMillis = intent?.getLongExtra(EXTRA_RECEIVED_AT_EPOCH_MILLI, -1L) ?: -1L
        val receivedAt = if (receivedAtMillis >= 0) Instant.ofEpochMilli(receivedAtMillis) else null
        runTickUnderWakeLock(startId, scheduledFor, receivedAt)
        return START_STICKY
    }

    private fun runTickUnderWakeLock(startId: Int, scheduledFor: Instant?, receivedAt: Instant?) {
        val wakeLock = acquireWakeLock(this)
        serviceScope.launch {
            try {
                val newState = runNightTick(this@NightService, Instant.now(), scheduledFor, receivedAt)
                handleTickResult(newState, startId)
            } catch (error: Exception) {
                handleTickFailure(error)
            } finally {
                releaseWakeLockSafely(wakeLock)
            }
        }
    }

    /** Publishes every committed state to [observedNightState] (D1) - the ViewModel must never miss a tick that ran from the service rather than from an immediate UI-requested tick. */
    private fun handleTickResult(newState: NightState?, startId: Int) {
        if (newState == null) {
            stopTracking(startId)
            return
        }
        publishNightState(newState)
        val noPhoneAlarmWarning = noPhoneAlarmTonight(newState.settings.deadline != null, newState.settings.phoneBackupEnabled)
        updateNotification(this, newState.lastPlan?.bandAlarm, newState.debugOptions, noPhoneAlarmWarning)
        if (newState.lastPlan?.mode == AlarmMode.FINISHED) {
            // Ticks have already stopped scheduling themselves (runNightTick cancels the tick alarm).
            // The state and the phone alarm stay in place; only the foreground tracking work is done.
            stopTracking(startId)
        }
    }

    /** Logs the failure to the night log and schedules a fallback tick, so one bad tick never silently ends syncing for the night. The fallback delay comes from [resolveEngineConfig] of the loaded state's own debug options, so a fast debug night keeps retrying at its own (faster) cadence. */
    private fun handleTickFailure(error: Exception) {
        Log.e(LOG_TAG, "night tick failed", error)
        val state = loadNightState(this)
        val debugOptions = state?.debugOptions ?: DebugOptions()
        state?.let {
            appendNightLog(this, it.startedAt, NightLogEvent(Instant.now(), "error", mapOf("step" to "tick", "cause" to (error.message ?: error.toString()))), debugOptions.isAnyEnabled)
        }
        scheduleTick(this, Instant.now().plus(resolveEngineConfig(debugOptions).normalSyncDelay))
    }

    private fun stopTracking(startId: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }
}

/** PendingIntent extra carrying when TickReceiver actually received the tick alarm, logged as `receivedAt` (C1). */
const val EXTRA_RECEIVED_AT_EPOCH_MILLI = "receivedAtEpochMilli"

/** Starts NightService (or wakes it) to run a tick; used by TickReceiver and by startNight for the first tick. [scheduledFor]/[receivedAt] are only known when TickReceiver triggered this (null for the very first tick or an immediate UI-requested one). */
fun startNightServiceForTick(context: Context, scheduledFor: Instant? = null, receivedAt: Instant? = null) {
    val intent = Intent(context, NightService::class.java)
    scheduledFor?.let { intent.putExtra(EXTRA_SCHEDULED_FOR_EPOCH_MILLI, it.toEpochMilli()) }
    receivedAt?.let { intent.putExtra(EXTRA_RECEIVED_AT_EPOCH_MILLI, it.toEpochMilli()) }
    context.startForegroundService(intent)
}

/** Stops NightService, ending overnight tracking. */
fun stopNightService(context: Context) {
    context.stopService(Intent(context, NightService::class.java))
}

private fun acquireWakeLock(context: Context): PowerManager.WakeLock {
    val powerManager = context.getSystemService<PowerManager>() ?: error("no PowerManager available")
    val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
    wakeLock.acquire(WAKE_LOCK_TIMEOUT.toMillis())
    return wakeLock
}

private fun releaseWakeLockSafely(wakeLock: PowerManager.WakeLock) {
    try {
        if (wakeLock.isHeld) wakeLock.release()
    } catch (error: RuntimeException) {
        Log.e(LOG_TAG, "failed to release the tick wake lock", error)
    }
}

private fun createNotificationChannel(context: Context) {
    val manager = context.getSystemService<NotificationManager>() ?: return
    manager.createNotificationChannel(NotificationChannel(NOTIFICATION_CHANNEL_ID, "Overnight tracking", NotificationManager.IMPORTANCE_LOW))
}

/**
 * A1: prefixes the notification text with every active debug switch's name, so a simulated night is never
 * mistaken for a real one from the notification shade alone. Item 4: [noPhoneAlarmWarning] appends the same
 * plain-English warning shown on Before bed and the Night screen, so the notification never disagrees with
 * either about whether the phone itself will ring tonight.
 */
private fun buildNotification(
    context: Context,
    plannedWake: Instant?,
    debugOptions: DebugOptions = DebugOptions(),
    noPhoneAlarmWarning: Boolean = false,
): Notification {
    val wakeLine = plannedWake?.let { "Planned wake time: ${NOTIFICATION_TIME_FORMAT.format(it)}" } ?: "Waiting for first sync"
    val warningSuffix = if (noPhoneAlarmWarning) " - ${context.getString(R.string.warning_no_phone_alarm)}" else ""
    return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
        .setContentTitle("Tracking your sleep")
        .setContentText(debugBannerPrefix(context, debugOptions) + wakeLine + warningSuffix)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setOngoing(true)
        .setSilent(true)
        .build()
}

private fun debugBannerPrefix(context: Context, debugOptions: DebugOptions): String {
    val labels = activeDebugSwitches(debugOptions).map { switch ->
        when (switch) {
            ActiveDebugSwitch.SIMULATED_SLEEP_DATA -> context.getString(R.string.debug_switch_simulated_sleep_data)
            ActiveDebugSwitch.BAND_COMMANDS_NOT_SENT -> context.getString(R.string.debug_switch_band_commands_not_sent)
            ActiveDebugSwitch.FAST_NIGHT -> context.getString(R.string.debug_switch_fast_night)
        }
    }
    return if (labels.isEmpty()) "" else "[${labels.joinToString(" · ")}] "
}

private fun updateNotification(
    context: Context,
    plannedWake: Instant?,
    debugOptions: DebugOptions = DebugOptions(),
    noPhoneAlarmWarning: Boolean = false,
) {
    val manager = context.getSystemService<NotificationManager>() ?: return
    manager.notify(NOTIFICATION_ID, buildNotification(context, plannedWake, debugOptions, noPhoneAlarmWarning))
}
