package com.nikita.testplayer

// File purpose: a looping tone with a real media session, driven from adb, so the emulator has "media playing"
// the way a phone with an audiobook does: AudioManager sees an active USAGE_MEDIA player, and the sleep app's
// media key pause reaches this session and stops it.
//   adb shell am start-foreground-service -n com.nikita.testplayer/.PlayerService -a play
//   adb shell am start-foreground-service -n com.nikita.testplayer/.PlayerService -a pause

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.util.Log

private const val LOG_TAG = "TestPlayer"
private const val CHANNEL_ID = "playback"
private const val NOTIFICATION_ID = 1
private const val ACTION_PLAY = "play"
private const val ACTION_PAUSE = "pause"

class PlayerService : Service() {
    private var player: MediaPlayer? = null
    private lateinit var session: MediaSession

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, LOG_TAG).apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = pause()
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Test player")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        when (intent?.action) {
            ACTION_PAUSE -> pause()
            else -> play()
        }
        return START_NOT_STICKY
    }

    private fun play() {
        val current = player ?: MediaPlayer.create(
            this, R.raw.tone,
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            0,
        ).apply { isLooping = true }
        player = current
        current.start()
        setState(PlaybackState.STATE_PLAYING)
        Log.i(LOG_TAG, "playing")
    }

    private fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        setState(PlaybackState.STATE_PAUSED)
        Log.i(LOG_TAG, "paused")
    }

    private fun setState(state: Int) {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                .setState(state, 0, 1f)
                .build()
        )
    }

    override fun onDestroy() {
        player?.release()
        session.release()
        super.onDestroy()
    }
}
