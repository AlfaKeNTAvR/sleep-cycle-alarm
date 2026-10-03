package com.nikita.sleepcycle.alarm

// File purpose: ISSUES.md #4 (round2 #3) - the alarm sound plays on the phone's ALARM stream (USAGE_ALARM,
// AlarmSoundChooser.kt), a volume slider of its own that nothing checked: at zero the alarm only vibrates.
// Owner decision, 2026-10-03: Start night's readiness check and Setup warn when it is low, and while the alarm
// rings the stream is raised to at least half its range, then put back when the ring stops (AlarmRingService).
// "Low" and the ring's floor are the same line, half the range rounded up, so a volume Setup calls fine is one
// the ring leaves alone.

import android.content.Context
import android.media.AudioManager
import androidx.core.content.getSystemService

/** Owner decision, 2026-10-03: the alarm stream's floor, half of [maxVolume] rounded up (4 of 7, 8 of 15). */
fun alarmVolumeFloor(maxVolume: Int): Int = (maxVolume + 1) / 2

/** Whether [volume] is under the floor: the alarm would ring quieter than half its range, or not at all. */
fun isAlarmVolumeLow(volume: Int, maxVolume: Int): Boolean = volume < alarmVolumeFloor(maxVolume)

/** The alarm stream's current volume and its maximum, or null when the phone has no AudioManager. */
fun readAlarmVolume(context: Context): Pair<Int, Int>? {
    val audioManager = context.getSystemService<AudioManager>() ?: return null
    return audioManager.getStreamVolume(AudioManager.STREAM_ALARM) to audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
}

/**
 * Raises the alarm stream to [alarmVolumeFloor] when it is below it. Returns the volume it replaced, for
 * [restoreAlarmVolume] once the ring stops, or null when it changed nothing.
 */
fun raiseAlarmVolumeToFloor(context: Context): Int? {
    val audioManager = context.getSystemService<AudioManager>() ?: return null
    val volume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
    val floor = alarmVolumeFloor(audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM))
    if (volume >= floor) return null
    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, floor, 0)
    return volume
}

/** Puts the alarm stream back to [volume], the one [raiseAlarmVolumeToFloor] replaced. */
fun restoreAlarmVolume(context: Context, volume: Int) {
    context.getSystemService<AudioManager>()?.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
}
