package com.nikita.sleepcycle.alarm

// File purpose: tries each alarm sound candidate in order - the default alarm sound, then the default
// ringtone, then the default notification sound - actually starting playback before counting one as chosen.
// A candidate only counts once MediaPlayer.prepare() and start() both succeed; a candidate that throws is
// released immediately and the next one is tried. Falls back to vibration only if every candidate fails.

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.PowerManager
import android.util.Log

private const val LOG_TAG = "AlarmSoundChooser"

const val ALARM_SOUND_SOURCE_DEFAULT = "default_alarm_sound"
const val ALARM_SOUND_SOURCE_RINGTONE_FALLBACK = "ringtone_fallback"
const val ALARM_SOUND_SOURCE_NOTIFICATION_FALLBACK = "notification_sound_fallback"
const val ALARM_SOUND_SOURCE_VIBRATION_ONLY = "vibration_only"

/** The alarm sound actually playing (or null for vibration only), which step produced it, and every candidate source that failed before it - for the night log. */
data class ChosenAlarmSound(val player: MediaPlayer?, val source: String, val failedSources: List<String>)

/**
 * Tries the default alarm sound, then the default ringtone, then the default notification sound, in order.
 * Each candidate must actually reach [MediaPlayer.start] to count as chosen; a missing URI or a player that
 * throws counts as a failure and moves on to the next candidate. Falls back to vibration only (a null
 * player) once every candidate has failed.
 */
fun startAlarmSound(context: Context): ChosenAlarmSound {
    val candidates = listOf(
        ALARM_SOUND_SOURCE_DEFAULT to RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM),
        ALARM_SOUND_SOURCE_RINGTONE_FALLBACK to RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE),
        ALARM_SOUND_SOURCE_NOTIFICATION_FALLBACK to RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION),
    )
    val failedSources = mutableListOf<String>()
    for ((source, uri) in candidates) {
        if (uri == null) {
            Log.w(LOG_TAG, "no URI available for $source")
            failedSources.add(source)
            continue
        }
        val player = tryStartLoopingSound(context, uri)
        if (player != null) {
            Log.i(LOG_TAG, "using $source")
            return ChosenAlarmSound(player, source, failedSources)
        }
        failedSources.add(source)
    }
    Log.w(LOG_TAG, "no alarm, ringtone or notification sound could be played, falling back to vibration only")
    return ChosenAlarmSound(null, ALARM_SOUND_SOURCE_VIBRATION_ONLY, failedSources)
}

private fun tryStartLoopingSound(context: Context, uri: Uri): MediaPlayer? {
    val player = MediaPlayer()
    return try {
        @Suppress("DEPRECATION") // Belt-and-suspenders alongside the wake lock PhoneAlarmReceiver holds through ringing.
        player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        player.setDataSource(context, uri)
        player.isLooping = true
        player.prepare()
        player.start()
        player
    } catch (error: Exception) {
        Log.e(LOG_TAG, "failed to play sound from $uri", error)
        releasePlayerSafely(player)
        null
    }
}

private fun releasePlayerSafely(player: MediaPlayer) {
    try {
        player.release()
    } catch (error: Exception) {
        Log.e(LOG_TAG, "failed to release a failed media player", error)
    }
}
