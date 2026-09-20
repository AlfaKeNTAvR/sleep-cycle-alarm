package com.nikita.sleepcycle.ui.format

// File purpose: the "Sleep up to" picker's decimal-hour labels ("4.5 h", "7.5 h"), distinct from formatDuration's
// "7 h 30" clock-style duration text used for actual elapsed sleep.

import java.time.Duration

/** Formats a duration as a decimal-hour label, e.g. "4.5 h", "6 h", "7.5 h". Assumes a whole or half hour. */
fun formatHoursLabel(duration: Duration): String {
    val totalMinutes = duration.toMinutes()
    val wholeHours = totalMinutes / 60
    val remainderMinutes = totalMinutes % 60
    val fraction = if (remainderMinutes == 0L) "" else ".5"
    return "$wholeHours$fraction h"
}

/**
 * The sleep-length label to show for [duration]: the usual decimal-hour label ("4.5 h").
 *
 * T7: used to switch to the honest minutes-and-hours form ("9 min", [formatDuration]) for a fast debug night,
 * where a "cycle" used to be shrunk to a few real minutes and a decimal-hour label would have rounded that
 * down to a misleading "0 h". A fast debug night no longer shrinks EngineConfig's own durations (see
 * EngineConfigResolution.kt) - it warps the clock instead - so a cycle is always a real number of hours again,
 * and this always uses the hours label now.
 */
fun formatSleepLengthLabel(duration: Duration): String = formatHoursLabel(duration)
