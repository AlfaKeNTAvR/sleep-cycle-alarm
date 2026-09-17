package com.nikita.sleepcycle.night

// File purpose: pure functions turning the Debug screen's button presses into a sleep segment timeline, so
// the rest of the app (the engine, the night log) can treat a simulated night exactly like a real one.
// Mirrors the button set: "I fell asleep now" opens a LIGHT segment, "I woke up now" closes it and opens an
// AWAKE one, "Fell back asleep now" closes that and opens LIGHT again. The most recent segment has no end of
// its own yet - [buildSimulatedSegments] extends it to `now` on every call, so it grows tick by tick exactly
// like a real open-ended sleep mark would.

import com.nikita.sleepcycle.engine.SegmentKind
import com.nikita.sleepcycle.engine.SleepSegment
import java.time.Instant

/** Which of the three simulator buttons was pressed. */
enum class SimulatedSleepEventKind { FELL_ASLEEP, WOKE_UP, FELL_BACK_ASLEEP }

/** One simulated button press, in the order it happened. */
data class SimulatedSleepEvent(val kind: SimulatedSleepEventKind, val at: Instant)

/** The segment kind that starts at a given event: sleep for both "fell asleep" events, awake for "woke up". */
private fun segmentKindStartedBy(kind: SimulatedSleepEventKind): SegmentKind = when (kind) {
    SimulatedSleepEventKind.WOKE_UP -> SegmentKind.AWAKE
    SimulatedSleepEventKind.FELL_ASLEEP, SimulatedSleepEventKind.FELL_BACK_ASLEEP -> SegmentKind.LIGHT
}

/**
 * Whether pressing [kind] next is a meaningful transition given [events] so far: "fell asleep" only as the
 * very first event, "woke up" only while a sleep segment is open, "fell back asleep" only while the awake
 * segment is open. A press that would not be a meaningful transition (e.g. a repeated press of the same
 * button) is not allowed - both the Debug screen's button enablement and [appendSimulatedSleepEvent] use
 * this same predicate, so the two can never disagree about which button does something right now.
 */
fun isSimulatedSleepEventAllowed(events: List<SimulatedSleepEvent>, kind: SimulatedSleepEventKind): Boolean {
    val last = events.lastOrNull()
    return when (kind) {
        SimulatedSleepEventKind.FELL_ASLEEP -> last == null
        SimulatedSleepEventKind.WOKE_UP -> last != null && last.kind != SimulatedSleepEventKind.WOKE_UP
        SimulatedSleepEventKind.FELL_BACK_ASLEEP -> last?.kind == SimulatedSleepEventKind.WOKE_UP
    }
}

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
