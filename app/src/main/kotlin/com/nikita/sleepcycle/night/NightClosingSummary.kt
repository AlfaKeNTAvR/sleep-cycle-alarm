package com.nikita.sleepcycle.night

// File purpose: the night_end companion the owner actually asked for - a summary with the numbers a reader
// needs (how long to fall asleep, each awakening's length, and above all how long he lay in bed after the
// final alarm), rather than raw stretches he has to process by hand. Pure over engine types so it is directly
// unit-testable. NOT wired in: night_end is logged inside NightController.kt, which this task's file boundary
// forbids editing. See this file's integration note below for the one call/log-append NightController.kt needs.
//
// INTEGRATION (for NightController.kt, not done here):
//   In `nightEndFields` (or right next to its one call site in endNightLocked/finishNightIfNeeded), after the
//   existing `appendNightLog(..., "night_end", ...)` call, add:
//
//     val config = resolveEngineConfig(state.debugOptions)
//     val closingSummary = buildNightClosingSummary(state.startedAt, state.lastSegments, now, config, state.wakeAlarmFiredAt)
//     appendNightLog(context, state.startedAt, NightLogEvent(now, "night_summary", encodeNightClosingSummaryFields(closingSummary)), state.debugOptions.isAnyEnabled)
//
//   `state` here is the same loaded/`applyAwakeConfirmation`-adjusted NightState already in scope at both
//   call sites (`endNightLocked`'s `state`, `finishNightIfNeeded`'s `loaded`); `now` is that function's own
//   `now`. One new log line per night end, right after night_end.

import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.engine.SleepSegment
import com.nikita.sleepcycle.engine.buildSleepStretches
import com.nikita.sleepcycle.engine.normalizeSegments
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The night's whole shape, distilled to the numbers a reader actually asked for (see this file's header).
 * [fellAsleepAfter] is null when the night ended before any sleep was ever detected (no stretches at all) -
 * a genuine "never fell asleep", not a zero. [inBedAfterWakeAlarm] is null when the wake alarm never fired
 * this night (the night was stopped early, or ended on the deadline/nap cap before it rang) - there is no
 * "after the alarm" to measure.
 */
data class NightClosingSummary(
    val fellAsleepAfter: Duration?,
    val awakenings: List<AwakeningEvent>,
    val inBedAfterWakeAlarm: Duration?
)

/**
 * Builds [NightClosingSummary] from the same inputs `nightEndFields` already has in scope: the night's start,
 * its full final segment list, the instant it ended, the EngineConfig it ran under, and the wake alarm's own
 * fired instant (or null if it never fired). Reuses [buildSleepStretches] - the same function the existing
 * `night_end` stretches/totalSleep fields are built from - so this summary can never disagree with them about
 * where one stretch ends and the next begins.
 */
fun buildNightClosingSummary(
    startedAt: Instant,
    segments: List<SleepSegment>,
    endedAt: Instant,
    config: EngineConfig,
    wakeAlarmFiredAt: Instant?
): NightClosingSummary {
    val stretches = buildSleepStretches(normalizeSegments(segments, endedAt, config))
    // Truncated to whole minutes at the source, not just when logged (encodeNightClosingSummaryFields already
    // rendered these in whole minutes via toMinutes(), but the raw Duration itself still carried the leftover
    // seconds - a caller reading NightClosingSummary directly, rather than through the encoded log fields,
    // would otherwise see e.g. 34 min 39 s where every documented and tested number is a clean 34 min).
    val fellAsleepAfter = stretches.firstOrNull()?.let { Duration.between(startedAt, it.onset).truncatedTo(ChronoUnit.MINUTES) }
    val awakenings = stretches.indices
        .filter { index -> stretches[index].followsAwakening }
        .map { index ->
            val awakeningStart = stretches[index - 1].end
            val awakeningEnd = stretches[index].onset
            AwakeningEvent(
                startedAt = awakeningStart,
                endedAt = awakeningEnd,
                duration = Duration.between(awakeningStart, awakeningEnd),
                afterWakeAlarm = wakeAlarmFiredAt != null && !awakeningStart.isBefore(wakeAlarmFiredAt)
            )
        }
    val inBedAfterWakeAlarm = wakeAlarmFiredAt?.let { Duration.between(it, endedAt).truncatedTo(ChronoUnit.MINUTES) }
    return NightClosingSummary(fellAsleepAfter, awakenings, inBedAfterWakeAlarm)
}

/**
 * Log fields for the `night_summary` event. Durations are logged in whole minutes, matching
 * [AwakeningEvent]'s own field encoding (`encodeAwakeningEndedFields`) elsewhere in the night log.
 * `awakeningDurationsMinutes` is a comma-joined list (e.g. "4,11,4,7") rather than nested JSON: this event has
 * no other array-shaped field, so a flat list needs no parser beyond `split(",")`.
 */
fun encodeNightClosingSummaryFields(summary: NightClosingSummary): Map<String, String> = mapOf(
    "fellAsleepAfterMinutes" to (summary.fellAsleepAfter?.toMinutes()?.toString() ?: ""),
    "awakeningCount" to summary.awakenings.size.toString(),
    "awakeningDurationsMinutes" to summary.awakenings.joinToString(",") { it.duration.toMinutes().toString() },
    "awakeningsAfterWakeAlarmCount" to summary.awakenings.count { it.afterWakeAlarm }.toString(),
    "inBedAfterWakeAlarmMinutes" to (summary.inBedAfterWakeAlarm?.toMinutes()?.toString() ?: "")
)
