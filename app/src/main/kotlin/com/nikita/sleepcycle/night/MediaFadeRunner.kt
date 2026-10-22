package com.nikita.sleepcycle.night

// File purpose: owner spec, 2026-10-22 (PER-134) - keeps a falling bedtime fade moving smoothly. The fade's
// steps used to ride on the night's ticks, which Doze delays, so drops bunched. While NightService runs, this
// updates a running fade every [UPDATE_INTERVAL_MS] ([advanceMediaFade]) for as long as its line is falling -
// media playing with the screen off - holding a partial wake lock only then, so the CPU stays up for exactly
// the minutes the fade is moving. It sleeps the rest of the night and is woken by MediaFadeWatcher.kt (media,
// volume or screen changes) and after every tick.

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.nikita.sleepcycle.engine.morningAlarmHasRung
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val LOG_TAG = "MediaFadeRunner"
private const val WAKE_LOCK_TAG = "SleepCycleAlarm:MediaFade"

/** How often a falling fade is updated, in real milliseconds: often enough to sound smooth (100 to 250 ms is fine). */
private const val UPDATE_INTERVAL_MS = 200L

/** Each wake lock hold, renewed while the fade keeps falling, so a stuck loop can never keep the phone awake for long. */
private const val WAKE_LOCK_SLICE_MS = 120_000L
private const val WAKE_LOCK_RENEW_AFTER_MS = 60_000L

/** How often the night state is re-read, for a morning alarm that rang meanwhile. */
private const val NIGHT_STATE_REFRESH_MS = 10_000L

/** Runs the fade's updates for [NightService]'s lifetime; [wake] whenever the fade may need to move, [stop] in onDestroy. */
class MediaFadeRunner(private val context: Context, private val scope: CoroutineScope, private val onFadeReachedEnd: () -> Unit) {
    private var job: Job? = null

    /** Starts updating unless already running. Harmless when no fade is falling: the first update says so and it stops. */
    @Synchronized
    fun wake() {
        if (job?.isActive == true) return
        job = scope.launch { runWhileFalling() }
    }

    /** Stops updating and releases the gain; the fade record and the volume stay as they are. */
    fun stop() {
        job?.cancel()
        MediaFadeGain.release()
    }

    private suspend fun runWhileFalling() {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply { setReferenceCounted(false) }
        var lockRenewedAt: Long? = null
        var wasFalling = false
        try {
            var state = loadNightState(context) ?: return
            var stateReadAt = SystemClock.elapsedRealtime()
            while (true) {
                if (SystemClock.elapsedRealtime() - stateReadAt > NIGHT_STATE_REFRESH_MS) {
                    state = loadNightState(context) ?: return
                    stateReadAt = SystemClock.elapsedRealtime()
                }
                // Once the morning alarm has rung the next tick puts the owner's own volume back; nothing moves until then.
                if (morningAlarmHasRung(state.wakeAlarmFiredAt, state.morningAlarmAt, state.phoneAlarmFiredFor)) {
                    MediaFadeGain.release()
                    return
                }
                val audio = readAppSettings(context).first().bedtimeAudio
                val progress = advanceMediaFade(context, state.realStartedAt, nowInstant(), audio, state.debugOptions.isAnyEnabled).progress
                if (progress != FadeProgress.DESCENDING) {
                    // Reaching the ending volume is the moment the pause after a fade is decided (MediaFade.kt).
                    if (progress == FadeProgress.AT_END && wasFalling) onFadeReachedEnd()
                    return
                }
                wasFalling = true
                if (wakeLock != null && (lockRenewedAt == null || SystemClock.elapsedRealtime() - lockRenewedAt > WAKE_LOCK_RENEW_AFTER_MS)) {
                    wakeLock.acquire(WAKE_LOCK_SLICE_MS)
                    lockRenewedAt = SystemClock.elapsedRealtime()
                }
                delay(UPDATE_INTERVAL_MS)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(LOG_TAG, "media fade update failed", error)
            // Never leave a gain behind that nothing updates: at most one step louder (MediaFadeGain.kt).
            MediaFadeGain.release()
        } finally {
            runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
        }
    }
}
