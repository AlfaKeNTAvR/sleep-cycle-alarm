package com.nikita.sleepcycle.night

// File purpose: arms and cancels the exact alarm that wakes TickReceiver for the next sync.

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.getSystemService
import java.time.Instant

private const val LOG_TAG = "TickScheduling"
private const val TICK_REQUEST_CODE = 1001

/** PendingIntent extra carrying the tick's intended instant, read back by TickReceiver and logged as `scheduledFor` (C1) - today's `scheduledFor` was just the actual firing time, which always reads as zero delay. */
const val EXTRA_SCHEDULED_FOR_EPOCH_MILLI = "scheduledForEpochMilli"

/**
 * Arms an exact alarm that fires [TickReceiver] at [at], waking the device from idle if needed. The
 * `USE_EXACT_ALARM` permission this app declares is granted automatically to alarm-clock apps but can
 * still be revoked from Settings, so the call is guarded (lint's MissingPermission check does not know
 * about `USE_EXACT_ALARM`, hence the suppression). A failure here is not fatal: the phone alarm stays the
 * safety net.
 */
@SuppressLint("MissingPermission")
fun scheduleTick(context: Context, at: Instant) {
    val alarmManager = context.getSystemService<AlarmManager>() ?: return
    try {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), tickPendingIntent(context, at))
    } catch (error: SecurityException) {
        Log.e(LOG_TAG, "cannot schedule the next tick: exact alarm permission was likely revoked", error)
    }
}

/** Cancels a pending tick alarm, if any. */
fun cancelTick(context: Context) {
    val alarmManager = context.getSystemService<AlarmManager>() ?: return
    alarmManager.cancel(tickPendingIntent(context, at = null))
}

/** [at] is embedded as an extra so TickReceiver can read back the intended instant (C1); omitted (null) when only used to build a cancellation target, since extras do not affect a PendingIntent's identity. */
private fun tickPendingIntent(context: Context, at: Instant?): PendingIntent {
    val intent = Intent(context, TickReceiver::class.java)
    at?.let { intent.putExtra(EXTRA_SCHEDULED_FOR_EPOCH_MILLI, it.toEpochMilli()) }
    return PendingIntent.getBroadcast(context, TICK_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
