package com.nikita.sleepcycle.night

// File purpose: pure functions turning the Debug screen's "Asleep" toggle into a sleep segment timeline, so
// the rest of the app (the engine, the night log) can treat a simulated night exactly like a real one.
// T9 SUPERSEDES the original three-button design (FELL_ASLEEP/WOKE_UP/FELL_BACK_ASLEEP): one toggle, two
// states - ASLEEP opens a LIGHT segment, AWAKE closes it and opens an AWAKE one. The most recent segment has
// no end of its own yet - [buildSimulatedSegments] extends it to `now` on every call, so it grows tick by tick
// exactly like a real open-ended sleep mark would.

import com.nikita.sleepcycle.engine.SegmentKind
import com.nikita.sleepcycle.engine.SleepSegment
import java.time.Instant

/** T9: the Debug screen's "Asleep" toggle has exactly two states - no more three-button FELL_ASLEEP/WOKE_UP/FELL_BACK_ASLEEP distinction, since "asleep again after waking" behaves identically to "asleep for the first time" from the engine's point of view. */
enum class SimulatedSleepEventKind { ASLEEP, AWAKE }

/** One simulated toggle flip, in the order it happened. */
data class SimulatedSleepEvent(val kind: SimulatedSleepEventKind, val at: Instant)

/** The segment kind that starts at a given event: sleep for ASLEEP, awake for AWAKE. */
private fun segmentKindStartedBy(kind: SimulatedSleepEventKind): SegmentKind = when (kind) {
    SimulatedSleepEventKind.ASLEEP -> SegmentKind.LIGHT
    SimulatedSleepEventKind.AWAKE -> SegmentKind.AWAKE
}

/**
 * T9: whether flipping the toggle to [kind] next is a meaningful transition given [events] so far - allowed
 * exactly when [kind] differs from the CURRENT state, where the current state is the last event's own kind, or
 * AWAKE when there is no event yet (a night starts awake). So ASLEEP is allowed first (there is nothing to be
 * awake FROM yet, but the state is already AWAKE by this null-counts-as-AWAKE convention), and AWAKE is not
 * allowed first (there is nothing to wake from). Both the Debug screen's toggle enablement and
 * [appendSimulatedSleepEvent] use this same predicate, so the two can never disagree about what the toggle
 * does right now.
 */
fun isSimulatedSleepEventAllowed(events: List<SimulatedSleepEvent>, kind: SimulatedSleepEventKind): Boolean {
    val current = events.lastOrNull()?.kind ?: SimulatedSleepEventKind.AWAKE
    return kind != current
}

/**
 * T10: the sleep toggle and the "Clear simulated sleep" button both require simulated band data -
 * [readBandDataForTick] only reads the simulated timeline when it is on, so pressing either while it is off
 * changes nothing (the bug the owner actually hit). Pure so the Debug screen's enablement and
 * [com.nikita.sleepcycle.ui.DebugScreenController]'s own second guard can never disagree.
 */
fun isSimulatedSleepControlAllowed(simulatedBandData: Boolean): Boolean = simulatedBandData

/**
 * Appends one simulated event at [at], or returns [events] unchanged when [kind] is not a meaningful
 * transition right now (see [isSimulatedSleepEventAllowed]) - a repeated press of the same button is a no-op
 * rather than a corrupt timeline.
 */
fun appendSimulatedSleepEvent(events: List<SimulatedSleepEvent>, kind: SimulatedSleepEventKind, at: Instant): List<SimulatedSleepEvent> =
    if (isSimulatedSleepEventAllowed(events, kind)) events + SimulatedSleepEvent(kind, at) else events

/** Discards every simulated event, for the "Clear simulated sleep" button. */
fun clearedSimulatedSleepEvents(): List<SimulatedSleepEvent> = emptyList()

/**
 * Builds the simulated sleep segment timeline from [events], in order: each event starts a segment of its
 * own kind (see [segmentKindStartedBy]) that runs until the next event, or - for the most recent event, which
 * has no next one yet - until [now]. A clock going backwards between calls (should not happen; guarded like
 * the engine guards the same case for real band data) drops that trailing segment rather than emitting one
 * with a negative length.
 */
fun buildSimulatedSegments(events: List<SimulatedSleepEvent>, now: Instant): List<SleepSegment> {
    if (events.isEmpty()) return emptyList()
    val sorted = events.sortedBy { it.at }
    return sorted.indices.mapNotNull { index ->
        val event = sorted[index]
        val end = sorted.getOrNull(index + 1)?.at ?: now
        if (!end.isAfter(event.at)) null else SleepSegment(start = event.at, end = end, kind = segmentKindStartedBy(event.kind))
    }
}
