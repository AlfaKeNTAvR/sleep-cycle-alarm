package com.nikita.sleepcycle.night

// File purpose: the simulator's event-to-segment pure functions - the T9 "Asleep" toggle (ASLEEP/AWAKE),
// repeated presses, clear, T10's control gating, and the open (most recent) segment extending to `now` on
// every call.

import com.nikita.sleepcycle.engine.SegmentKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class BandDataSimulatorTest {
    private val t0 = Instant.parse("2026-09-17T23:00:00Z")
    private val t1 = Instant.parse("2026-09-17T23:10:00Z")
    private val t2 = Instant.parse("2026-09-17T23:20:00Z")
    private val t3 = Instant.parse("2026-09-17T23:30:00Z")

    @Test
    fun `an empty event list builds no segments`() {
        assertEquals(emptyList<com.nikita.sleepcycle.engine.SleepSegment>(), buildSimulatedSegments(emptyList(), t0))
    }

    @Test
    fun `asleep opens one LIGHT segment extended to now`() {
        val events = appendSimulatedSleepEvent(emptyList(), SimulatedSleepEventKind.ASLEEP, t0)

        val segments = buildSimulatedSegments(events, t1)

        assertEquals(1, segments.size)
        assertEquals(SegmentKind.LIGHT, segments[0].kind)
        assertEquals(t0, segments[0].start)
        assertEquals(t1, segments[0].end)
    }

    @Test
    fun `the open segment keeps extending to a later now on a later tick`() {
        val events = appendSimulatedSleepEvent(emptyList(), SimulatedSleepEventKind.ASLEEP, t0)

        val segments = buildSimulatedSegments(events, t3)

        assertEquals(t3, segments.single().end)
    }

    @Test
    fun `awake closes the sleep segment and opens an AWAKE one extended to now`() {
        var events = appendSimulatedSleepEvent(emptyList(), SimulatedSleepEventKind.ASLEEP, t0)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.AWAKE, t1)

        val segments = buildSimulatedSegments(events, t2)

        assertEquals(2, segments.size)
        assertEquals(SegmentKind.LIGHT, segments[0].kind)
        assertEquals(t0, segments[0].start)
        assertEquals(t1, segments[0].end)
        assertEquals(SegmentKind.AWAKE, segments[1].kind)
        assertEquals(t1, segments[1].start)
        assertEquals(t2, segments[1].end)
    }

    @Test
    fun `asleep again after waking closes the awake segment and opens a new LIGHT one extended to now`() {
        var events = appendSimulatedSleepEvent(emptyList(), SimulatedSleepEventKind.ASLEEP, t0)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.AWAKE, t1)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.ASLEEP, t2)

        val segments = buildSimulatedSegments(events, t3)

        assertEquals(3, segments.size)
        assertEquals(SegmentKind.LIGHT, segments[2].kind)
        assertEquals(t2, segments[2].start)
        assertEquals(t3, segments[2].end)
    }

    @Test
    fun `a repeated asleep press is a no-op`() {
        var events = appendSimulatedSleepEvent(emptyList(), SimulatedSleepEventKind.ASLEEP, t0)
        val before = events
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.ASLEEP, t1)

        assertEquals(before, events)
    }

    @Test
    fun `a repeated awake press is a no-op`() {
        var events = appendSimulatedSleepEvent(emptyList(), SimulatedSleepEventKind.ASLEEP, t0)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.AWAKE, t1)
        val before = events
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.AWAKE, t2)

        assertEquals(before, events)
    }

    @Test
    fun `awake before any asleep event (the very first press) is a no-op - null counts as AWAKE`() {
        val events = appendSimulatedSleepEvent(emptyList(), SimulatedSleepEventKind.AWAKE, t0)

        assertTrue(events.isEmpty())
    }

    @Test
    fun `asleep is allowed as the very first press - null counts as AWAKE`() {
        val events = appendSimulatedSleepEvent(emptyList(), SimulatedSleepEventKind.ASLEEP, t0)

        assertEquals(1, events.size)
    }

    @Test
    fun `clear discards every simulated event`() {
        assertTrue(clearedSimulatedSleepEvents().isEmpty())
    }

    // ---- FIX3: appending against the STORED list, not a stale StateFlow snapshot ------------------------

    @Test
    fun `appending against a stale snapshot can silently erase an already-stored event - the regression FIX3's stored-not-snapshot merge avoids`() {
        // Models two quick taps of the Debug screen's Asleep toggle: a concurrent write commits an ASLEEP
        // event that `staleSnapshot` (a StateFlow value read before that write landed) never saw.
        val staleSnapshot = emptyList<SimulatedSleepEvent>()
        val stored = appendSimulatedSleepEvent(staleSnapshot, SimulatedSleepEventKind.ASLEEP, t0)

        // The bug (what DebugScreenController.recordEvent used to do): appending AWAKE against the STALE
        // snapshot treats it as the very first press (null counts as AWAKE, see isSimulatedSleepEventAllowed),
        // a no-op that returns the snapshot itself - writing that back would erase the already-committed
        // ASLEEP event permanently.
        val buggyMerge = appendSimulatedSleepEvent(staleSnapshot, SimulatedSleepEventKind.AWAKE, t1)
        assertTrue(buggyMerge.isEmpty())

        // The fix (updateSimulatedSleepEvents, DebugSettingsStore.kt): appending against the STORED list -
        // read inside DataStore's own edit{} at write time, never a stale snapshot - keeps the ASLEEP event
        // and correctly closes it with the new AWAKE one.
        val fixedMerge = appendSimulatedSleepEvent(stored, SimulatedSleepEventKind.AWAKE, t1)
        assertEquals(listOf(SimulatedSleepEventKind.ASLEEP, SimulatedSleepEventKind.AWAKE), fixedMerge.map { it.kind })
    }

    @Test
    fun `isSimulatedSleepEventAllowed agrees with appendSimulatedSleepEvent's own no-op behaviour`() {
        val events = appendSimulatedSleepEvent(emptyList(), SimulatedSleepEventKind.ASLEEP, t0)
        assertEquals(false, isSimulatedSleepEventAllowed(events, SimulatedSleepEventKind.ASLEEP))
        assertEquals(true, isSimulatedSleepEventAllowed(events, SimulatedSleepEventKind.AWAKE))
    }

    @Test
    fun `isSimulatedSleepEventAllowed treats no events yet as the AWAKE state`() {
        assertEquals(true, isSimulatedSleepEventAllowed(emptyList(), SimulatedSleepEventKind.ASLEEP))
        assertEquals(false, isSimulatedSleepEventAllowed(emptyList(), SimulatedSleepEventKind.AWAKE))
    }

    // ---- T10: the sleep toggle and Clear require simulated band data -----------------------------------

    @Test
    fun `the sleep control is allowed only while simulated band data is on`() {
        assertTrue(isSimulatedSleepControlAllowed(simulatedBandData = true))
        assertFalse(isSimulatedSleepControlAllowed(simulatedBandData = false))
    }
}
