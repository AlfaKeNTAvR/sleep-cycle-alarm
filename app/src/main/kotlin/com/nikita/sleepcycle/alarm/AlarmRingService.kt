package com.nikita.sleepcycle.alarm

// File purpose: foreground service that rings the phone alarm - sound, vibration, full-screen notification -
// until stopped from AlarmActivity or after an auto-stop timeout. startRinging is idempotent: a second start
// command while already ringing does nothing rather than overwriting a live MediaPlayer without releasing
// it. stopRinging guards each step so a failure stopping the sound can never skip stopping vibration,
// releasing the wake lock, or stopSelf.
//
// stopAlarmRinging is also called unconditionally by endNight, every time a night ends, whether or not the
// alarm ever rang - starting the service just to ask it to stop, when it was never running, used to log a
// misleading "alarm_stopped" line for a stop that never happened. stopRinging now only logs when [isRinging]
// was actually true on THIS instance, which is only ever the case when the service was already running and
// ringing; a reason distinguishes the ringing alarm's own Stop button/notification action from endNight
// stopping a (possibly non-ringing) alarm because the night itself ended.

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.CombinedVibration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.night.NightLogEvent
import com.nikita.sleepcycle.night.appendToCurrentNightLog
import java.time.Duration
import java.time.Instant

private const val LOG_TAG = "AlarmRingService"
private const val NOTIFICATION_ID = 2
private const val ACTION_STOP = "com.nikita.sleepcycle.alarm.action.STOP"
private const val EXTRA_STOP_REASON = "stopReason"

/** The ringing alarm's own Stop button, tapped from the notification or from AlarmActivity. */
const val ALARM_STOP_REASON_BUTTON = "stop_pressed"

/** [AUTO_STOP_AFTER] elapsed with nobody stopping it. */
const val ALARM_STOP_REASON_AUTO = "auto_stop"

/** The night itself ended (see NightController.endNight) - never the ringing alarm's own Stop button, whether or not it was actually ringing at the time. */
const val ALARM_STOP_REASON_NIGHT_ENDED = "night_ended"
private const val ALARM_ACTIVITY_REQUEST_CODE = 3001
private const val ALARM_STOP_REQUEST_CODE = 3002
private val VIBRATION_PATTERN = longArrayOf(0, 800, 500)

/** How long the alarm rings before auto-stopping; also the timeout on the wake lock PhoneAlarmReceiver acquires. */
val AUTO_STOP_AFTER: Duration = Duration.ofMinutes(10)

/** Foreground service (type `mediaPlayback`, falls back to `specialUse`) that owns the ringing alarm. */
class AlarmRingService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private var isRinging = false
    private val stopHandler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable { stopRinging(ALARM_STOP_REASON_AUTO) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val reason = intent.getStringExtra(EXTRA_STOP_REASON) ?: ALARM_STOP_REASON_BUTTON
            stopRinging(reason)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildAlarmNotification(this))
        startRinging()
        return START_NOT_STICKY
    }

    /** Idempotent: called again while already ringing (e.g. a duplicate delivery) does nothing. */
    private fun startRinging() {
        if (isRinging) return
        isRinging = true
        appendToCurrentNightLog(
            this,
            NightLogEvent(Instant.now(), "alarm_ring_started", mapOf("fullScreenIntentAllowed" to canUseFullScreenIntent(this).toString()))
        )
        val chosen = startAlarmSound(this)
        mediaPlayer = chosen.player
        appendToCurrentNightLog(
            this,
            NightLogEvent(
                Instant.now(), "alarm_sound_chosen",
                mapOf("source" to chosen.source, "failedSources" to chosen.failedSources.joinToString(","))
            )
        )
        vibrate(this)
        stopHandler.postDelayed(stopRunnable, AUTO_STOP_AFTER.toMillis())
    }

    /**
     * Only logs `alarm_stopped` when this instance was actually ringing (isRinging is only ever true when the
     * service was already running and ringing before this call, since a fresh instance starts with it false).
     * Otherwise this is a no-op stop - e.g. endNight calling [stopAlarmRinging] on a night where the alarm never
     * fired - and must log nothing, so the night log never claims an alarm was stopped that was never ringing.
     */
    private fun stopRinging(reason: String) {
        if (isRinging) {
            appendToCurrentNightLog(this, NightLogEvent(Instant.now(), "alarm_stopped", mapOf("reason" to reason)))
        }
        stopPlayerSafely()
        runGuarded("stop vibration") { stopVibration(this) }
        runGuarded("release the alarm wake lock") { releaseAlarmWakeLock() }
        stopHandler.removeCallbacks(stopRunnable)
        isRinging = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** [MediaPlayer.stop] and [MediaPlayer.release] are tried independently so a failure in one still lets the other run, and either way [stopRinging] moves on to vibration, the wake lock and stopSelf. */
    private fun stopPlayerSafely() {
        val player = mediaPlayer
        mediaPlayer = null
        if (player == null) return
        runGuarded("stop the alarm player") { player.stop() }
        runGuarded("release the alarm player") { player.release() }
    }

    private fun runGuarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            Log.e(LOG_TAG, "failed to $what", error)
        }
    }

    override fun onDestroy() {
        stopHandler.removeCallbacks(stopRunnable)
        stopPlayerSafely()
        runGuarded("release the alarm wake lock on destroy") { releaseAlarmWakeLock() }
        super.onDestroy()
    }
}

/**
 * Stops the ringing alarm, if it is ringing: called from the Stop button in [AlarmActivity] (implicitly
 * [ALARM_STOP_REASON_BUTTON]) and from `NightController.endNight` (explicitly [ALARM_STOP_REASON_NIGHT_ENDED]).
 * The notification's own Stop action reaches [AlarmRingService] directly with the same [ACTION_STOP], so it
 * also defaults to [ALARM_STOP_REASON_BUTTON]. Safe to call when nothing is ringing - see [AlarmRingService.stopRinging].
 */
fun stopAlarmRinging(context: Context, reason: String = ALARM_STOP_REASON_BUTTON) {
    context.startService(Intent(context, AlarmRingService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_STOP_REASON, reason))
}

private fun vibrate(context: Context) {
    val vibratorManager = context.getSystemService<VibratorManager>() ?: return
    val effect = CombinedVibration.createParallel(VibrationEffect.createWaveform(VIBRATION_PATTERN, 0))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val attributes = VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build()
        vibratorManager.vibrate(effect, attributes)
    } else {
        // VibrationAttributes needs API 33; below that (down to minSdk 31) USAGE_ALARM cannot be requested,
        // so the plain overload is used and the OS default vibration policy applies instead.
        vibratorManager.vibrate(effect)
    }
}

private fun stopVibration(context: Context) {
    context.getSystemService<VibratorManager>()?.cancel()
}

/**
 * The alarm's notification: a tap and the Stop action both reach [AlarmActivity] / this service, so the
 * alarm can always be silenced even when the full-screen presentation did not show (screen unlocked,
 * permission revoked, or the banner was swiped away).
 */
fun buildAlarmNotification(context: Context): Notification {
    val activityIntent = Intent(context, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val activityPendingIntent = PendingIntent.getActivity(
        context, ALARM_ACTIVITY_REQUEST_CODE, activityIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val stopPendingIntent = PendingIntent.getService(
        context, ALARM_STOP_REQUEST_CODE, Intent(context, AlarmRingService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    return NotificationCompat.Builder(context, ALARM_NOTIFICATION_CHANNEL_ID)
        .setContentTitle(context.getString(R.string.alarm_notification_title))
        .setContentText(context.getString(R.string.alarm_notification_text))
        .setSmallIcon(R.mipmap.ic_launcher)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setFullScreenIntent(activityPendingIntent, true)
        .setContentIntent(activityPendingIntent)
        .addAction(0, context.getString(R.string.alarm_notification_stop_action), stopPendingIntent)
        .setOngoing(true)
        .build()
}

/** Idempotent: safe to call even before [AlarmRingService] has ever started, e.g. from PhoneAlarmReceiver's fallback path. */
fun createNotificationChannel(context: Context) {
    val manager = context.getSystemService<NotificationManager>() ?: return
    val channel = NotificationChannel(ALARM_NOTIFICATION_CHANNEL_ID, "Alarm", NotificationManager.IMPORTANCE_HIGH)
    // The MediaPlayer above owns playback with USAGE_ALARM; a channel sound would play a second time.
    channel.setSound(null, null)
    manager.createNotificationChannel(channel)
}

/** Posts the alarm notification directly, without going through [AlarmRingService] - the fallback PhoneAlarmReceiver uses when it cannot start the service at all. */
fun postAlarmNotificationDirectly(context: Context) {
    createNotificationChannel(context)
    val manager = context.getSystemService<NotificationManager>() ?: return
    manager.notify(NOTIFICATION_ID, buildAlarmNotification(context))
}
