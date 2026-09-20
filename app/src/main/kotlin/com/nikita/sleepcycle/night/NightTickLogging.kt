package com.nikita.sleepcycle.night

// File purpose: everything runNightTick logs about the data it read and the plan it computed - split out of
// NightOrchestrator.kt to keep that file focused on the tick's own control flow. Answers the field questions
// from the reviews: does the band publish sleep marks mid-night (data/segments), what did the plan actually
// decide and why (plan), and - the gap a real log (night-20260920-0104.jsonl) exposed - did an awakening
// happen at all, as a thing that happened rather than a gap a reader has to compute from night_end's
// stretches (awakening_started/awakening_ended, see AwakeningLog.kt). D2: the band alarm command/table
// logging that used to live here is gone along with the rest of the band alarm machinery - the band is a
// sensor only now.

import android.content.Context
import com.nikita.sleepcycle.engine.AlarmPlan
import com.nikita.sleepcycle.engine.SleepSegment
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Formats a tick's `scheduledFor`/`receivedAt` log field. Both are null exactly for a tick not triggered by
 * the exact tick alarm (the night's very first tick, or an immediate UI-requested one, e.g. opening the Night
 * screen) - a genuine absence, not a bug, so it reads as "immediate" rather than an empty string that looks
 * like missing data when scanning a night log. `internal`, not `private`, so it is JVM-testable directly.
 */
internal fun formatTickTimeField(instant: Instant?): String = instant?.toString() ?: "immediate"

/** The "data" event only lists segments when the list changed since last tick (C2); each entry says whether it is a segment newly seen this tick or an existing one whose end merely extended, per [encodeSegmentsDeltaForLog] - never the whole list again once a segment has already been logged unchanged. `source` says whether the segments came from the simulator or the band (debug mode). The "plan" event carries every field a field question needs, per the reason sentence (C3). Also detects and logs any awakening this tick's new data started or closed (see AwakeningLog.kt) - the fact the old log left the reader to reconstruct by hand from night_end's stretches. */
fun logDataAndPlan(context: Context, state: NightState, outcome: SyncOutcome, plan: AlarmPlan, now: Instant) {
    val debugNight = state.debugOptions.isAnyEnabled
    val sleepState = buildNightEngineView(state.copy(lastSegments = outcome.segments), now).sleepState
    val segmentsChanged = outcome.segments != state.lastSegments
    val dataFields = mutableMapOf(
        "segmentCount" to outcome.segments.size.toString(),
        "sleepState" to sleepState.name,
        "newestSampleAt" to (outcome.newestSampleAt?.toString() ?: ""),
        "exportFileModifiedAt" to (outcome.exportFileModifiedAt?.toString() ?: ""),
        "segmentsChanged" to segmentsChanged.toString(),
        "source" to outcome.source.name.lowercase()
    )
    if (segmentsChanged) {
        dataFields["segments"] = encodeSegmentsDeltaForLog(outcome.segments, state.lastSegments)
    }
    appendNightLog(context, state.startedAt, NightLogEvent(now, "data", dataFields), debugNight)

    logAwakeningChanges(context, state, outcome, now, debugNight)

    appendNightLog(
        context, state.startedAt,
        NightLogEvent(
            now, "plan",
            mapOf(
                "mode" to plan.mode.name,
                "wakeAt" to (plan.wakeAt?.toString() ?: ""),
                "cycles" to plan.cycles.toString(),
                // The two numbers rule 2's night total is decided from: how much sleep was already had, and
                // how many whole cycles that left owed BEFORE any deadline cap. Fields, not just prose in
                // `reason`, because together with `cycles` they are what says whether the total or the
                // deadline bound this plan.
                "sleptSoFarMinutes" to plan.sleptSoFar.toMinutes().toString(),
                "owedCycles" to plan.owedCycles.toString(),
                "referenceOnset" to (plan.referenceOnset?.toString() ?: ""),
                "onsetIsProjected" to plan.onsetIsProjected.toString(),
                // F11: the two D5 inputs this plan was computed with (see NightOrchestrator.runNightTickLocked)
                // - F2 showed diagnosing a morning that ended early is guesswork without them.
                "wakeAlarmFiredAt" to (state.wakeAlarmFiredAt?.toString() ?: ""),
                "napAlarmsUsed" to state.napAlarmsUsed.toString(),
                "reason" to plan.reason
            )
        ),
        debugNight
    )
}

/**
 * D: the old encoding dumped the WHOLE current segment list on every tick the list changed - most of the
 * file's bytes, almost none of its value, since a segment already logged unchanged says nothing new. This
 * logs only what changed: a segment not seen before (by start+kind identity, "new"), or a previously-seen one
 * whose end grew ("extended", carrying its previous end so a reader can see how far it grew this tick) - and
 * drops a segment that is byte-for-byte identical to what was already logged, entirely. Matching by
 * start+kind rather than full equality is what lets a still-growing segment (the same LIGHT interval reported
 * with a later end tick after tick - exactly the waste night-20260920-0104.jsonl showed) be recognised as the
 * SAME segment extending, instead of a new unrelated entry each time. `internal`, not `private`, so it is
 * JVM-testable directly (same reasoning as [formatTickTimeField]).
 */
internal fun encodeSegmentsDeltaForLog(segments: List<SleepSegment>, previousSegments: List<SleepSegment>): String {
    val previousByIdentity = previousSegments.associateBy { it.start to it.kind }
    val array = JSONArray()
    segments.forEach { segment ->
        val previous = previousByIdentity[segment.start to segment.kind]
        val entry = when {
            previous == null -> segmentLogEntry(segment).apply { put("status", "new") }
            previous.end != segment.end -> segmentLogEntry(segment).apply {
                put("status", "extended")
                put("previousEnd", previous.end.toString())
            }
            else -> null // Unchanged since it was last logged - nothing new to say, so it is dropped rather than repeated.
        }
        if (entry != null) array.put(entry)
    }
    return array.toString()
}

private fun segmentLogEntry(segment: SleepSegment): JSONObject = JSONObject().apply {
    put("start", segment.start.toString())
    put("end", segment.end.toString())
    put("kind", segment.kind.name)
}

/**
 * B: logs this tick's new awakening boundaries, if any - an awakening that closed (sleep resumed) as
 * "awakening_ended" carrying its full span and duration, and, separately, an awakening that just opened as
 * "awakening_started" (logged once, the tick it first opens - see [AwakeningTickDelta.justStartedAt]) so a
 * night still in progress shows an awakening that has not closed yet instead of nothing at all. Reuses
 * [state.wakeAlarmFiredAt] (already merged in by loadNightState before this tick started) as the one fact
 * that answers "was this before or after the alarm" without any further cross-referencing.
 */
private fun logAwakeningChanges(context: Context, state: NightState, outcome: SyncOutcome, now: Instant, debugNight: Boolean) {
    val config = resolveEngineConfig(state.debugOptions)
    val delta = detectAwakeningsThisTick(state.lastSegments, outcome.segments, now, config, state.wakeAlarmFiredAt)
    delta.closed.forEach { awakening ->
        appendNightLog(context, state.startedAt, NightLogEvent(now, "awakening_ended", encodeAwakeningEndedFields(awakening)), debugNight)
    }
    delta.justStartedAt?.let { startedAt ->
        appendNightLog(context, state.startedAt, NightLogEvent(now, "awakening_started", encodeAwakeningStartedFields(startedAt, state.wakeAlarmFiredAt)), debugNight)
    }
}
