package com.nikita.sleepcycle.night

// File purpose: owner spec, 2026-10-22 (PER-134) - the small digital gain that makes the bedtime fade smooth.
// The media volume only has whole steps (25 on the Pixel, 2 to 4 dB each), so the fade keeps stepping the
// hardware volume and fills the gap between two steps with a global DynamicsProcessing effect on audio session
// 0, the output mix every app's media goes through (spike, 2026-10-21: "this worked amazingly" on the phone
// speaker and a Bluetooth speaker). Needs MODIFY_AUDIO_SETTINGS, a normal permission.
//
// Research notes (smooth-fade-research.md, 2026-10-21): the effect is torn down if the app dies, which the
// hybrid limits to a jump of at most one step; another effects app can take control of it; some Bluetooth
// devices may bypass it; session 0 is deprecated in the docs but works. So the effect sits behind
// [FadeGainEffect] - a seam Robolectric tests replace with a fake, since Robolectric cannot create the real one -
// and whenever it cannot be created or loses control the fade carries on with plain hardware steps along the
// same line ([MediaFadeGain.apply] returns false; MediaFade.kt logs that once).

import android.media.audiofx.DynamicsProcessing
import android.util.Log

private const val LOG_TAG = "MediaFadeGain"

/** Above any ordinary equalizer app's priority, so the fade keeps control of its own effect where it can. */
private const val EFFECT_PRIORITY = 100

/** The global output mix: every app's media, not one app's session. */
private const val OUTPUT_MIX_SESSION = 0

/** Stereo: the gain is applied to every channel alike. */
private const val EFFECT_CHANNELS = 2

/** A digital gain applied to all media on the current output. */
interface FadeGainEffect {
    /** Sets the gain in dB (0 or below); false when the effect no longer controls the output. */
    fun setGainDb(gainDb: Float): Boolean

    /** Back to 0 dB and gone. Safe to call more than once. */
    fun release()
}

/** Why the fade went on without its digital gain, for the night log. */
enum class FadeGainFallback { UNAVAILABLE, LOST_CONTROL }

/**
 * The one effect the process holds while a fade runs. Opened on the first non-zero gain, released whenever a
 * fade ends, parks, is switched off, or the alarm rings. Calls come from the fade runner and the night's tick,
 * both under MediaFade.kt's fade lock.
 */
object MediaFadeGain {
    /** Opens the effect; replaced by a fake in tests. Null when it cannot be created. */
    var openEffect: () -> FadeGainEffect? = ::openDynamicsProcessingGain

    private var effect: FadeGainEffect? = null

    /** Set once the effect failed for the current fade; the fade then uses hardware steps only until the next one starts. */
    var fallback: FadeGainFallback? = null
        private set

    /** Set when an alarm starts ringing: no gain at all until the next fade starts ([resetForNewFade]). */
    private var blockedByAlarm = false

    /**
     * Applies [gainDb] to all media; true while the gain is really in effect. Opens the effect when needed. A
     * failure releases it and records [fallback], so the caller falls back to plain steps.
     */
    @Synchronized
    fun apply(gainDb: Float): Boolean {
        if (blockedByAlarm || fallback != null) return false
        if (effect == null) {
            if (gainDb >= 0f) return true
            effect = openEffect()
            if (effect == null) {
                fallback = FadeGainFallback.UNAVAILABLE
                return false
            }
        }
        val applied = runCatching { effect?.setGainDb(gainDb) == true }.getOrDefault(false)
        if (!applied) {
            fallback = FadeGainFallback.LOST_CONTROL
            release()
        }
        return applied
    }

    /** Back to 0 dB and the effect released. Harmless with no effect open. */
    @Synchronized
    fun release() {
        val open = effect ?: return
        effect = null
        runCatching { open.release() }.onFailure { Log.e(LOG_TAG, "failed to release the fade gain", it) }
    }

    /** A new fade gets a fresh try at the effect, even after the last one fell back or an alarm rang. */
    @Synchronized
    fun resetForNewFade() {
        release()
        fallback = null
        blockedByAlarm = false
    }

    /** An alarm is ringing: the gain goes, and stays gone until the next fade starts. */
    @Synchronized
    fun releaseForAlarm() {
        blockedByAlarm = true
        release()
    }

    /** Whether an effect is open right now, for tests. */
    val isOpen: Boolean
        @Synchronized get() = effect != null
}

/** The real effect: input gain only, no EQ, compressor or limiter stages (as in the spike). */
private fun openDynamicsProcessingGain(): FadeGainEffect? = try {
    val config = DynamicsProcessing.Config.Builder(
        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, EFFECT_CHANNELS, false, 0, false, 0, false, 0, false,
    ).build()
    val processing = DynamicsProcessing(EFFECT_PRIORITY, OUTPUT_MIX_SESSION, config)
    processing.setEnabled(true)
    if (!processing.hasControl()) {
        processing.release()
        Log.w(LOG_TAG, "the fade gain effect was created without control")
        null
    } else {
        DynamicsProcessingGain(processing)
    }
} catch (error: Exception) {
    Log.w(LOG_TAG, "the fade gain effect could not be created", error)
    null
}

private class DynamicsProcessingGain(private val processing: DynamicsProcessing) : FadeGainEffect {
    @Volatile private var hasControl = true

    init {
        processing.setControlStatusListener { _, controlGranted -> hasControl = controlGranted }
    }

    override fun setGainDb(gainDb: Float): Boolean {
        if (!hasControl) return false
        processing.setInputGainAllChannelsTo(gainDb)
        return true
    }

    override fun release() {
        runCatching { processing.setInputGainAllChannelsTo(0f) }
        runCatching { processing.setEnabled(false) }
        processing.release()
    }
}
