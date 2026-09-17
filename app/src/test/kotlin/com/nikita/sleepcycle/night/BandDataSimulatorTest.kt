package com.nikita.sleepcycle.night

// File purpose: the simulator's event-to-segment pure functions - fell asleep, woke, back asleep, repeated
// presses, clear, and the open (most recent) segment extending to `now` on every call.

import com.nikita.sleepcycle.engine.SegmentKind
import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `fell asleep opens one LIGHT segment extended to now`() {
        val events = appendSimulatedSleepEvent(emptyList(), SimulatedSleepEventKind.FELL_ASLEEP, t0)

        val segments = buildSimulatedSegments(events, t1)

        assertEquals(1, segments.size)
        assertEquals(SegmentKind.LIGHT, segments[0].kind)
        assertEquals(t0, segments[0].start)
        assertEquals(t1, segments[0].end)
    }

    @Test
    fun `the open segment keeps extending to a later now on a later tick`() {
        val events = appendSimulatedSleepEvent(emptyList(), SimulatedSleepEventKind.FELL_ASLEEP, t0)

        val segments = buildSimulatedSegments(events, t3)

        assertEquals(t3, segments.single().end)
    }

    @Test
    fun `woke up closes the sleep segment and opens an AWAKE one extended to now`() {
        var events = appendSimulatedSleepEvent(emptyList(), SimulatedSleepEventKind.FELL_ASLEEP, t0)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.WOKE_UP, t1)

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
    fun `fell back asleep closes the awake segment and opens a new LIGHT one extended to now`() {
        var events = appendSimulatedSleepEvent(emptyList(), SimulatedSleepEventKind.FELL_ASLEEP, t0)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.WOKE_UP, t1)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.FELL_BACK_ASLEEP, t2)

        val segments = buildSimulatedSegments(events, t3)

        assertEquals(3, segments.size)
        assertEquals(SegmentKind.LIGHT, segments[2].kind)
        assertEquals(t2, segments[2].start)
        assertEquals(t3, segments[2].end)
    }

    @Test
    fun `a repeated fell-asleep press is a no-op`() {
        var events = appendSimulatedSleepEvent(emptyList(), SimulatedSleepEventKind.FELL_ASLEEP, t0)
        val before = events
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.FELL_ASLEEP, t1)

        assertEquals(before, events)
    }

    @Test
    fun `a repeated woke-up press is a no-op`() {
        var events = appendSimulatedSleepEvent(emptyList(), SimulatedSleepEventKind.FELL_ASLEEP, t0)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.WOKE_UP, t1)
        val before = events
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.WOKE_UP, t2)

        assertEquals(before, events)
    }

    @Test
    fun `fell-back-asleep before any woke-up event is a no-op`() {
        val events = appendSimulatedSleepEvent(emptyList(), SimulatedSleepEventKind.FELL_BACK_ASLEEP, t0)

        assertTrue(events.isEmpty())
    }

    @Test
    fun `woke-up before any fell-asleep event is a no-op`() {
        val events = appendSimulatedSleepEvent(emptyList(), SimulatedSleepEventKind.WOKE_UP, t0)

        assertTrue(events.isEmpty())
    }

    @Test
    fun `clear discards every simulated event`() {
        assertTrue(clearedSimulatedSleepEvents().isEmpty())
    }

    @Test
    fun `isSimulatedSleepEventAllowed agrees with appendSimulatedSleepEvent's own no-op behaviour`() {
        val events = appendSimulatedSleepEvent(emptyList(), SimulatedSleepEventKind.FELL_ASLEEP, t0)
        assertEquals(false, isSimulatedSleepEventAllowed(events, SimulatedSleepEventKind.FELL_ASLEEP))
        assertEquals(true, isSimulatedSleepEventAllowed(events, SimulatedSleepEventKind.WOKE_UP))
        assertEquals(false, isSimulatedSleepEventAllowed(events, SimulatedSleepEventKind.FELL_BACK_ASLEEP))
    }
}
