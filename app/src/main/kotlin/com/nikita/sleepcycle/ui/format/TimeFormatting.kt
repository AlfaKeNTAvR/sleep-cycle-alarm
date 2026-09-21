package com.nikita.sleepcycle.ui.format

// File purpose: pure time/duration formatting shared by every screen, plus the "next occurrence of a clock time"
// rule the deadline picker needs. Nothing here touches Android or the engine, so it is plain JVM-testable.

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MINUTES_PER_HOUR = 60
private val CLOCK_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

/** Formats an instant as a 24-hour clock time, e.g. "07:55". */
fun formatClockTime(instant: Instant, zone: ZoneId): String =
    CLOCK_TIME_FORMAT.withZone(zone).format(instant)

/** Formats a duration as "7 h 30", "1 h 59", or, under an hour, "20 min". Negative durations format as "0 min". */
fun formatDuration(duration: Duration): String {
    val totalMinutes = maxOf(duration.toMinutes(), 0L)
    val hours = totalMinutes / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    return if (hours <= 0) {
        "$minutes min"
    } else {
        "$hours h " + minutes.toString().padStart(2, '0')
    }
}

/**
 * N1: how long from [now] until [target], as a duration label ("20 min", "5 h 38") for the one line the night
 * screen shows under its hero time. Reuses [formatDuration]'s own wording rather than inventing a second
 * duration format, so a countdown and the morning report's totals read the same. A [target] at or before [now]
 * formats as "0 min" ([formatDuration] clamps negatives): an alarm whose instant has passed but which has not
 * fired yet is a real state for the seconds between the two, and "0 min" is the honest reading of it.
 */
fun formatTimeUntil(target: Instant, now: Instant): String = formatDuration(Duration.between(now, target))

/** Formats a cycle count to one decimal, e.g. "1.3", for the morning report's parenthetical. */
fun formatCycles(cycles: Double): String = String.format(Locale.US, "%.1f", cycles)

/**
 * The next moment [clockTime] occurs at or after [now], in [zone]. Strictly after `now`: if `now` already sits
 * exactly on [clockTime], the next occurrence is a full day later, not the current instant. Handles clock times
 * that roll past midnight the same way as any other time of day.
 */
fun nextOccurrenceOfDeadline(clockTime: LocalTime, now: Instant, zone: ZoneId): Instant {
    val nowZoned = now.atZone(zone)
    val todayAtClockTime = nowZoned.toLocalDate().atTime(clockTime).atZone(zone)
    val candidate = if (todayAtClockTime.toInstant().isAfter(now)) todayAtClockTime else todayAtClockTime.plusDays(1)
    return candidate.toInstant()
}
