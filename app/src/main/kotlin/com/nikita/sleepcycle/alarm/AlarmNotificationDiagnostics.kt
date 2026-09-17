package com.nikita.sleepcycle.alarm

// File purpose: reports whether the OS will actually let the phone alarm interrupt the lock screen - full
// screen intent permission, notifications enabled, and the alarm channel's importance - for the setup
// checklist and for startNight to log before the night begins.

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService

/** Must match the channel id AlarmRingService creates and posts to. */
const val ALARM_NOTIFICATION_CHANNEL_ID = "alarm"

/** Everything that could silently stop the alarm from showing over the lock screen. */
data class AlarmNotificationReadiness(
    val canUseFullScreenIntent: Boolean,
    val notificationsEnabled: Boolean,
    val alarmChannelBlocked: Boolean,
    val alarmChannelDowngraded: Boolean
)

fun readAlarmNotificationReadiness(context: Context): AlarmNotificationReadiness = AlarmNotificationReadiness(
    canUseFullScreenIntent = canUseFullScreenIntent(context),
    notificationsEnabled = notificationsAreEnabled(context),
    alarmChannelBlocked = alarmChannelIsBlocked(context),
    alarmChannelDowngraded = alarmChannelIsDowngraded(context)
)

/** True when the OS will let a full-screen intent take over the lock screen; the permission does not exist below API 34, where it is always allowed. */
fun canUseFullScreenIntent(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
    val manager = context.getSystemService<NotificationManager>() ?: return false
    return manager.canUseFullScreenIntent()
}

fun notificationsAreEnabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

/** True when the user blocked the alarm channel from Settings (importance NONE). Missing channel counts as blocked: it has not been created yet. */
fun alarmChannelIsBlocked(context: Context): Boolean {
    val manager = context.getSystemService<NotificationManager>() ?: return true
    val channel = manager.getNotificationChannel(ALARM_NOTIFICATION_CHANNEL_ID) ?: return false
    return channel.importance == NotificationManager.IMPORTANCE_NONE
}

/** True when the alarm channel exists but was downgraded below HIGH (the importance AlarmRingService requests), so it may not show full screen or make sound. */
fun alarmChannelIsDowngraded(context: Context): Boolean {
    val manager = context.getSystemService<NotificationManager>() ?: return true
    val channel = manager.getNotificationChannel(ALARM_NOTIFICATION_CHANNEL_ID) ?: return false
    return channel.importance in 1 until NotificationManager.IMPORTANCE_HIGH
}
