package com.nikita.sleepcycle.night

// File purpose: the bedtime media fade - owner request, 2026-10-02. Once media is playing after Start night the
// media volume drops to the Settings screen's starting volume (the fade can be switched off) and fades down to
// the Settings screen's ending volume, where it stays until the band says the owner is asleep (MediaPauseOnSleep.kt
// then pauses playback and the volume is parked at the starting volume - see below).
//
// Owner request, 2026-10-02: the fade runs again after a mid-night awakening. Falling asleep re-arms it, and
// the first check that sees the owner awake with media playing again starts a fresh fade ([shouldStartWakeFade]).
//
// Phone test, 2026-10-02: Start night used to set the volume at the tap itself. With the audiobook started
// afterwards (or on headphones, which keep a volume of their own) that changed a volume nobody heard, and the
// next tick read the playing device's own, different volume as the owner overriding the fade - so it ended on
// the spot, before any step. Start night now only ARMS the fade ([armMediaFade]); like the wake fade, it begins
// once media is actually playing, so it reads and sets the volume being listened to.
//
// Phone test, 2026-10-02 (owner's choice): falling asleep used to put the owner's own volume back, so an
// audiobook restarted on waking played at full volume until the band confirmed the awakening, minutes later.
// Falling asleep now PARKS the volume at the fade's starting volume ([parkMediaFade]). A fade after waking
// starts from the parked volume and keeps the night's original.
//
// Owner spec, 2026-10-02: the fade owns the volume from Start night until the morning alarm, and every awakening
// starts again from the starting volume. Once the morning alarm has rung there is no fading at all: the next
// tick puts the owner's own volume back, so media in bed before the out-of-bed nudge plays at his volume. End
// night does the same for a night that never reached its morning alarm.
//
// Phone test, 2026-10-02: until the morning alarm the fade only ever LOWERS the volume. Parking never lifts a
// faded volume back up to the starting volume ([fadeParkStep]).
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
//
// Owner spec, 2026-10-21/22 (PER-134 "Fade v2", PER-135): the fade used to hold 10 minutes, then drop one volume
// step every 5 minutes on the night's ticks, which Doze delays so the steps bunched; the owner found the drops
// abrupt. Now:
// - Smooth: a target line falls in dB at a speed worked out from the playing output's starting and ending volume
//   and the shared fade duration ([fadeRateDbPerMinute]), on that output's real dB curve. The hardware volume sits
//   on the lowest step still at or above the line and a small digital gain fills the rest ([splitFadeTarget],
//   MediaFadeGain.kt), updated every 200 ms by MediaFadeRunner.kt only while the line is falling. Never more than
//   one hardware step per update, so drops never bunch. Without the gain (effect unavailable or lost) the same line
//   is followed with plain steps, logged once.
// - Screen: a fade begins only once media is playing AND the screen is off, and the line freezes while the screen
//   is on (or nothing plays), carrying on from there once it is off again (owner, 2026-10-21: "I don't want it to
//   start dropping the audio while the screen is still active").
// - Owner's own volume: a volume he raises or lowers himself (side buttons included) becomes the line's new start
//   and keeps falling at the same speed ([fadeReanchorCause]). The old pull back to the schedule is gone.
// - Output: each output (phone speaker, Bluetooth) has its own starting and ending volume; when media moves to
//   another output mid-fade the line starts again from that output's own volume, at that output's speed.
// The gain is released whenever a fade ends, parks, is switched off, reaches its ending volume, or an alarm rings.

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.nikita.sleepcycle.engine.SleepState
import com.nikita.sleepcycle.ui.components.MEDIA_PLAYBACK_ATTRIBUTES
import com.nikita.sleepcycle.ui.state.fadeOutputFor
import com.nikita.sleepcycle.ui.state.mediaOutputFor
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val LOG_TAG = "MediaFade"

/** A whole volume range, in the percent the Settings screen's volumes are given in. */
private const val FULL_RANGE_PERCENT = 100.0

/** Slack for comparing dB values that went through arithmetic: well below anything audible. */
private const val DB_TOLERANCE = 0.01

private const val MILLIS_PER_MINUTE = 60_000.0

/**
 * AOSP's default media volume curve (default_volume_tables.xml), percent of the range to dB: used when the phone
 * does not report a usable curve of its own ([isUsableVolumeCurve]).
 */
private val DEFAULT_CURVE_POINTS: List<Pair<Double, Double>> = listOf(1.0 to -58.0, 20.0 to -40.0, 60.0 to -17.0, 100.0 to 0.0)

/**
 * The volume step a fade starts at: exactly [startPercent] of [maxStep] (the Settings screen's starting volume),
 * whatever was playing, raising a quieter volume too - owner decision, 2026-10-02: Start night and every
 * awakening begin at the starting volume, and a starting volume that feels too loud is changed in Settings.
 */
fun fadeStartStep(maxStep: Int, startPercent: Int): Int = fadeVolumeStep(startPercent, maxStep)

/** The fade's floor step: the Settings screen's ending volume [endPercent] of [maxStep], at least one step so playback stays audible. */
fun fadeFloorStep(maxStep: Int, endPercent: Int): Int = maxOf(1, fadeVolumeStep(endPercent, maxStep))

/**
 * The media volume's loudness per step on one output, in dB: [stepDbs] index = step, so index 0 (muted) is
 * usually minus infinity. Read from the phone ([readMediaVolumeCurve]) or AOSP's default ([defaultVolumeCurve]).
 */
class VolumeCurve(val stepDbs: List<Double>) {
    fun db(step: Int): Double = stepDbs[step.coerceIn(0, stepDbs.lastIndex)]
}

/** Whether a curve read from the phone can be trusted: every step above muted finite and louder than the one below. */
fun isUsableVolumeCurve(stepDbs: List<Double>): Boolean =
    stepDbs.size >= 2 && stepDbs.drop(1).all { it.isFinite() } && stepDbs.drop(1).zipWithNext().all { (lower, upper) -> upper > lower }

/** AOSP's default media curve over [maxStep] steps: about -58 dB at 1% of the range up to 0 dB at the top. */
fun defaultVolumeCurve(maxStep: Int): VolumeCurve {
    val steps = maxOf(1, maxStep)
    return VolumeCurve(listOf(Double.NEGATIVE_INFINITY) + (1..steps).map { defaultCurveDb(it * FULL_RANGE_PERCENT / steps) })
}

private fun defaultCurveDb(percent: Double): Double {
    val (firstPercent, firstDb) = DEFAULT_CURVE_POINTS.first()
    if (percent <= firstPercent) return firstDb
    val upperIndex = DEFAULT_CURVE_POINTS.indexOfFirst { it.first >= percent }
    if (upperIndex <= 0) return DEFAULT_CURVE_POINTS.last().second
    val (lowPercent, lowDb) = DEFAULT_CURVE_POINTS[upperIndex - 1]
    val (highPercent, highDb) = DEFAULT_CURVE_POINTS[upperIndex]
    return lowDb + (highDb - lowDb) * (percent - lowPercent) / (highPercent - lowPercent)
}

/**
 * The fade's speed in dB per minute (owner spec, 2026-10-22, PER-134: "you specify starting sound, ending
 * percentage, and the duration... automatically calculates the speed"): the playing output's starting volume
 * [startDb] down to its ending volume [endDb] over [duration]. 0 when the ending volume is not below the start.
 */
fun fadeRateDbPerMinute(startDb: Double, endDb: Double, duration: Duration): Double {
    val minutes = duration.toMillis() / MILLIS_PER_MINUTE
    if (minutes <= 0.0 || !(startDb > endDb)) return 0.0
    return (startDb - endDb) / minutes
}

/** The fade's target line: [anchorDb] at [anchorAt], falling from there at the fade's speed. */
data class FadeLine(val anchorDb: Double, val anchorAt: Instant)

/**
 * Where the line is at [now]: falling from its anchor at [rateDbPerMinute], held at [endDb] once it gets there.
 * A line anchored at or below [endDb] (the owner turned it down that far himself) stays where it is.
 */
fun fadeTargetDb(line: FadeLine, rateDbPerMinute: Double, endDb: Double, now: Instant): Double {
    if (line.anchorDb <= endDb) return line.anchorDb
    val minutes = Duration.between(line.anchorAt, now).toMillis().coerceAtLeast(0) / MILLIS_PER_MINUTE
    return maxOf(endDb, line.anchorDb - rateDbPerMinute * minutes)
}

/**
 * The line moved on to [now] and re-anchored there. [descendingSinceAnchor]: media played with the screen off
 * all the way since the anchor; when not, the line has been frozen and only its anchor time moves, so it carries
 * on from the same loudness once the screen goes off again - no jump, no reset (owner, 2026-10-21).
 */
fun advanceFadeLine(line: FadeLine, rateDbPerMinute: Double, endDb: Double, now: Instant, descendingSinceAnchor: Boolean): FadeLine =
    FadeLine(if (descendingSinceAnchor) fadeTargetDb(line, rateDbPerMinute, endDb, now) else line.anchorDb, now)

/** One update's volume: the hardware [step] and the digital [gainDb] (0 or below) on top of it. */
data class FadeSplit(val step: Int, val gainDb: Double)

/**
 * Splits [targetDb] into a hardware step and a digital gain (the hybrid, spike 2026-10-21): the lowest step
 * between [floorStep] and [currentStep] still at or above the target, but never more than one step below
 * [currentStep] per update, so steps never bunch, and never above [currentStep] (the fade only lowers). The gain
 * is the rest, never deeper than the gap to the next step down, so an effect that vanishes (the app killed) can
 * only ever make the media up to one step louder. A volume at or below [floorStep] is left alone.
 */
fun splitFadeTarget(targetDb: Double, curve: VolumeCurve, currentStep: Int, floorStep: Int): FadeSplit {
    if (currentStep <= floorStep) return FadeSplit(currentStep, 0.0)
    val wanted = (floorStep..currentStep).firstOrNull { curve.db(it) >= targetDb - DB_TOLERANCE } ?: currentStep
    val step = maxOf(wanted, currentStep - 1)
    return FadeSplit(step, gainBetween(targetDb, curve, step, floorStep))
}

/** The gain for [targetDb] on [step]: 0 at or above the step, down to the gap to the step below, and 0 on the floor. */
private fun gainBetween(targetDb: Double, curve: VolumeCurve, step: Int, floorStep: Int): Double {
    if (step <= floorStep) return 0.0
    val deepest = curve.db(step - 1) - curve.db(step)
    val gain = (targetDb - curve.db(step)).coerceIn(deepest, 0.0)
    return if (gain > -DB_TOLERANCE) 0.0 else gain
}

/** Why a running fade's line starts again from the volume playing now. */
enum class FadeReanchor { NONE, OWNER_VOLUME, OUTPUT }

/**
 * Whether the line starts again from the playing volume (owner spec, 2026-10-22): media moved to another
 * output ([recordedDeviceType] to [actualDeviceType], AudioDeviceInfo types), or the volume is not the step the
 * fade last left it at - the owner raised or lowered it himself, side buttons included. Either way it then keeps
 * falling at the same speed (owner: "in case you increase the volume myself, it's going to still be fading away
 * with the same rate"), with no snap back to the starting volume.
 */
fun fadeReanchorCause(recordedStep: Int, actualStep: Int, recordedDeviceType: Int, actualDeviceType: Int): FadeReanchor = when {
    recordedDeviceType != actualDeviceType -> FadeReanchor.OUTPUT
    recordedStep != actualStep -> FadeReanchor.OWNER_VOLUME
    else -> FadeReanchor.NONE
}

/**
 * Whether a fade begun at [startedAt] has run its course (owner decision, 2026-10-03): its line is at the ending
 * volume [endDb] and at least the fade [duration] has passed - so media pressed while the band says asleep is
 * never paused the moment it starts, whatever the volume.
 */
fun fadeHasRunItsCourse(targetDb: Double, endDb: Double, startedAt: Instant, now: Instant, duration: Duration): Boolean =
    targetDb <= endDb + DB_TOLERANCE && !now.isBefore(startedAt.plus(duration))

/**
 * When a falling [line] will have run its course ([fadeHasRunItsCourse]), so a tick lands there for the pause
 * decision (ticks come only every 15 minutes while asleep).
 */
fun fadeProjectedEndAt(line: FadeLine, rateDbPerMinute: Double, endDb: Double, startedAt: Instant, duration: Duration): Instant {
    val earliest = startedAt.plus(duration)
    if (line.anchorDb <= endDb || rateDbPerMinute <= 0.0) return maxOf(earliest, line.anchorAt)
    val reachesEndAt = line.anchorAt.plusMillis(((line.anchorDb - endDb) / rateDbPerMinute * MILLIS_PER_MINUTE).roundToLong())
    return maxOf(earliest, reachesEndAt)
}

/**
 * Whether this check should start a fade: Start night or falling asleep armed it ([fadeRearmed]), media is
 * playing, the screen is off ([screenOff], owner, 2026-10-21; always-on display counts as off), the fade is
 * switched on, and the morning alarm has not rung (once up, the owner's media is left alone - the same rule the
 * pause on sleep follows). Whatever the band says: media started while it still says asleep is faded too (see
 * this file's header). Not armed: a fade is already running (or parked) and carries on by itself.
 */
fun shouldStartWakeFade(fadeRearmed: Boolean, mediaPlaying: Boolean, screenOff: Boolean, fadeEnabled: Boolean, morningAlarmRang: Boolean): Boolean =
    fadeRearmed && mediaPlaying && screenOff && fadeEnabled && !morningAlarmRang

/**
 * Whether a running fade's media should be paused now (owner decision, 2026-10-03): the fade has run its course
 * ([fadeHasRunItsCourse]) and the band still says ASLEEP with media playing - media started while asleep, the
 * owner most likely drifted off again. Never after the morning alarm, like the pause on sleep.
 */
fun shouldPauseFinishedFade(sleepState: SleepState, mediaPlaying: Boolean, fadeFinished: Boolean, morningAlarmRang: Boolean): Boolean =
    sleepState == SleepState.ASLEEP && mediaPlaying && fadeFinished && !morningAlarmRang

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
    /** Move the running fade along its line ([advanceMediaFade]). */
    data object Advance : FadeTickAction
    /** Leave the volume as it is. */
    data object None : FadeTickAction
}

/**
 * One tick's decision for a fade (owner spec, 2026-10-02): once the morning alarm has rung, the owner's own volume
 * comes back; while [parked] (asleep, or awake with nothing playing yet) the volume never goes above [startStep]
 * and never steps down; a running fade follows its line.
 */
fun fadeTickAction(startStep: Int, parked: Boolean, currentStep: Int, morningAlarmRang: Boolean): FadeTickAction = when {
    morningAlarmRang -> FadeTickAction.RestoreOriginal
    !parked -> FadeTickAction.Advance
    currentStep > startStep -> FadeTickAction.SetVolume(startStep)
    else -> FadeTickAction.None
}

/** Where a running fade stands after one update ([advanceMediaFade]). */
enum class FadeProgress {
    /** No running fade: none at all, or parked. */
    NO_FADE,
    /** Frozen: the screen is on or nothing plays. */
    HOLDING,
    /** The line is falling: update again soon. */
    DESCENDING,
    /** The line has reached the ending volume (or a volume below it) and holds there. */
    AT_END,
}

/** One update's outcome: its [progress], and whether the fade has run its course for the pause decision. */
data class FadeAdvance(val progress: FadeProgress, val finished: Boolean)

/**
 * A fade in progress, persisted so it survives the process: the owner's own volume before the fade
 * ([originalStep], put back once the fade is over), and where and when it began. [parked]: the owner fell asleep,
 * the volume sits at [startStep] and no longer moves. [line] and [rateDbPerMinute]: the target line as last saved
 * (null for a record written by an older build, which re-anchors on its first update); saved again whenever the
 * hardware steps, the speed changes, the line re-anchors or it freezes or thaws, so the line computed from it is
 * always right. [lastStep] and [deviceType]: the step the fade last left the volume at and the output it was on,
 * to tell the owner's own changes and a switch of output ([fadeReanchorCause]). [frozen]: the screen was on or
 * nothing played at the last save.
 */
private data class MediaFadeRecord(
    val originalStep: Int,
    val startStep: Int,
    val startedAt: Instant,
    val parked: Boolean = false,
    val line: FadeLine? = null,
    val rateDbPerMinute: Double = 0.0,
    val lastStep: Int = startStep,
    val deviceType: Int = AudioDeviceInfo.TYPE_UNKNOWN,
    val frozen: Boolean = false,
)

private const val MEDIA_FADE_FILE_NAME = "media_fade.txt"

/** The record's first field in the current format; a record without it is from an older build (3 or 4 fields). */
private const val RECORD_VERSION = "v2"

/** The record's parked field; an older build's record carries it only while the volume is parked. */
private const val PARKED_FIELD = "parked"
private const val RUNNING_FIELD = "running"
private const val FROZEN_FIELD = "frozen"
private const val MOVING_FIELD = "moving"
private const val NO_LINE_FIELD = "-"

/** Present once falling asleep has re-armed the fade for the next awakening; removed when a fade starts. */
private const val WAKE_FADE_REARM_FILE_NAME = "media_fade_rearm.txt"

/**
 * Guards the fade record, the media volume and the gain: the runner's updates every 200 ms, the night's ticks and
 * the watcher's checks all read, decide and write under it, so two of them never step the same volume twice.
 * Reentrant, so a locked function may call another.
 */
private val fadeLock = Any()

/** The phone's own curve for the last output read, so an update every 200 ms does not ask for it again. Under [fadeLock]. */
private var cachedCurve: Triple<Int, Int, VolumeCurve>? = null

/** Whether the screen is off, always-on display included (owner, 2026-10-21): only then may a fade begin or fall. */
fun isScreenOff(context: Context): Boolean = context.getSystemService(PowerManager::class.java)?.isInteractive == false

/**
 * Which output's starting and ending volume apply now (owner spec, 2026-10-22, PER-135): the device media plays
 * on, classified exactly as the live volume line does, so the pair Settings shows is the pair the fade uses. Only
 * the device TYPE is read, never a Bluetooth address.
 */
fun readFadeOutput(context: Context): FadeOutput = fadeOutputFor(mediaOutputFor(readMediaDeviceTypes(context)))

/** The AudioDeviceInfo types media plays on: the one device on Android 13+, every connected output before it. */
internal fun readMediaDeviceTypes(context: Context): List<Int> {
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return emptyList()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        audioManager.getAudioDevicesForAttributes(MEDIA_PLAYBACK_ATTRIBUTES).map { it.type }
    } else {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
    }
}

/** The one device type media plays on, out of [deviceTypes]: the one its output name comes from, else the speaker. */
private fun mediaDeviceType(deviceTypes: List<Int>): Int {
    val output = mediaOutputFor(deviceTypes)
    return deviceTypes.firstOrNull { mediaOutputFor(listOf(it)) == output } ?: AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
}

/**
 * The media volume's dB per step on [deviceType] (AudioManager.getStreamVolumeDb), or AOSP's default curve when
 * the phone's own is not usable (an emulator, an odd device, an error). Under [fadeLock].
 */
private fun readVolumeCurve(audioManager: AudioManager, maxStep: Int, deviceType: Int): VolumeCurve {
    cachedCurve?.let { (type, steps, curve) -> if (type == deviceType && steps == maxStep) return curve }
    val read = runCatching { (0..maxStep).map { audioManager.getStreamVolumeDb(AudioManager.STREAM_MUSIC, it, deviceType).toDouble() } }.getOrNull()
    val curve = if (read != null && isUsableVolumeCurve(read)) VolumeCurve(read) else defaultVolumeCurve(maxStep)
    cachedCurve = Triple(deviceType, maxStep, curve)
    return curve
}

/** The curve the fade uses for media playing now, so tests can work out what a fade should do. */
fun readMediaVolumeCurve(context: Context): VolumeCurve? = synchronized(fadeLock) {
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return@synchronized null
    readVolumeCurve(audioManager, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), mediaDeviceType(readMediaDeviceTypes(context)))
}

/** Everything one update needs about the output media plays on now, read in one go. */
private class FadeOutputReading(
    val audioManager: AudioManager,
    val deviceType: Int,
    val curve: VolumeCurve,
    val output: FadeOutput,
    val startStep: Int,
    val floorStep: Int,
    val endDb: Double,
    val rateDbPerMinute: Double,
)

/** Reads the playing output and works out its pair's steps and the fade's speed from [audio], live (owner decision, 2026-10-03). */
private fun readFadeOutputReading(context: Context, audio: BedtimeAudioSettings): FadeOutputReading? {
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return null
    val maxStep = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (maxStep <= 0) return null
    val deviceTypes = readMediaDeviceTypes(context)
    val deviceType = mediaDeviceType(deviceTypes)
    val curve = readVolumeCurve(audioManager, maxStep, deviceType)
    val output = fadeOutputFor(mediaOutputFor(deviceTypes))
    val volumes = audio.volumesFor(output)
    val startStep = fadeStartStep(maxStep, volumes.startPercent)
    val floorStep = fadeFloorStep(maxStep, volumes.endPercent)
    val endDb = curve.db(floorStep)
    val rate = fadeRateDbPerMinute(curve.db(startStep), endDb, fadeDuration(audio))
    return FadeOutputReading(audioManager, deviceType, curve, output, startStep, floorStep, endDb, rate)
}

private fun fadeDuration(audio: BedtimeAudioSettings): Duration = Duration.ofMinutes(audio.fadeDurationMinutes.toLong())

private fun formatDb(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

/**
 * Starts a fade once media is playing with the screen off (see [startWakeFadeIfDue]): sets the playing output's
 * starting volume ([fadeStartStep]) and records the original and the line's start. A fade left over from a night
 * that never ended cleanly is finished first, so its original volume is not lost; a parked volume carries its
 * original over instead.
 */
fun startMediaFade(context: Context, nightStartedAt: Instant, now: Instant, audio: BedtimeAudioSettings, debugNight: Boolean) {
    synchronized(fadeLock) { startMediaFadeLocked(context, nightStartedAt, now, audio, debugNight) }
}

private fun startMediaFadeLocked(context: Context, nightStartedAt: Instant, now: Instant, audio: BedtimeAudioSettings, debugNight: Boolean) {
    // A parked volume is not ended (that would put the original back mid-night); its original carries over.
    val parkedOriginalStep = readMediaFade(context)?.takeIf { it.parked }?.originalStep
    if (parkedOriginalStep != null) clearMediaFade(context) else endMediaFadeLocked(context, nightStartedAt, now, debugNight)
    clearWakeFadeRearm(context)
    MediaFadeGain.resetForNewFade()
    val reading = readFadeOutputReading(context, audio) ?: return
    val audioManager = reading.audioManager
    val currentStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    val originalStep = fadeOriginalStep(currentStep, parkedOriginalStep)
    if (reading.startStep != currentStep) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, reading.startStep, 0)
    // What the device reports right after the set, for the night log: shows a device that ignored the set.
    val actualStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    saveMediaFade(
        context,
        MediaFadeRecord(
            originalStep, reading.startStep, now, line = FadeLine(reading.curve.db(actualStep), now), rateDbPerMinute = reading.rateDbPerMinute,
            lastStep = actualStep, deviceType = reading.deviceType, frozen = false,
        )
    )
    appendNightLog(
        context, nightStartedAt,
        NightLogEvent(
            now, "media_fade_start",
            mapOf(
                "originalStep" to originalStep.toString(), "startStep" to reading.startStep.toString(), "actualStep" to actualStep.toString(),
                "mediaPlaying" to audioManager.isMusicActive.toString(), "output" to reading.output.name.lowercase(),
                "durationMinutes" to audio.fadeDurationMinutes.toString(), "dbPerMinute" to formatDb(reading.rateDbPerMinute),
            )
        ),
        debugNight
    )
}

/**
 * One update of a running fade (owner spec, 2026-10-22, PER-134): re-anchors the line on the owner's own volume
 * change or a switch of output, moves it on (or holds it while the screen is on or nothing plays), sets the
 * hardware step and the digital gain for it ([splitFadeTarget]), and saves the record when anything in it
 * changed. MediaFadeRunner.kt calls this every 200 ms while it returns [FadeProgress.DESCENDING]; every tick and
 * fade check calls it once too. The gain is used only while the fade is switched on: switched off mid-fade, the
 * running fade carries on to its floor in plain steps, its gain released.
 */
fun advanceMediaFade(context: Context, nightStartedAt: Instant, now: Instant, audio: BedtimeAudioSettings, debugNight: Boolean): FadeAdvance =
    synchronized(fadeLock) { advanceMediaFadeLocked(context, nightStartedAt, now, audio, debugNight) }

private fun advanceMediaFadeLocked(context: Context, nightStartedAt: Instant, now: Instant, audio: BedtimeAudioSettings, debugNight: Boolean): FadeAdvance {
    val record = readMediaFade(context)
    if (record == null || record.parked) {
        MediaFadeGain.release()
        return FadeAdvance(FadeProgress.NO_FADE, finished = false)
    }
    val reading = readFadeOutputReading(context, audio) ?: return FadeAdvance(FadeProgress.NO_FADE, finished = false)
    val audioManager = reading.audioManager
    val actualStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    val descending = audioManager.isMusicActive && isScreenOff(context)
    val cause = fadeReanchorCause(record.lastStep, actualStep, record.deviceType, reading.deviceType)
    val line = if (record.line != null && cause == FadeReanchor.NONE) {
        advanceFadeLine(record.line, record.rateDbPerMinute, reading.endDb, now, descendingSinceAnchor = !record.frozen)
    } else {
        if (record.line != null) {
            appendNightLog(
                context, nightStartedAt,
                NightLogEvent(now, "media_fade_reanchor", mapOf("cause" to cause.name.lowercase(), "step" to actualStep.toString(), "output" to reading.output.name.lowercase())),
                debugNight
            )
        }
        // The owner's own volume or a new output: the line starts again from the volume playing now.
        FadeLine(reading.curve.db(actualStep), now)
    }
    val split = splitFadeTarget(line.anchorDb, reading.curve, actualStep, reading.floorStep)
    var newStep = actualStep
    if (split.step != actualStep) {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, split.step, 0)
        newStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        appendNightLog(context, nightStartedAt, NightLogEvent(now, "media_fade_step", mapOf("step" to newStep.toString(), "from" to actualStep.toString())), debugNight)
    }
    val atEnd = line.anchorDb <= reading.endDb + DB_TOLERANCE
    applyFadeGain(context, nightStartedAt, now, split.gainDb, useGain = shouldFadeMedia(audio) && !atEnd, debugNight = debugNight)
    val updated = record.copy(line = line, rateDbPerMinute = reading.rateDbPerMinute, lastStep = newStep, deviceType = reading.deviceType, frozen = !descending)
    val worthSaving = record.line == null || cause != FadeReanchor.NONE || newStep != record.lastStep ||
        record.rateDbPerMinute != reading.rateDbPerMinute || record.frozen != updated.frozen
    if (worthSaving) saveMediaFade(context, updated)
    val finished = fadeHasRunItsCourse(line.anchorDb, reading.endDb, record.startedAt, now, fadeDuration(audio))
    val progress = when {
        !descending -> FadeProgress.HOLDING
        atEnd -> FadeProgress.AT_END
        else -> FadeProgress.DESCENDING
    }
    return FadeAdvance(progress, finished)
}

/**
 * Applies the digital gain while [useGain], else releases it. The first time the effect is unavailable or loses
 * control in a fade, that is logged once (never any device address) and the fade carries on in plain steps.
 */
private fun applyFadeGain(context: Context, nightStartedAt: Instant, now: Instant, gainDb: Double, useGain: Boolean, debugNight: Boolean) {
    if (!useGain) {
        MediaFadeGain.release()
        return
    }
    val hadFallback = MediaFadeGain.fallback != null
    MediaFadeGain.apply(gainDb.toFloat())
    val fallback = MediaFadeGain.fallback
    if (!hadFallback && fallback != null) {
        appendNightLog(context, nightStartedAt, NightLogEvent(now, "media_fade_gain_fallback", mapOf("reason" to fallback.name.lowercase())), debugNight)
    }
}

/**
 * One tick's (or fade check's) share of a fade, running or parked: applies [fadeTickAction] - a running fade is
 * moved along its line ([advanceMediaFade]), a parked volume turned up is pulled back to the starting volume, and
 * once the morning alarm has rung ([morningAlarmRang]) the owner's own volume comes back for good. Returns
 * whether the running fade has run its course, for [pauseFinishedFadeIfDue].
 */
fun stepMediaFade(context: Context, nightStartedAt: Instant, now: Instant, audio: BedtimeAudioSettings, morningAlarmRang: Boolean, debugNight: Boolean): Boolean =
    synchronized(fadeLock) { stepMediaFadeLocked(context, nightStartedAt, now, audio, morningAlarmRang, debugNight) }

private fun stepMediaFadeLocked(context: Context, nightStartedAt: Instant, now: Instant, audio: BedtimeAudioSettings, morningAlarmRang: Boolean, debugNight: Boolean): Boolean {
    val record = readMediaFade(context) ?: return false
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return false
    val currentStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    when (val action = fadeTickAction(record.startStep, record.parked, currentStep, morningAlarmRang)) {
        FadeTickAction.RestoreOriginal -> endMediaFadeLocked(context, nightStartedAt, now, debugNight)
        FadeTickAction.Advance -> return advanceMediaFadeLocked(context, nightStartedAt, now, audio, debugNight).finished
        FadeTickAction.None -> Unit
        is FadeTickAction.SetVolume -> {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, action.step, 0)
            saveMediaFade(context, record.copy(lastStep = action.step))
            appendNightLog(context, nightStartedAt, NightLogEvent(now, "media_fade_step", mapOf("step" to action.step.toString(), "from" to currentStep.toString())), debugNight)
        }
    }
    return false
}

/**
 * Ends the fade, if one is running or parked: releases the digital gain and puts the owner's original volume
 * back. Called by the first tick after the morning alarm, when the night ends or is discarded, and at Start night
 * for a night that never ended cleanly.
 */
fun endMediaFade(context: Context, nightStartedAt: Instant, now: Instant, debugNight: Boolean) {
    synchronized(fadeLock) { endMediaFadeLocked(context, nightStartedAt, now, debugNight) }
}

private fun endMediaFadeLocked(context: Context, nightStartedAt: Instant, now: Instant, debugNight: Boolean) {
    MediaFadeGain.release()
    val record = readMediaFade(context) ?: return
    clearMediaFade(context)
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, record.originalStep, 0)
    appendNightLog(context, nightStartedAt, NightLogEvent(now, "media_fade_end", mapOf("restoredStep" to record.originalStep.toString())), debugNight)
}

/**
 * Falling asleep, once media is paused: releases the digital gain and parks the volume at the fade's starting
 * volume, or lower where the fade already got to ([fadeParkStep]), so media restarted on waking plays quietly
 * from the first second (see this file's header), whatever volume was playing before.
 */
fun parkMediaFade(context: Context, nightStartedAt: Instant, now: Instant, debugNight: Boolean) {
    synchronized(fadeLock) { parkMediaFadeLocked(context, nightStartedAt, now, debugNight) }
}

private fun parkMediaFadeLocked(context: Context, nightStartedAt: Instant, now: Instant, debugNight: Boolean) {
    MediaFadeGain.release()
    val record = readMediaFade(context) ?: return
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return
    val currentStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    val parkStep = fadeParkStep(currentStep, record.startStep)
    if (parkStep != currentStep) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, parkStep, 0)
    val parkedStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    saveMediaFade(context, record.copy(parked = true, lastStep = parkedStep))
    appendNightLog(context, nightStartedAt, NightLogEvent(now, "media_fade_parked", mapOf("step" to parkedStep.toString())), debugNight)
}

/**
 * Start night: arms the fade to begin once media is playing with the screen off (see this file's header). A
 * fade left over from a night that never ended cleanly is finished first, so its original volume is not lost.
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
 * playing the audiobook again fades it again, from the playing output's starting volume in [audio].
 */
fun startWakeFadeIfDue(
    context: Context, nightStartedAt: Instant, now: Instant, audio: BedtimeAudioSettings, morningAlarmRang: Boolean, debugNight: Boolean,
): Boolean {
    val mediaPlaying = context.getSystemService(AudioManager::class.java)?.isMusicActive ?: false
    if (!shouldStartWakeFade(wakeFadeRearmFile(context).exists(), mediaPlaying, isScreenOff(context), shouldFadeMedia(audio), morningAlarmRang)) return false
    startMediaFade(context, nightStartedAt, now, audio, debugNight)
    return true
}

/**
 * Pauses the media of a fade that has run its course ([fadeFinished], from [stepMediaFade]) while the band still
 * says asleep, then parks the volume and re-arms the fade like falling asleep does ([shouldPauseFinishedFade]).
 * An app that ignores the pause keeps its faded volume and is tried again on the next tick.
 */
suspend fun pauseFinishedFadeIfDue(
    context: Context, nightStartedAt: Instant, now: Instant, sleepState: SleepState, fadeFinished: Boolean, morningAlarmRang: Boolean, debugNight: Boolean,
) {
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return
    if (!shouldPauseFinishedFade(sleepState, audioManager.isMusicActive, fadeFinished, morningAlarmRang)) return
    val result = pausePlayingMedia(context)
    appendNightLog(context, nightStartedAt, NightLogEvent(now, "media_pause_after_fade", mapOf("result" to result.name.lowercase())), debugNight)
    if (result == MediaPauseResult.STILL_PLAYING) return
    parkMediaFade(context, nightStartedAt, now, debugNight)
    rearmMediaFadeForWake(context)
}

/**
 * When the running fade will have run its course ([fadeProjectedEndAt]), for the night's next tick, so the pause
 * after a fade is decided on time while asleep; null when no fade is falling (none, parked, or frozen) or that
 * instant is not after [now], so a tick is never booked in the past.
 */
fun mediaFadeEndsAt(context: Context, now: Instant, audio: BedtimeAudioSettings): Instant? = synchronized(fadeLock) {
    val record = readMediaFade(context)?.takeUnless { it.parked || it.frozen } ?: return@synchronized null
    val line = record.line ?: return@synchronized null
    val reading = readFadeOutputReading(context, audio) ?: return@synchronized null
    fadeProjectedEndAt(line, record.rateDbPerMinute, reading.endDb, record.startedAt, fadeDuration(audio)).takeIf { it.isAfter(now) }
}

private fun wakeFadeRearmFile(context: Context): File = File(context.filesDir, WAKE_FADE_REARM_FILE_NAME)

private fun clearWakeFadeRearm(context: Context) {
    val file = wakeFadeRearmFile(context)
    if (file.exists() && !file.delete()) Log.e(LOG_TAG, "failed to clear the media fade re-arm marker")
}

private fun mediaFadeFile(context: Context): File = File(context.filesDir, MEDIA_FADE_FILE_NAME)

private fun saveMediaFade(context: Context, record: MediaFadeRecord) {
    try {
        val fields = listOf(
            RECORD_VERSION, record.originalStep, record.startStep, record.startedAt,
            if (record.parked) PARKED_FIELD else RUNNING_FIELD,
            record.line?.anchorDb ?: NO_LINE_FIELD, record.line?.anchorAt ?: NO_LINE_FIELD,
            record.rateDbPerMinute, record.lastStep, record.deviceType,
            if (record.frozen) FROZEN_FIELD else MOVING_FIELD,
        )
        writeTextAtomically(mediaFadeFile(context), fields.joinToString(" "))
    } catch (error: Exception) {
        Log.e(LOG_TAG, "failed to save the media fade record", error)
    }
}

/** The fade in progress, or null when none is, or the file is unreadable (never throws). */
private fun readMediaFade(context: Context): MediaFadeRecord? {
    val file = mediaFadeFile(context)
    if (!file.exists()) return null
    val parts = runCatching { file.readText().trim().split(" ") }.getOrNull() ?: return null
    return runCatching { if (parts[0] == RECORD_VERSION) parseRecord(parts) else parseOlderRecord(parts) }
        .onFailure { Log.e(LOG_TAG, "unparsable media fade record, treating as absent", it) }
        .getOrNull()
}

private fun parseRecord(parts: List<String>): MediaFadeRecord {
    val line = if (parts[5] == NO_LINE_FIELD) null else FadeLine(parts[5].toDouble(), Instant.parse(parts[6]))
    return MediaFadeRecord(
        originalStep = parts[1].toInt(), startStep = parts[2].toInt(), startedAt = Instant.parse(parts[3]), parked = parts[4] == PARKED_FIELD,
        line = line, rateDbPerMinute = parts[7].toDouble(), lastStep = parts[8].toInt(), deviceType = parts[9].toInt(), frozen = parts[10] == FROZEN_FIELD,
    )
}

/** A record an older build wrote: "original start startedAt [parked]". Its first update re-anchors the line. */
private fun parseOlderRecord(parts: List<String>): MediaFadeRecord =
    MediaFadeRecord(parts[0].toInt(), parts[1].toInt(), Instant.parse(parts[2]), parked = parts.getOrNull(3) == PARKED_FIELD)

private fun clearMediaFade(context: Context) {
    val file = mediaFadeFile(context)
    if (file.exists() && !file.delete()) Log.e(LOG_TAG, "failed to delete the media fade record")
}
