package com.nikita.sleepcycle.night

// File purpose: the owner's own settings from the Settings screen (owner spec, 2026-10-02) - the after-alarm
// timing, the bedtime audio switches and the sleep rating - with their defaults and the limits each stepper
// offers. Pure: persisted alongside the rest of AppSettings (AppSettings.kt), read by the night and the UI.

import java.time.LocalTime

private const val DEFAULT_NUDGE_MINUTES = 10
private const val DEFAULT_NAP_MINUTES = 20
private const val DEFAULT_FADE_START_PERCENT = 25
private val DEFAULT_RATING_ASK_AT: LocalTime = LocalTime.of(15, 0)

/**
 * After the alarm: how long after a ring ends the out-of-bed nudge rings, and the one nap length used by both
 * the Night screen's Nap button and the engine's naps between cycles. A night captures these once at Start
 * night (NightState.afterAlarm), the same way it captures its debug options.
 */
data class AfterAlarmSettings(
    val nudgeMinutes: Int = DEFAULT_NUDGE_MINUTES,
    val napMinutes: Int = DEFAULT_NAP_MINUTES,
)

/** Bedtime audio: the media volume fade from Start night (MediaFade.kt) and the pause on falling asleep (MediaPauseOnSleep.kt). */
data class BedtimeAudioSettings(
    val fadeEnabled: Boolean = true,
    /** Where the fade starts, as a percent of the media volume range - never louder than what was already playing. */
    val fadeStartPercent: Int = DEFAULT_FADE_START_PERCENT,
    val pauseWhenAsleep: Boolean = true,
)

/** Sleep rating: the morning report's "How did you sleep?" card, and the later "Still feel the same?" notification at [askAt]. */
data class SleepRatingSettings(
    val enabled: Boolean = true,
    val askAgainLater: Boolean = true,
    val askAt: LocalTime = DEFAULT_RATING_ASK_AT,
)

/** Each number the Settings screen changes with a minus/plus stepper: its allowed [range] and how far one tap moves it. */
enum class SettingStepper(val range: IntRange, val step: Int) {
    NUDGE_MINUTES(5..15, 1),
    NAP_MINUTES(10..30, 5),
    FADE_START_PERCENT(10..50, 5),
}

/** [current] moved one step in [direction] (+1 or -1), held inside the stepper's range. */
fun stepSetting(stepper: SettingStepper, current: Int, direction: Int): Int =
    clampSetting(stepper, current + direction * stepper.step)

/** Whether a tap in [direction] would change [current] at all - the stepper's button is disabled when not. */
fun canStepSetting(stepper: SettingStepper, current: Int, direction: Int): Boolean =
    stepSetting(stepper, current, direction) != current

/** [value] held inside the stepper's range, so a stored value from an older build or a bad write still reads back usable. */
fun clampSetting(stepper: SettingStepper, value: Int): Int = value.coerceIn(stepper.range)
