package com.nikita.sleepcycle.night

// File purpose: the bedtime media fade - owner request, 2026-10-02. On Start night the media volume drops to
// the Settings screen's starting volume (default 25%, never raised; the fade can be switched off), holds for 10 minutes, then loses one volume step every 5 minutes down to a
// floor of about 5%, where it stays until the band says the owner is asleep (MediaPauseOnSleep.kt then pauses
// playback and the original volume is put back). Steps ride on the night's own ticks, which run every 5
// minutes for the first hour while the owner is not yet asleep, so a late tick makes its step late too.

import android.content.Context
import android.media.AudioManager
import android.util.Log
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

private const val LOG_TAG = "MediaFade"

/** A whole volume range, in the percent the Settings screen's starting volume is given in. */
private const val FULL_RANGE_PERCENT = 100.0

/** Fraction of the media volume range a fade never goes below (at least one step, so playback stays audible). */
private const val FADE_FLOOR_FRACTION = 0.05

/** How long the starting volume holds before the first step down. */
private val FADE_HOLD: Duration = Duration.ofMinutes(10)

/** How often the volume loses one more step after the hold. */
private val FADE_STEP_EVERY: Duration = Duration.ofMinutes(5)

/** The volume step a fade starts at: [startPercent] of [maxStep] (the Settings screen's starting volume), or [currentStep] if that is already lower. */
fun fadeStartStep(currentStep: Int, maxStep: Int, startPercent: Int): Int =
    minOf(currentStep, (maxStep * startPercent / FULL_RANGE_PERCENT).roundToInt())

/**
 * The volume step a fade begun at [startStep] at [startedAt] should be at by [now]: [startStep] through the
 * hold, then one step lower at the hold's end and every [FADE_STEP_EVERY] after, never below the floor (about
 * 5% of [maxStep], at least one step) - and never above [startStep], so a fade starting at or below the floor,
 * muted included, simply stays where it is.
 */
fun fadeTargetStep(startStep: Int, maxStep: Int, startedAt: Instant, now: Instant): Int {
    val elapsed = Duration.between(startedAt, now)
    if (elapsed < FADE_HOLD) return startStep
    val stepsDown = 1 + elapsed.minus(FADE_HOLD).dividedBy(FADE_STEP_EVERY).toInt()
    val floorStep = maxOf(1, (maxStep * FADE_FLOOR_FRACTION).roundToInt())
    return minOf(startStep, maxOf(floorStep, startStep - stepsDown))
}

/**
 * A fade in progress, persisted so it survives the process between ticks: the owner's own volume before the
 * fade ([originalStep], put back when it ends), where and when it began, and the step the app itself last set
 * ([lastSetStep]) - any other reading means the owner changed the volume, which ends the fade.
 */
private data class MediaFadeRecord(val originalStep: Int, val startStep: Int, val startedAt: Instant, val lastSetStep: Int)

private const val MEDIA_FADE_FILE_NAME = "media_fade.txt"

/**
 * Starts the fade at Start night: lowers the media volume to [fadeStartStep] and records the original. A fade
 * left over from a night that never ended cleanly is finished first, so its original volume is not lost.
 */
fun startMediaFade(context: Context, nightStartedAt: Instant, now: Instant, startPercent: Int, debugNight: Boolean) {
    endMediaFade(context, nightStartedAt, now, debugNight)
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return
    val originalStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    val startStep = fadeStartStep(originalStep, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), startPercent)
    if (startStep != originalStep) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, startStep, 0)
    saveMediaFade(context, MediaFadeRecord(originalStep, startStep, now, startStep))
    appendNightLog(
        context, nightStartedAt,
        NightLogEvent(now, "media_fade_start", mapOf("originalStep" to originalStep.toString(), "startStep" to startStep.toString())),
        debugNight
    )
}

/**
 * One tick's fade step while the owner is not asleep: sets the volume to [fadeTargetStep]. If the volume is no
 * longer what the app last set, the owner (or a switch to other headphones) changed it, so the fade ends there
 * and leaves that volume alone.
 */
fun stepMediaFade(context: Context, nightStartedAt: Instant, now: Instant, debugNight: Boolean) {
    val record = readMediaFade(context) ?: return
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return
    val currentStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    if (currentStep != record.lastSetStep) {
        clearMediaFade(context)
        appendNightLog(context, nightStartedAt, NightLogEvent(now, "media_fade_overridden", mapOf("step" to currentStep.toString())), debugNight)
        return
    }
    val targetStep = fadeTargetStep(record.startStep, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), record.startedAt, now)
    if (targetStep == currentStep) return
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetStep, 0)
    saveMediaFade(context, record.copy(lastSetStep = targetStep))
    appendNightLog(context, nightStartedAt, NightLogEvent(now, "media_fade_step", mapOf("step" to targetStep.toString())), debugNight)
}

/**
 * Ends the fade, if one is running: puts the owner's original volume back, unless the owner has changed it
 * since the app last set it (theirs wins). Called once media is paused on falling asleep, and when the night ends.
 */
fun endMediaFade(context: Context, nightStartedAt: Instant, now: Instant, debugNight: Boolean) {
    val record = readMediaFade(context) ?: return
    clearMediaFade(context)
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return
    val restored = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == record.lastSetStep
    if (restored) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, record.originalStep, 0)
    appendNightLog(
        context, nightStartedAt,
        NightLogEvent(now, "media_fade_end", mapOf("restoredStep" to if (restored) record.originalStep.toString() else "none")),
        debugNight
    )
}

private fun mediaFadeFile(context: Context): File = File(context.filesDir, MEDIA_FADE_FILE_NAME)

private fun saveMediaFade(context: Context, record: MediaFadeRecord) {
    try {
        mediaFadeFile(context).writeText("${record.originalStep} ${record.startStep} ${record.startedAt} ${record.lastSetStep}")
    } catch (error: Exception) {
        Log.e(LOG_TAG, "failed to save the media fade record", error)
    }
}

/** The fade in progress, or null when none is, or the file is unreadable (never throws). */
private fun readMediaFade(context: Context): MediaFadeRecord? {
    val file = mediaFadeFile(context)
    if (!file.exists()) return null
    val parts = runCatching { file.readText().trim().split(" ") }.getOrNull() ?: return null
    return runCatching { MediaFadeRecord(parts[0].toInt(), parts[1].toInt(), Instant.parse(parts[2]), parts[3].toInt()) }
        .onFailure { Log.e(LOG_TAG, "unparsable media fade record, treating as absent", it) }
        .getOrNull()
}

private fun clearMediaFade(context: Context) {
    val file = mediaFadeFile(context)
    if (file.exists() && !file.delete()) Log.e(LOG_TAG, "failed to delete the media fade record")
}
