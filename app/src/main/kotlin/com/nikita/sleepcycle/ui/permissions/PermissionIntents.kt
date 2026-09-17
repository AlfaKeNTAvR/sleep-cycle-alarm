package com.nikita.sleepcycle.ui.permissions

// File purpose: deep links from the setup checklist into the system settings screens that grant each exemption.

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Opens this app's "Use full-screen intent" settings screen (Android 14+; the permission is normal before that). */
fun fullScreenIntentSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))

/** Opens the "ignore battery optimisation" request dialog for this app. */
fun batteryOptimizationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

// exactAlarmSettingsIntent (ACTION_REQUEST_SCHEDULE_EXACT_ALARM) was removed (D6): the manifest declares
// only USE_EXACT_ALARM, a normal permission auto-granted to alarm-clock apps that AlarmManager.canScheduleExactAlarms()
// already reports true for, so PermissionStatus.exactAlarmsAllowed can never be false and the settings
// screen this opened was unreachable.
