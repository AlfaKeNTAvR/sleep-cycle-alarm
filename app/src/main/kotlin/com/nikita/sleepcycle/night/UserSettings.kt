package com.nikita.sleepcycle.night

// File purpose: the owner's own settings from the Settings screen (owner spec, 2026-10-02) - the after-alarm
// timing, the bedtime audio switches and the sleep rating - with their defaults and the limits each stepper
// offers. Pure: persisted alongside the rest of AppSettings (AppSettings.kt), read by the night and the UI.
//
// Owner spec, 2026-10-22 (PER-134, PER-135): the fade's starting and ending volume are kept per output (phone
// speaker or Bluetooth) and offered as the phone's own volume steps, and one shared fade duration replaces the
// hidden 10 minute hold and 5 minute step.

import java.time.LocalTime
import kotlin.math.roundToInt

private const val DEFAULT_NUDGE_MINUTES = 10
private const val DEFAULT_NAP_MINUTES = 20
private const val DEFAULT_FADE_START_PERCENT = 25
private const val DEFAULT_FADE_END_PERCENT = 5
private const val DEFAULT_FADE_DURATION_MINUTES = 15
private val DEFAULT_RATING_ASK_AT: LocalTime = LocalTime.of(15, 0)

/** A whole volume range, in percent. */
private const val FULL_RANGE_PERCENT = 100.0

/**
 * After the alarm: how long after a ring ends the out-of-bed nudge rings, and the one nap length used by both
 * the Night screen's Nap button and the engine's naps between cycles. A night captures these at Start night
 * (NightState.afterAlarm); since PER-148 (owner spec, 2026-10-23) a change mid-night is copied into the running
 * night and applies from the next nap or nudge armed, never moving one already armed.
 */
data class AfterAlarmSettings(
    val nudgeMinutes: Int = DEFAULT_NUDGE_MINUTES,
    val napMinutes: Int = DEFAULT_NAP_MINUTES,
)

/**
 * Which pair of starting and ending volumes a fade uses (owner spec, 2026-10-22, PER-135): Bluetooth, or the
 * phone speaker for everything else - wired and USB headphones included.
 */
enum class FadeOutput { PHONE_SPEAKER, BLUETOOTH }

/**
 * One output's starting and ending volume, as a percent of the media volume range. Stored as the percent, shown
 * and used snapped to the phone's own volume steps ([snapFadeVolumePercent]), so a value saved before the steps
 * (25% and 5%) reads as the step nearest to it.
 */
data class FadeVolumes(
    /** Where the fade starts - exactly there, even when that raises what was already playing. Read live mid-night: a change applies to the next fade. */
    val startPercent: Int = DEFAULT_FADE_START_PERCENT,
    /** Where the fade stops (owner request, 2026-10-02). At or above [startPercent] the fade simply holds the starting volume. */
    val endPercent: Int = DEFAULT_FADE_END_PERCENT,
)

/**
 * Bedtime audio: the media volume fade from Start night (MediaFade.kt) and the pause on falling asleep
 * (MediaPauseOnSleep.kt). Owner spec, 2026-10-22 (PER-134, PER-135): a starting and ending volume per output,
 * "remembered per device and automatically switched depending on whether I'm listening from the speaker" or
 * Bluetooth, and one shared [fadeDurationMinutes] the fade's speed is worked out from.
 */
data class BedtimeAudioSettings(
    val fadeEnabled: Boolean = true,
    val phoneSpeakerVolumes: FadeVolumes = FadeVolumes(),
    val bluetoothVolumes: FadeVolumes = FadeVolumes(),
    /** How long a fade takes from its output's starting volume to its ending volume. Replaces the old hidden 10 min hold and 5 min step. */
    val fadeDurationMinutes: Int = DEFAULT_FADE_DURATION_MINUTES,
    val pauseWhenAsleep: Boolean = true,
) {
    fun volumesFor(output: FadeOutput): FadeVolumes = when (output) {
        FadeOutput.PHONE_SPEAKER -> phoneSpeakerVolumes
        FadeOutput.BLUETOOTH -> bluetoothVolumes
    }

    /** These settings with [output]'s pair replaced by [volumes]; the other output's pair is untouched. */
    fun withVolumesFor(output: FadeOutput, volumes: FadeVolumes): BedtimeAudioSettings = when (output) {
        FadeOutput.PHONE_SPEAKER -> copy(phoneSpeakerVolumes = volumes)
        FadeOutput.BLUETOOTH -> copy(bluetoothVolumes = volumes)
    }
}

/** Owner request, 2026-10-02: the fade only runs alongside the pause - it exists to lead into it, so switching the pause off switches the fade off too. */
fun shouldFadeMedia(audio: BedtimeAudioSettings): Boolean = audio.pauseWhenAsleep && audio.fadeEnabled

/** Sleep rating:the morning report's "How did you sleep?" card, and the later "Still feel the same?" notification at [askAt]. */
data class SleepRatingSettings(
    val enabled: Boolean = true,
    val askAgainLater: Boolean = true,
    val askAt: LocalTime = DEFAULT_RATING_ASK_AT,
)

/** Each number the Settings screen changes with a minus/plus stepper: its allowed [range] and how far one tap moves it. */
enum class SettingStepper(val range: IntRange, val step: Int) {
    NUDGE_MINUTES(5..15, 5),
    NAP_MINUTES(10..30, 5),
    FADE_DURATION_MINUTES(5..60, 5),
}

/** [current] moved one step in [direction] (+1 or -1), held inside the stepper's range. */
fun stepSetting(stepper: SettingStepper, current: Int, direction: Int): Int =
    clampSetting(stepper, current + direction * stepper.step)

/** Whether a tap in [direction] would change [current] at all - the stepper's button is disabled when not. */
fun canStepSetting(stepper: SettingStepper, current: Int, direction: Int): Boolean =
    stepSetting(stepper, current, direction) != current

/** [value] held inside the stepper's range, so a stored value from an older build or a bad write still reads back usable. */
fun clampSetting(stepper: SettingStepper, value: Int): Int = value.coerceIn(stepper.range)

/**
 * The two fade volumes and the percent range each one offers (owner spec, 2026-10-02). Owner, 2026-10-22: "there
 * is no point in setting up in divisions of 5%, because everything works in 4%... do the same levels as the
 * phone" - so each range is offered as the phone's own media volume steps nearest to it ([fadeVolumeChoices]).
 */
enum class FadeVolumeKind(val percentRange: IntRange) {
    START(10..50),
    END(5..45),
}

/** The media volume step nearest to [percent] of [maxStep], a step exactly between two going up. */
fun fadeVolumeStep(percent: Int, maxStep: Int): Int = (maxStep * percent / FULL_RANGE_PERCENT).roundToInt()

/** The steps [kind] offers on a phone with [maxStep] media volume steps: the ones nearest its range's ends and all between, never step 0. */
private fun fadeVolumeStepRange(kind: FadeVolumeKind, maxStep: Int): IntRange =
    maxOf(1, fadeVolumeStep(kind.percentRange.first, maxStep))..maxOf(1, fadeVolumeStep(kind.percentRange.last, maxStep))

/**
 * Every value the [kind] picker offers, as the percent the live volume line shows for that step
 * ([mediaVolumePercent]): on the Pixel's 25 steps 12% to 52% for the start and 4% to 44% for the end, 4% apart.
 * Derived from [maxStep] at runtime, never hardcoded. Empty when the phone reports no volume range.
 */
fun fadeVolumeChoices(kind: FadeVolumeKind, maxStep: Int): List<Int> =
    if (maxStep <= 0) emptyList() else fadeVolumeStepRange(kind, maxStep).map { mediaVolumePercent(it, maxStep) }

/**
 * [percent] as the [kind] choice it reads as: the nearest phone step's percent, held inside the offered steps.
 * This is the migration of a value stored before the steps: 25% reads as 24% and 5% as 4% on 25 steps (the same
 * steps the fade already used for them), and a value exactly between two steps goes up. [percent] itself when
 * the phone reports no volume range.
 */
fun snapFadeVolumePercent(kind: FadeVolumeKind, percent: Int, maxStep: Int): Int {
    if (maxStep <= 0) return percent
    val step = fadeVolumeStep(percent, maxStep).coerceIn(fadeVolumeStepRange(kind, maxStep))
    return mediaVolumePercent(step, maxStep)
}

/** [current] moved one phone step in [direction] (+1 or -1), held inside the [kind] choices. */
fun stepFadeVolume(kind: FadeVolumeKind, current: Int, direction: Int, maxStep: Int): Int {
    if (maxStep <= 0) return current
    val step = (fadeVolumeStep(snapFadeVolumePercent(kind, current, maxStep), maxStep) + direction).coerceIn(fadeVolumeStepRange(kind, maxStep))
    return mediaVolumePercent(step, maxStep)
}

/** Whether a tap in [direction] would change [current] at all - the picker's button is disabled when not. */
fun canStepFadeVolume(kind: FadeVolumeKind, current: Int, direction: Int, maxStep: Int): Boolean =
    stepFadeVolume(kind, current, direction, maxStep) != snapFadeVolumePercent(kind, current, maxStep)
