package com.nikita.sleepcycle.night

// File purpose: pause whatever media is playing (audiobook, music, video) once the band says the owner fell
// asleep - owner request, 2026-10-02, replacing a separate sleep-timer app. A plain pause, no volume fade yet.
//
// Mechanism: the same one open-source sleep timers use - a simulated "pause" media-button press through
// AudioManager, which the system routes to whichever app last held media playback. No extra permission. The
// pause lands as soon as a tick SEES the band's sleep verdict; the band itself confirms sleep 5 to 24 minutes
// late, a known limitation.

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.nikita.sleepcycle.engine.SleepState
import kotlinx.coroutines.delay

/** How long after the pause press to check whether playback actually stopped, for the night log only. */
private const val PAUSE_CHECK_DELAY_MS = 1_500L

/**
 * True when this tick should pause media: the band now says ASLEEP and the previous tick did not (a first
 * falling asleep, or falling back asleep after a mid-night awakening), and the morning alarm has not rung yet -
 * once the owner is up, dozing off with something playing is not a night-time sleep to protect.
 */
fun shouldPauseMediaOnSleep(previousSleepState: SleepState, currentSleepState: SleepState, morningAlarmRang: Boolean): Boolean =
    !morningAlarmRang && currentSleepState == SleepState.ASLEEP && previousSleepState != SleepState.ASLEEP

/** What [pausePlayingMedia] found and did, for the night log. */
enum class MediaPauseResult { NOTHING_PLAYING, PAUSED, STILL_PLAYING }

/**
 * Presses "pause" for the current media app if anything is playing, then checks after [PAUSE_CHECK_DELAY_MS]
 * whether playback really stopped (an app may ignore media keys). Never presses play/pause toggle, so a press
 * can never START playback.
 */
suspend fun pausePlayingMedia(context: Context): MediaPauseResult {
    val audioManager = context.getSystemService(AudioManager::class.java)
    if (audioManager == null || !audioManager.isMusicActive) return MediaPauseResult.NOTHING_PLAYING
    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
    delay(PAUSE_CHECK_DELAY_MS)
    return if (audioManager.isMusicActive) MediaPauseResult.STILL_PLAYING else MediaPauseResult.PAUSED
}
