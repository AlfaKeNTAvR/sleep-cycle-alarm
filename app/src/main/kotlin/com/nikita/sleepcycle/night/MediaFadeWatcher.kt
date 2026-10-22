package com.nikita.sleepcycle.night

// File purpose: phone test, 2026-10-02 - the bedtime fade used to act only on the night's ticks, so media
// started (or a volume turned up) in the night played loud for up to 15 minutes before the fade caught up.
// While NightService runs, this watches for media starting or stopping and for any media volume change, and
// runs the fade's share of a tick ([runMediaFadeCheck]) as soon as things settle.
//
// Owner spec, 2026-10-21/22 (PER-134): a fade begins, and its line falls, only while the screen is off, so the
// screen turning on or off is watched too. After every check MediaFadeRunner.kt is woken ([onChecked]) to move a
// falling fade smoothly.

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LOG_TAG = "MediaFadeWatcher"

/**
 * The system's own broadcast on every stream volume change. Not in the public SDK constants, but sent by every
 * Android version this app supports; at worst it never arrives and media starting still triggers the check.
 */
private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"

/**
 * How long things must stay quiet before the check runs (real time): a held volume key sends a change every
 * 50 ms, and checking mid-press would fight the owner's finger.
 */
private const val SETTLE_DELAY_MS = 1_000L

/** Watches media playback and volume for [NightService]'s lifetime; [start] in onCreate, [stop] in onDestroy. */
class MediaFadeWatcher(private val context: Context, private val scope: CoroutineScope, private val onChecked: () -> Unit) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var pendingCheck: Job? = null

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) = scheduleCheck()
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) == AudioManager.STREAM_MUSIC) scheduleCheck()
        }
    }

    /** The screen turning on freezes a falling fade; turning off lets a fade begin or carry on (always-on display counts as off). */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = scheduleCheck()
    }

    fun start() {
        audioManager?.registerAudioPlaybackCallback(playbackCallback, Handler(Looper.getMainLooper()))
        ContextCompat.registerReceiver(context, volumeReceiver, IntentFilter(VOLUME_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(context, screenReceiver, screenFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun stop() {
        audioManager?.unregisterAudioPlaybackCallback(playbackCallback)
        runCatching { context.unregisterReceiver(volumeReceiver) }
        runCatching { context.unregisterReceiver(screenReceiver) }
        pendingCheck?.cancel()
    }

    /** Runs a check after the settle wait, as a media or screen change does; MediaFadeRunner.kt asks for one when a fade reaches its ending volume. */
    fun requestCheck() = scheduleCheck()

    /** Restarts the settle wait: only the last event of a burst runs the check. Called on the main thread. */
    @Synchronized
    private fun scheduleCheck() {
        pendingCheck?.cancel()
        pendingCheck = scope.launch {
            delay(SETTLE_DELAY_MS)
            try {
                // A fresh fade needs a tick on its first step, which only a full tick books.
                if (runMediaFadeCheck(context)) runImmediateTick(context)
                onChecked()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.e(LOG_TAG, "media fade check failed", error)
            }
        }
    }
}
