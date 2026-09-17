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
 * The sleep-length label to show for [duration]: the usual decimal-hour label ("4.5 h"), or - in a fast
 * debug night, where a "cycle" can be a few minutes long - the honest minutes-and-hours form ("9 min") from
 * [formatDuration], since a decimal-hour label would round a real number of minutes down to "0 h" and
 * mislead the person reading it.
 */
fun formatSleepLengthLabel(duration: Duration, fastNight: Boolean): String =
    if (fastNight) formatDuration(duration) else formatHoursLabel(duration)
