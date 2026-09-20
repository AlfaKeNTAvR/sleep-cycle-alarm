package com.nikita.sleepcycle.alarm

// File purpose: foreground service that rings the phone alarm - sound, vibration, full-screen notification -
// until stopped from AlarmActivity or after an auto-stop timeout. F1: startRinging is NOT idempotent - a
// second start command while already ringing means a genuinely new alarm fired (typically the out-of-bed
// nudge, D4) into a service that never got stopped, and it restarts ringing fresh for that new alarm rather
// than silently no-op'ing. The old idempotent no-op used to let AUTO_STOP_AFTER's countdown, started by the
// FIRST alarm, tear the service down moments after the nudge arrived, without the nudge ever actually
// ringing - see EngineConfig.ringAutoStopAfter's own doc for the timing root cause. stopRinging guards each
// step so a failure stopping the sound can never skip stopping vibration, releasing the wake lock, or stopSelf.
//
// stopAlarmRinging is also called unconditionally by endNight, every time a night ends, whether or not the
// alarm ever rang - starting the service just to ask it to stop, when it was never running, used to log a
// misleading "alarm_stopped" line for a stop that never happened. stopRinging now only logs when [isRinging]
// was actually true on THIS instance, which is only ever the case when the service was already running and
// ringing; a reason distinguishes the ringing alarm's own Stop button/notification action from endNight
// stopping a (possibly non-ringing) alarm because the night itself ended.
//
// V1: [startRinging]'s own auto-stop used to post its Handler at a REAL millis delay taken straight from
// EngineConfig.ringAutoStopAfter, while outOfBedDelay and preNudgeCheckLead reach AlarmManager through
// AppClock.toRealInstant and so compress with the simulated-clock speed - meaning validateConfig's own
// `outOfBedDelay > ringAutoStopAfter` invariant was comparing quantities on two DIFFERENT clocks above 1x, and
// was false in practice (ringAutoStopAfter stayed a full 9 REAL minutes while the nudge's own 15 virtual
// minutes compressed to seconds). The nudge would then fire into a ring that had not auto-stopped yet, and F1's
// own non-idempotent restart would keep the phone ringing continuously through a simulated morning. Fixed by
// computing the auto-stop as a VIRTUAL instant (`nowInstant() + autoStopAfter`) and converting THAT through
// [AppClock.toRealInstant] before posting the handler - the same T5 conversion point every other duration in
// this trio already uses, so all three compress together and validateConfig's invariant means what it says
// again.

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
import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.night.AppClock
import com.nikita.sleepcycle.night.NightLogEvent
import com.nikita.sleepcycle.night.appendToCurrentNightLog
import com.nikita.sleepcycle.night.nowInstant
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

/** F1: carries the resolved EngineConfig.ringAutoStopAfter (real or fast-night) from PhoneAlarmReceiver, which knows the night's own debug options; this service has no state file to read one from itself. */
const val EXTRA_RING_AUTO_STOP_AFTER_MILLIS = "ringAutoStopAfterMillis"

/** The real-config fallback used only when no duration was supplied (should not normally happen - every real firing intent carries one). Kept equal to [EngineConfig]'s own real default, not a second independently-tuned number, so the two can never quietly drift apart (F1). */
val AUTO_STOP_AFTER: Duration = EngineConfig().ringAutoStopAfter

/** Foreground service (type `specialUse`) that owns the ringing alarm. */
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
        // D4: which alarm is ringing - the wake/nap alarm or the out-of-bed nudge - only matters for the
        // notification's and AlarmActivity's wording; sound, vibration and every stop path are identical either way.
        val isOutOfBed = intent?.getBooleanExtra(EXTRA_ALARM_IS_OUT_OF_BED_NUDGE, false) ?: false
        val autoStopAfterMillis = intent?.getLongExtra(EXTRA_RING_AUTO_STOP_AFTER_MILLIS, AUTO_STOP_AFTER.toMillis()) ?: AUTO_STOP_AFTER.toMillis()
        startForeground(NOTIFICATION_ID, buildAlarmNotification(this, isOutOfBed))
        startRinging(Duration.ofMillis(autoStopAfterMillis))
        return START_NOT_STICKY
    }

    /**
     * F1: NOT idempotent. A call while already ringing means a genuinely new alarm fired into this still-live
     * service (in practice: the out-of-bed nudge, D4, arriving because the owner slept through the whole wake
     * alarm ring - exactly the case the nudge exists for) - it tears down the current ring's artifacts and
     * starts fresh for the new one, with its own full [autoStopAfter] window timed from now. The old no-op
     * left the auto-stop countdown running on the FIRST alarm's own clock, which - started slightly later than
     * the nudge's own arming instant (see EngineConfig.ringAutoStopAfter's doc) - reliably fired a moment
     * after the nudge arrived, silently swallowing it.
     */
    private fun startRinging(autoStopAfter: Duration) {
        if (isRinging) {
            stopRingingArtifacts()
        }
        isRinging = true
        appendToCurrentNightLog(
            this,
            NightLogEvent(nowInstant(), "alarm_ring_started", mapOf("fullScreenIntentAllowed" to canUseFullScreenIntent(this).toString()))
        )
        val chosen = startAlarmSound(this)
        mediaPlayer = chosen.player
        appendToCurrentNightLog(
            this,
            NightLogEvent(
                nowInstant(), "alarm_sound_chosen",
                mapOf("source" to chosen.source, "failedSources" to chosen.failedSources.joinToString(","))
            )
        )
        vibrate(this)
        stopHandler.postDelayed(stopRunnable, realAutoStopDelayMillis(autoStopAfter))
    }

    /**
     * V1: [autoStopAfter] is a VIRTUAL duration (EngineConfig.ringAutoStopAfter) - converts `nowInstant() +
     * autoStopAfter` (the virtual instant the ring must stop by) through [AppClock.toRealInstant] and returns
     * the REAL delay from here to there, floored at zero (a conversion landing at or before now, e.g. from
     * clock arithmetic at the moment a speed change lands, must never produce a negative Handler delay). See
     * this file's own header for why this must compress with speed exactly like outOfBedDelay/preNudgeCheckLead do.
     */
    private fun realAutoStopDelayMillis(autoStopAfter: Duration): Long {
        val virtualStopAt = nowInstant().plus(autoStopAfter)
        val realStopAt = AppClock.toRealInstant(virtualStopAt)
        return Duration.between(Instant.now(), realStopAt).toMillis().coerceAtLeast(0)
    }

    /** Stops the sound, vibration and the pending auto-stop only - never the service, the notification or the wake lock. Shared by [startRinging]'s restart path (F1) and as the first step of [stopRinging] itself. */
    private fun stopRingingArtifacts() {
        stopPlayerSafely()
        runGuarded("stop vibration") { stopVibration(this) }
        stopHandler.removeCallbacks(stopRunnable)
    }

    /**
     * Only logs `alarm_stopped` when this instance was actually ringing (isRinging is only ever true when the
     * service was already running and ringing before this call, since a fresh instance starts with it false).
     * Otherwise this is a no-op stop - e.g. endNight calling [stopAlarmRinging] on a night where the alarm never
     * fired - and must log nothing, so the night log never claims an alarm was stopped that was never ringing.
     */
    private fun stopRinging(reason: String) {
        if (isRinging) {
            appendToCurrentNightLog(this, NightLogEvent(nowInstant(), "alarm_stopped", mapOf("reason" to reason)))
        }
        stopRingingArtifacts()
        runGuarded("release the alarm wake lock") { releaseAlarmWakeLock() }
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
 * permission revoked, or the banner was swiped away). D4: [isOutOfBed] only changes the wording, so the
 * nudge reads as "time to get up" rather than the wake alarm's own text - the Stop action still only stops
 * the sound (D6), it never ends the night, whichever alarm this is.
 */
fun buildAlarmNotification(context: Context, isOutOfBed: Boolean = false): Notification {
    val activityIntent = Intent(context, AlarmActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(EXTRA_ALARM_IS_OUT_OF_BED_NUDGE, isOutOfBed)
    val activityPendingIntent = PendingIntent.getActivity(
        context, ALARM_ACTIVITY_REQUEST_CODE, activityIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val stopPendingIntent = PendingIntent.getService(
        context, ALARM_STOP_REQUEST_CODE, Intent(context, AlarmRingService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val titleRes = if (isOutOfBed) R.string.alarm_notification_out_of_bed_title else R.string.alarm_notification_title
    val textRes = if (isOutOfBed) R.string.alarm_notification_out_of_bed_text else R.string.alarm_notification_text
    return NotificationCompat.Builder(context, ALARM_NOTIFICATION_CHANNEL_ID)
        .setContentTitle(context.getString(titleRes))
        .setContentText(context.getString(textRes))
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
fun postAlarmNotificationDirectly(context: Context, isOutOfBed: Boolean = false) {
    createNotificationChannel(context)
    val manager = context.getSystemService<NotificationManager>() ?: return
    manager.notify(NOTIFICATION_ID, buildAlarmNotification(context, isOutOfBed))
}
