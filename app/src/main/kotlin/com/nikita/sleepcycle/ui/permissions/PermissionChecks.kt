package com.nikita.sleepcycle.ui.permissions

// File purpose: reads the real Android permission/exemption statuses the setup checklist needs. Called on every
// resume, per the spec, since the user grants these by leaving the app for a system settings screen.

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.nikita.sleepcycle.ui.state.PermissionStatus

/** Reads the current statuses of every permission/exemption the setup checklist tracks. */
fun currentPermissionStatus(context: Context): PermissionStatus = PermissionStatus(
    notificationsGranted = isNotificationsGranted(context),
    fullScreenIntentAllowed = isFullScreenIntentAllowed(context),
    batteryOptimizationIgnored = isBatteryOptimizationIgnored(context),
    exactAlarmsAllowed = isExactAlarmsAllowed(context),
)

private fun isNotificationsGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/** [NotificationManager.canUseFullScreenIntent] only exists from Android 14; the permission was always granted at install before that. */
private fun isFullScreenIntentAllowed(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        context.getSystemService<NotificationManager>()?.canUseFullScreenIntent() ?: false
    } else {
        true
    }

private fun isBatteryOptimizationIgnored(context: Context): Boolean =
    context.getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(context.packageName) ?: false

private fun isExactAlarmsAllowed(context: Context): Boolean =
    context.getSystemService<AlarmManager>()?.canScheduleExactAlarms() ?: false
