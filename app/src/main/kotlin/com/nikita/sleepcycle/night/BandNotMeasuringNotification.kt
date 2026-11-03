package com.nikita.sleepcycle.night

// File purpose: PER-130 - the silent lock-screen notification for a band that syncs but measures no heart rate
// (every HEART_RATE row -1, StaleReason.NO_HEART_RATE_VALUES). The tick posts it when a sync first finds that
// case, and removes it once a sync brings real values again; ending the night removes it too. It only tells:
// the alarm already follows the no-data path (the plan is kept), and the Night screen shows the same warning.
// Same channel style as the Are you up? ask (AreYouUpNotification.kt): DEFAULT importance with sound, vibration
// and lights off, so it shows on a Pixel lock screen without ever waking the sleeper.

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.nikita.sleepcycle.MainActivity
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.bridge.StaleReason
import java.time.Instant

private const val LOG_TAG = "BandNotMeasuring"
private const val BAND_NOT_MEASURING_NOTIFICATION_ID = 7
private const val BAND_NOT_MEASURING_CHANNEL_ID = "band_not_measuring"
private const val BAND_NOT_MEASURING_OPEN_APP_REQUEST_CODE = 4030

/**
 * The tick's share, run under its night lock after the sync: posts the notification when [outcome] is the first
 * sync to find rows with no real heart rate (the previous one did not), and removes a showing one once a sync is
 * fresh again. A failed sync in between changes nothing: it says nothing about whether the band measures. Both
 * are logged (`band_not_measuring_posted`, `band_not_measuring_cleared`). A swiped-away notification is not
 * posted again until the band has measured and stopped again; the Night screen keeps the warning meanwhile.
 */
internal fun stepBandNotMeasuringNotification(context: Context, state: NightState, outcome: SyncOutcome, now: Instant) {
    val notMeasuring = outcome.staleReason == StaleReason.NO_HEART_RATE_VALUES
    val wasNotMeasuring = state.lastStaleReason == StaleReason.NO_HEART_RATE_VALUES
    when {
        notMeasuring && !wasNotMeasuring -> {
            val posted = postBandNotMeasuringNotification(context)
            appendNightLog(
                context, state.realStartedAt,
                NightLogEvent(now, "band_not_measuring_posted", mapOf("notificationPosted" to posted.toString())),
                state.debugOptions.isAnyEnabled
            )
        }
        outcome.syncOk -> clearBandNotMeasuringNotification(context, state, now, cause = "real_heart_rate_returned")
    }
}

/** Removes the notification if it is showing, and logs that with [cause]. Also called when the night ends. */
internal fun clearBandNotMeasuringNotification(context: Context, state: NightState, now: Instant, cause: String) {
    if (!isBandNotMeasuringNotificationShowing(context)) return
    cancelBandNotMeasuringNotification(context)
    appendNightLog(
        context, state.realStartedAt,
        NightLogEvent(now, "band_not_measuring_cleared", mapOf("cause" to cause)),
        state.debugOptions.isAnyEnabled
    )
}

fun cancelBandNotMeasuringNotification(context: Context) {
    context.getSystemService<NotificationManager>()?.cancel(BAND_NOT_MEASURING_NOTIFICATION_ID)
}

private fun isBandNotMeasuringNotificationShowing(context: Context): Boolean =
    context.getSystemService<NotificationManager>()?.activeNotifications?.any { it.id == BAND_NOT_MEASURING_NOTIFICATION_ID } == true

/** Posts (or replaces) the notification. Returns false when notifications are not allowed; the Night screen still warns. */
private fun postBandNotMeasuringNotification(context: Context): Boolean {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        Log.w(LOG_TAG, "notifications are not allowed, the band-not-measuring warning shows on the Night screen only")
        return false
    }
    val manager = context.getSystemService<NotificationManager>() ?: return false
    manager.createNotificationChannel(buildBandNotMeasuringChannel(context))
    manager.notify(BAND_NOT_MEASURING_NOTIFICATION_ID, buildBandNotMeasuringNotification(context))
    return true
}

/** Silent but on the lock screen: see AreYouUpNotification.kt's channel for why DEFAULT importance, not LOW. */
private fun buildBandNotMeasuringChannel(context: Context) =
    NotificationChannel(BAND_NOT_MEASURING_CHANNEL_ID, context.getString(R.string.band_not_measuring_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
        setSound(null, null)
        enableVibration(false)
        enableLights(false)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }

private fun buildBandNotMeasuringNotification(context: Context) =
    NotificationCompat.Builder(context, BAND_NOT_MEASURING_CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(context.getString(R.string.band_not_measuring_title))
        .setContentText(context.getString(R.string.band_not_measuring_body))
        .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.band_not_measuring_body)))
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                context, BAND_NOT_MEASURING_OPEN_APP_REQUEST_CODE,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()
