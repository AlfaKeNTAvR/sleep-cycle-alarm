package com.nikita.sleepcycle.alarm

// File purpose: the wake lock that keeps the CPU on from the moment the phone alarm fires until ringing
// stops. Acquired in PhoneAlarmReceiver, released by AlarmRingService when ringing stops - belt and
// suspenders alongside MediaPlayer's own wake mode, since the exact-alarm wake window is short.

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.core.content.getSystemService
import java.time.Duration

private const val LOG_TAG = "AlarmWakeLock"
private const val WAKE_LOCK_TAG = "SleepCycleAlarm:Ringing"

private var heldWakeLock: PowerManager.WakeLock? = null

/** Acquires the ringing wake lock, timed out at [timeout] (matching the alarm's auto-stop) as a last-resort safety net. */
fun acquireAlarmWakeLock(context: Context, timeout: Duration) {
    val powerManager = context.getSystemService<PowerManager>() ?: return
    val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
    wakeLock.acquire(timeout.toMillis())
    heldWakeLock = wakeLock
}

/** Releases the ringing wake lock if one is held. Safe to call even if none is held or it already timed out. */
fun releaseAlarmWakeLock() {
    try {
        heldWakeLock?.let { if (it.isHeld) it.release() }
    } catch (error: RuntimeException) {
        Log.e(LOG_TAG, "failed to release the alarm wake lock", error)
    } finally {
        heldWakeLock = null
    }
}
