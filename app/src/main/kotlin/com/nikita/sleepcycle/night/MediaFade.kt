package com.nikita.sleepcycle.night

// File purpose: the bedtime media fade - owner request, 2026-10-02. Once media is playing after Start night the
// media volume drops to the Settings screen's starting volume (default 25%; the fade can be switched off), holds for 10 minutes, then loses one volume step every 5 minutes down to the
// Settings screen's ending volume (default 5%), where it stays until the band says the owner is asleep (MediaPauseOnSleep.kt then pauses
// playback and the volume is parked at the starting volume - see below). Steps ride on the night's own ticks, which run every 5
// minutes for the first hour while the owner is not yet asleep, so a late tick makes its step late too.
//
// Owner request, 2026-10-02: the fade runs again after a mid-night awakening. Falling asleep re-arms it, and
// the first tick that sees the owner awake with media playing again starts a fresh fade ([shouldStartWakeFade]).
//
// Phone test, 2026-10-02: Start night used to set the volume at the tap itself. With the audiobook started
// afterwards (or on headphones, which keep a volume of their own) that changed a volume nobody heard, and the
// next tick read the playing device's own, different volume as the owner overriding the fade - so it ended on
// the spot, before any step. Start night now only ARMS the fade ([armMediaFade]); like the wake fade, it begins
// on the first tick that sees media actually playing, so it reads and sets the volume being listened to.
//
// Phone test, 2026-10-02 (owner's choice): falling asleep used to put the owner's own volume back, so an
// audiobook restarted on waking played at full volume until the band confirmed the awakening, minutes later.
// Falling asleep now PARKS the volume at the fade's starting volume ([parkMediaFade]). A fade after waking
// starts from the parked volume and keeps the night's original.
//
// Owner spec, 2026-10-02: the fade owns the volume from Start night until the morning alarm. A volume the
// owner sets himself in between is pulled back to the fade's schedule on the next tick ([fadeTickAction]),
// and every awakening starts again from the starting volume. Once the morning alarm has rung there is no
// fading at all: the next tick puts the owner's own volume back, so media in bed before the out-of-bed nudge
// plays at his volume. End night does the same for a night that never reached its morning alarm.
//
// Phone test, 2026-10-02: until the morning alarm the fade only ever LOWERS the volume. Parking no longer
// lifts a faded volume back up to the starting volume ([fadeParkStep]), and a tick leaves a volume the owner
// turned down below the schedule where he put it ([fadeTickAction]).
//
// Owner decision, 2026-10-02 (phone log 22:03): the one exception is the START of a fade. Start night and
// every awakening set the volume to exactly the starting volume, raising a quieter one too ([fadeStartStep]),
// so a fade after waking no longer restarts from wherever the last one had got to.
//
// Emulator test, 2026-10-03 (owner decision): media started while the band still says asleep used to play at
// the parked volume all night - the pause on sleep only fires on falling asleep, and a fresh fade waited for the
// band to confirm an awakening, which it does late, or never when the owner lies still. Pressing play now starts
// a fresh fade whatever the band says ([shouldStartWakeFade]), and if the band still says asleep once that fade
// has run its course, the media is paused and the volume parked again ([shouldPauseFinishedFade]).

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.nikita.sleepcycle.engine.SleepState
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

private const val LOG_TAG = "MediaFade"

/** A whole volume range, in the percent the Settings screen's starting volume is given in. */
private const val FULL_RANGE_PERCENT = 100.0

/** How long the starting volume holds before the first step down. */
private val FADE_HOLD: Duration = Duration.ofMinutes(10)

/** How often the volume loses one more step after the hold. */
private val FADE_STEP_EVERY: Duration = Duration.ofMinutes(5)

/**
 * The volume step a fade starts at: exactly [startPercent] of [maxStep] (the Settings screen's starting volume),
 * whatever was playing, raising a quieter volume too - owner decision, 2026-10-02: Start night and every
 * awakening begin at the starting volume, and a starting volume that feels too loud is changed in Settings.
 */
fun fadeStartStep(maxStep: Int, startPercent: Int): Int =
    (maxStep * startPercent / FULL_RANGE_PERCENT).roundToInt()

/**
 * The volume step a fade begun at [startStep] at [startedAt] should be at by [now]: [startStep] through the
 * hold, then one step lower at the hold's end and every [FADE_STEP_EVERY] after, never below the floor - the
 * Settings screen's ending volume [endPercent] of [maxStep], at least one step so playback stays audible - and
 * never above [startStep], so a fade starting at or below the floor, muted included, simply stays where it is.
 */
fun fadeTargetStep(startStep: Int, maxStep: Int, startedAt: Instant, now: Instant, endPercent: Int): Int {
    val elapsed = Duration.between(startedAt, now)
    if (elapsed < FADE_HOLD) return startStep
    val stepsDown = 1 + elapsed.minus(FADE_HOLD).dividedBy(FADE_STEP_EVERY).toInt()
    val floorStep = fadeFloorStep(maxStep, endPercent)
    return minOf(startStep, maxOf(floorStep, startStep - stepsDown))
}

/**
 * Whether this tick should start a fade: Start night or falling asleep armed it ([fadeRearmed]), media is
 * playing, the fade is switched on, and the morning alarm has not rung (once up, the owner's media is left
 * alone - the same rule the pause on sleep follows). Whatever the band says: media started while it still says
 * asleep is faded too (see this file's header). Not armed: a fade is already running (or parked) and carries on by itself.
 */
fun shouldStartWakeFade(fadeRearmed: Boolean, mediaPlaying: Boolean, fadeEnabled: Boolean, morningAlarmRang: Boolean): Boolean =
    fadeRearmed && mediaPlaying && fadeEnabled && !morningAlarmRang

/** The fade's floor step: the Settings screen's ending volume [endPercent] of [maxStep], at least one step. */
private fun fadeFloorStep(maxStep: Int, endPercent: Int): Int = maxOf(1, (maxStep * endPercent / FULL_RANGE_PERCENT).roundToInt())

/**
 * When a fade begun at [startStep] at [startedAt] has run its course: the step that reaches its floor (see
 * [fadeTargetStep]), or the hold's end for a fade that starts at or below the floor and never steps.
 */
fun fadeEndsAt(startStep: Int, maxStep: Int, startedAt: Instant, endPercent: Int): Instant {
    val stepsAfterFirst = maxOf(0, startStep - fadeFloorStep(maxStep, endPercent) - 1).toLong()
    return startedAt.plus(FADE_HOLD).plus(FADE_STEP_EVERY.multipliedBy(stepsAfterFirst))
}

/**
 * The fade's next step after [now], so a tick lands on it (ticks come only every 15 minutes while asleep): the
 * hold's end, then every [FADE_STEP_EVERY], up to [fadeEndsAt], where [shouldPauseFinishedFade] is decided;
 * null once the fade has run its course.
 */
fun nextFadeStepAt(startStep: Int, maxStep: Int, startedAt: Instant, now: Instant, endPercent: Int): Instant? {
    val endsAt = fadeEndsAt(startStep, maxStep, startedAt, endPercent)
    if (!now.isBefore(endsAt)) return null
    val holdEndsAt = startedAt.plus(FADE_HOLD)
    if (now.isBefore(holdEndsAt)) return holdEndsAt
    val stepsDone = Duration.between(holdEndsAt, now).dividedBy(FADE_STEP_EVERY)
    return minOf(endsAt, holdEndsAt.plus(FADE_STEP_EVERY.multipliedBy(stepsDone + 1)))
}

/**
 * Whether a running fade's media should be paused now (owner decision, 2026-10-03): the fade has run its course
 * ([fadeEndsAt]) and the band still says ASLEEP with media playing - media started while asleep, the owner most
 * likely drifted off again. Never after the morning alarm, like the pause on sleep.
 */
fun shouldPauseFinishedFade(sleepState: SleepState, mediaPlaying: Boolean, fadeEndsAt: Instant, now: Instant, morningAlarmRang: Boolean): Boolean =
    sleepState == SleepState.ASLEEP && mediaPlaying && !now.isBefore(fadeEndsAt) && !morningAlarmRang

/**
 * The original volume a new fade must restore at End night: the night's own, kept by a volume parked on
 * falling asleep ([parkedOriginalStep]), or the volume playing now ([currentStep]) for the night's first fade.
 */
fun fadeOriginalStep(currentStep: Int, parkedOriginalStep: Int?): Int = parkedOriginalStep ?: currentStep

/** The media volume [currentStep] as a whole percent of the phone's range [maxStep] (0 when it reports none), for the simulation block. */
fun mediaVolumePercent(currentStep: Int, maxStep: Int): Int =
    if (maxStep <= 0) 0 else (currentStep * FULL_RANGE_PERCENT / maxStep).roundToInt()

/** The volume parked on falling asleep: the fade's [startStep], or [currentStep] if the fade already took it lower. */
fun fadeParkStep(currentStep: Int, startStep: Int): Int = minOf(currentStep, startStep)

/** What one tick does to the media volume while a fade is running or parked (see [fadeTickAction]). */
sealed interface FadeTickAction {
    /** Set the volume to [step]. */
    data class SetVolume(val step: Int) : FadeTickAction
    /** The fade is over: put the owner's own volume back and forget the fade. */
    data object RestoreOriginal : FadeTickAction
    /** Leave the volume as it is. */
    data object None : FadeTickAction
}

/**
 * One tick's decision for a fade begun at [startStep] at [startedAt] (owner spec, 2026-10-02): once the morning
 * alarm has rung, the owner's own volume comes back; while [parked] (asleep, or awake with nothing playing yet)
 * the volume never goes above [startStep] and never steps down; otherwise the volume
 * is wherever [fadeTargetStep] says, down to [endPercent] - a [currentStep] the owner turned up himself is
 * pulled back to it, while one he turned down below it stays where he put it (the fade only ever lowers).
 */
fun fadeTickAction(
    startStep: Int, startedAt: Instant, parked: Boolean, currentStep: Int, maxStep: Int, now: Instant, endPercent: Int, morningAlarmRang: Boolean,
): FadeTickAction {
    if (morningAlarmRang) return FadeTickAction.RestoreOriginal
    if (parked) return if (currentStep > startStep) FadeTickAction.SetVolume(startStep) else FadeTickAction.None
    val targetStep = fadeTargetStep(startStep, maxStep, startedAt, now, endPercent)
    return if (targetStep >= currentStep) FadeTickAction.None else FadeTickAction.SetVolume(targetStep)
}

/**
 * A fade in progress, persisted so it survives the process between ticks: the owner's own volume before the
 * fade ([originalStep], put back once the fade is over), and where and when it began. [parked]: the owner fell
 * asleep, the volume sits at [startStep] and no longer steps down.
 */
private data class MediaFadeRecord(val originalStep: Int, val startStep: Int, val startedAt: Instant, val parked: Boolean = false)

private const val MEDIA_FADE_FILE_NAME = "media_fade.txt"

/** The record's optional fourth field, present while the volume is parked; a record without it is a running fade. */
private const val PARKED_FIELD = "parked"

/** Present once falling asleep has re-armed the fade for the next awakening; removed when a fade starts. */
private const val WAKE_FADE_REARM_FILE_NAME = "media_fade_rearm.txt"

/**
 * Starts a fade once media is playing (see [startWakeFadeIfDue]): lowers the media volume to [fadeStartStep]
 * and records the original. A fade left over from a night that never ended cleanly is finished first, so its
 * original volume is not lost; a parked volume carries its original over instead.
 */
fun startMediaFade(context: Context, nightStartedAt: Instant, now: Instant, startPercent: Int, debugNight: Boolean) {
    // A parked volume is not ended (that would put the original back mid-night); its original carries over.
    val parkedOriginalStep = readMediaFade(context)?.takeIf { it.parked }?.originalStep
    if (parkedOriginalStep != null) clearMediaFade(context) else endMediaFade(context, nightStartedAt, now, debugNight)
    clearWakeFadeRearm(context)
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return
    val currentStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    val originalStep = fadeOriginalStep(currentStep, parkedOriginalStep)
    val startStep = fadeStartStep(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), startPercent)
    if (startStep != currentStep) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, startStep, 0)
    // What the device reports right after the set, for the night log: shows a device that ignored the set.
    val actualStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    saveMediaFade(context, MediaFadeRecord(originalStep, startStep, now))
    appendNightLog(
        context, nightStartedAt,
        NightLogEvent(
            now, "media_fade_start",
            mapOf("originalStep" to originalStep.toString(), "startStep" to startStep.toString(), "actualStep" to actualStep.toString(), "mediaPlaying" to audioManager.isMusicActive.toString())
        ),
        debugNight
    )
}

/**
 * One tick of a fade, running or parked: applies [fadeTickAction] - steps the volume down to the Settings
 * screen's ending volume [endPercent], pulls a volume the owner turned up himself back to the schedule, and
 * once the morning alarm has rung ([morningAlarmRang]) puts the owner's own volume back for good.
 */
fun stepMediaFade(context: Context, nightStartedAt: Instant, now: Instant, endPercent: Int, morningAlarmRang: Boolean, debugNight: Boolean) {
    val record = readMediaFade(context) ?: return
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return
    val currentStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    val maxStep = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    when (val action = fadeTickAction(record.startStep, record.startedAt, record.parked, currentStep, maxStep, now, endPercent, morningAlarmRang)) {
        FadeTickAction.RestoreOriginal -> endMediaFade(context, nightStartedAt, now, debugNight)
        FadeTickAction.None -> Unit
        is FadeTickAction.SetVolume -> {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, action.step, 0)
            appendNightLog(context, nightStartedAt, NightLogEvent(now, "media_fade_step", mapOf("step" to action.step.toString(), "from" to currentStep.toString())), debugNight)
        }
    }
}

/**
 * Ends the fade, if one is running or parked: puts the owner's original volume back. Called by the first tick
 * after the morning alarm, when the night ends, and at Start night for a night that never ended cleanly.
 */
fun endMediaFade(context: Context, nightStartedAt: Instant, now: Instant, debugNight: Boolean) {
    val record = readMediaFade(context) ?: return
    clearMediaFade(context)
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, record.originalStep, 0)
    appendNightLog(context, nightStartedAt, NightLogEvent(now, "media_fade_end", mapOf("restoredStep" to record.originalStep.toString())), debugNight)
}

/**
 * Falling asleep, once media is paused: parks the volume at the fade's starting volume, or lower where the
 * fade already got to ([fadeParkStep]), so media restarted on waking plays quietly from the first second (see
 * this file's header), whatever volume was playing before.
 */
fun parkMediaFade(context: Context, nightStartedAt: Instant, now: Instant, debugNight: Boolean) {
    val record = readMediaFade(context) ?: return
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return
    val currentStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    val parkStep = fadeParkStep(currentStep, record.startStep)
    if (parkStep != currentStep) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, parkStep, 0)
    val parkedStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    saveMediaFade(context, record.copy(parked = true))
    appendNightLog(context, nightStartedAt, NightLogEvent(now, "media_fade_parked", mapOf("step" to parkedStep.toString())), debugNight)
}

/**
 * Start night: arms the fade to begin once media is playing (see this file's header). A fade left over from a
 * night that never ended cleanly is finished first, so its original volume is not lost.
 */
fun armMediaFade(context: Context, nightStartedAt: Instant, now: Instant, debugNight: Boolean) {
    endMediaFade(context, nightStartedAt, now, debugNight)
    rearmMediaFadeForWake(context)
    appendNightLog(context, nightStartedAt, NightLogEvent(now, "media_fade_armed", emptyMap()), debugNight)
}

/** Falling asleep re-arms the fade for the next awakening (see [shouldStartWakeFade]). */
fun rearmMediaFadeForWake(context: Context) {
    try {
        writeTextAtomically(wakeFadeRearmFile(context), "1")
    } catch (error: Exception) {
        Log.e(LOG_TAG, "failed to re-arm the media fade for the next awakening", error)
    }
}

/**
 * Starts a fresh fade once [shouldStartWakeFade] says so - owner request, 2026-10-02: waking in the night and
 * playing the audiobook again fades it again, from the Settings screen's starting volume [startPercent].
 */
fun startWakeFadeIfDue(
    context: Context, nightStartedAt: Instant, now: Instant,
    fadeEnabled: Boolean, startPercent: Int, morningAlarmRang: Boolean, debugNight: Boolean,
): Boolean {
    val mediaPlaying = context.getSystemService(AudioManager::class.java)?.isMusicActive ?: false
    if (!shouldStartWakeFade(wakeFadeRearmFile(context).exists(), mediaPlaying, fadeEnabled, morningAlarmRang)) return false
    startMediaFade(context, nightStartedAt, now, startPercent, debugNight)
    return true
}

/**
 * Pauses the media of a fade that has run its course while the band still says asleep, then parks the volume
 * and re-arms the fade like falling asleep does ([shouldPauseFinishedFade]). An app that ignores the pause keeps
 * its faded volume and is tried again on the next tick.
 */
suspend fun pauseFinishedFadeIfDue(
    context: Context, nightStartedAt: Instant, now: Instant, sleepState: SleepState, endPercent: Int, morningAlarmRang: Boolean, debugNight: Boolean,
) {
    val record = readMediaFade(context)?.takeUnless { it.parked } ?: return
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return
    val endsAt = fadeEndsAt(record.startStep, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), record.startedAt, endPercent)
    if (!shouldPauseFinishedFade(sleepState, audioManager.isMusicActive, endsAt, now, morningAlarmRang)) return
    val result = pausePlayingMedia(context)
    appendNightLog(context, nightStartedAt, NightLogEvent(now, "media_pause_after_fade", mapOf("result" to result.name.lowercase())), debugNight)
    if (result == MediaPauseResult.STILL_PLAYING) return
    parkMediaFade(context, nightStartedAt, now, debugNight)
    rearmMediaFadeForWake(context)
}

/** The running fade's next step ([nextFadeStepAt]) for the night's next tick, or null when no fade is running. */
fun mediaFadeNextStepAt(context: Context, now: Instant, endPercent: Int): Instant? {
    val record = readMediaFade(context)?.takeUnless { it.parked } ?: return null
    val maxStep = context.getSystemService(AudioManager::class.java)?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: return null
    return nextFadeStepAt(record.startStep, maxStep, record.startedAt, now, endPercent)
}

private fun wakeFadeRearmFile(context: Context): File = File(context.filesDir, WAKE_FADE_REARM_FILE_NAME)

private fun clearWakeFadeRearm(context: Context) {
    val file = wakeFadeRearmFile(context)
    if (file.exists() && !file.delete()) Log.e(LOG_TAG, "failed to clear the media fade re-arm marker")
}

private fun mediaFadeFile(context: Context): File = File(context.filesDir, MEDIA_FADE_FILE_NAME)

private fun saveMediaFade(context: Context, record: MediaFadeRecord) {
    try {
        val parkedField = if (record.parked) " $PARKED_FIELD" else ""
        writeTextAtomically(mediaFadeFile(context), "${record.originalStep} ${record.startStep} ${record.startedAt}$parkedField")
    } catch (error: Exception) {
        Log.e(LOG_TAG, "failed to save the media fade record", error)
    }
}

/** The fade in progress, or null when none is, or the file is unreadable (never throws). */
private fun readMediaFade(context: Context): MediaFadeRecord? {
    val file = mediaFadeFile(context)
    if (!file.exists()) return null
    val parts = runCatching { file.readText().trim().split(" ") }.getOrNull() ?: return null
    return runCatching { MediaFadeRecord(parts[0].toInt(), parts[1].toInt(), Instant.parse(parts[2]), parked = parts.getOrNull(3) == PARKED_FIELD) }
        .onFailure { Log.e(LOG_TAG, "unparsable media fade record, treating as absent", it) }
        .getOrNull()
}

private fun clearMediaFade(context: Context) {
    val file = mediaFadeFile(context)
    if (file.exists() && !file.delete()) Log.e(LOG_TAG, "failed to delete the media fade record")
}
