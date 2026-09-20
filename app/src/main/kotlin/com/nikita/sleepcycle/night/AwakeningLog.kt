package com.nikita.sleepcycle.night

// File purpose: turns the per-tick sleep timeline into first-class awakening events for the night log,
// instead of leaving a reader to recompute them by hand from the gaps between night_end's `stretches` (the
// field question a real log - night-20260920-0104.jsonl - could not answer directly: how many times did he
// wake, how long was each, and was it before or after the alarm). Pure over engine types, called once per
// tick from NightTickLogging.logDataAndPlan with that tick's previous and current segment lists; no Android
// dependency, so it is unit-testable without Robolectric (this repo has none).

import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.engine.SleepSegment
import com.nikita.sleepcycle.engine.SleepStretch
import com.nikita.sleepcycle.engine.buildSleepStretches
import com.nikita.sleepcycle.engine.normalizeSegments
import java.time.Duration
import java.time.Instant

/**
 * One awakening: a gap between two sleep stretches. [afterWakeAlarm] is the single fact the owner could not
 * get out of the old log without cross-referencing timestamps by hand - whether this awakening began at or
 * after the night's wake alarm had already fired ([wakeAlarmFiredAt] null means it had not fired at all yet).
 */
data class AwakeningEvent(
    val startedAt: Instant,
    val endedAt: Instant,
    val duration: Duration,
    val afterWakeAlarm: Boolean
)

/**
 * What this tick's new band data changed about awakenings, relative to the previous tick: awakenings that
 * newly CLOSED (sleep resumed) during this tick, and, separately, the instant an awakening that is still open
 * right now most recently started - non-null only on the tick it first opens, so a caller logs "started" once
 * per awakening rather than on every tick it remains open.
 */
data class AwakeningTickDelta(
    val closed: List<AwakeningEvent>,
    val justStartedAt: Instant?
)

/**
 * Compares the sleep timeline built from [previousSegments] against the one built from [currentSegments],
 * both normalized at the same [now] so the comparison is apples to apples (an interval "still running" is
 * clipped the same way on both sides). [config] is the caller's already-resolved EngineConfig (fast debug
 * night or real), so a blip below [EngineConfig.minAwakening] never surfaces as either a started or a closed
 * awakening here, same as it never splits a stretch in [normalizeSegments]/[buildSleepStretches].
 *
 * A closed awakening is matched across ticks by the ONSET of the stretch it closes: a stretch's onset never
 * moves once established (only its end extends as more data arrives), so an onset not present in the
 * previous tick's stretches means the awakening before it just closed for the first time - including the
 * case where a late-arriving AWAKE mark retroactively splits an already-logged stretch in two; that split is
 * reported the tick it is LEARNED about, the same "firstSeenAt" semantics the segment log already uses.
 */
fun detectAwakeningsThisTick(
    previousSegments: List<SleepSegment>,
    currentSegments: List<SleepSegment>,
    now: Instant,
    config: EngineConfig,
    wakeAlarmFiredAt: Instant?
): AwakeningTickDelta {
    val previousTimeline = normalizeSegments(previousSegments, now, config)
    val currentTimeline = normalizeSegments(currentSegments, now, config)
    val previousStretches = buildSleepStretches(previousTimeline)
    val currentStretches = buildSleepStretches(currentTimeline)

    val closed = newlyClosedAwakenings(previousStretches, currentStretches, wakeAlarmFiredAt)

    val previousOpenStart = previousTimeline.lastOrNull()?.takeIf { !it.asleep }?.start
    val currentOpenStart = currentTimeline.lastOrNull()?.takeIf { !it.asleep }?.start
    val justStartedAt = currentOpenStart?.takeIf { it != previousOpenStart }

    return AwakeningTickDelta(closed, justStartedAt)
}

private fun newlyClosedAwakenings(
    previousStretches: List<SleepStretch>,
    currentStretches: List<SleepStretch>,
    wakeAlarmFiredAt: Instant?
): List<AwakeningEvent> {
    val previousOnsets = previousStretches.map { it.onset }.toSet()
    return currentStretches.indices
        .filter { index -> currentStretches[index].followsAwakening && currentStretches[index].onset !in previousOnsets }
        .map { index ->
            val startedAt = currentStretches[index - 1].end
            val endedAt = currentStretches[index].onset
            AwakeningEvent(
                startedAt = startedAt,
                endedAt = endedAt,
                duration = Duration.between(startedAt, endedAt),
                afterWakeAlarm = wakeAlarmFiredAt != null && !startedAt.isBefore(wakeAlarmFiredAt)
            )
        }
}

/** Log fields for a closed `awakening_ended` event - everything B asked for: when it started, when it ended, its duration, and whether the wake alarm had already fired when it began. */
fun encodeAwakeningEndedFields(event: AwakeningEvent): Map<String, String> = mapOf(
    "startedAt" to event.startedAt.toString(),
    "endedAt" to event.endedAt.toString(),
    "durationMinutes" to event.duration.toMinutes().toString(),
    "afterWakeAlarm" to event.afterWakeAlarm.toString()
)

/** Log fields for an `awakening_started` event - logged once, so a night that has not ended yet still shows an awakening in progress rather than nothing at all. */
fun encodeAwakeningStartedFields(startedAt: Instant, wakeAlarmFiredAt: Instant?): Map<String, String> = mapOf(
    "startedAt" to startedAt.toString(),
    "afterWakeAlarm" to (wakeAlarmFiredAt != null && !startedAt.isBefore(wakeAlarmFiredAt)).toString()
)
