package com.nikita.sleepcycle.night

// File purpose: owner request, 2026-10-03 - switching simulated band data on jumps the clock to the next
// "Simulated start" time. Covers [nextOccurrenceOf], the date math that picks today's or tomorrow's instant.

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class SimulatedStartTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun `a start time still ahead today lands today`() {
        // 15:00 in Berlin (CEST, UTC+2) on 3 October; 23:00 is still 8 h away.
        val realNow = Instant.parse("2026-10-03T13:00:00Z")
        assertEquals(Instant.parse("2026-10-03T21:00:00Z"), nextOccurrenceOf(LocalTime.of(23, 0), berlin, realNow))
    }

    @Test
    fun `a start time already passed today lands tomorrow`() {
        // 23:30 in Berlin; 23:00 went by half an hour ago, so the next one is 4 October.
        val realNow = Instant.parse("2026-10-03T21:30:00Z")
        assertEquals(Instant.parse("2026-10-04T21:00:00Z"), nextOccurrenceOf(LocalTime.of(23, 0), berlin, realNow))
    }

    @Test
    fun `an early morning start time picked late at night lands the next morning`() {
        // 23:30 in Berlin, start 02:00: the next 02:00 is after midnight, on 4 October.
        val realNow = Instant.parse("2026-10-03T21:30:00Z")
        assertEquals(Instant.parse("2026-10-04T00:00:00Z"), nextOccurrenceOf(LocalTime.of(2, 0), berlin, realNow))
    }

    @Test
    fun `a start time exactly now lands now, not a day later`() {
        val realNow = Instant.parse("2026-10-03T21:00:00Z")
        assertEquals(realNow, nextOccurrenceOf(LocalTime.of(23, 0), berlin, realNow))
    }

    @Test
    fun `a start time one millisecond in the past lands tomorrow`() {
        val realNow = Instant.parse("2026-10-03T21:00:00.001Z")
        assertEquals(Instant.parse("2026-10-04T21:00:00Z"), nextOccurrenceOf(LocalTime.of(23, 0), berlin, realNow))
    }

    @Test
    fun `a start time skipped by the spring DST jump moves to just after the gap`() {
        // Berlin skips 02:00-03:00 on 29 March 2026; 02:30 does not exist that night and becomes 03:30 CEST.
        val realNow = Instant.parse("2026-03-28T22:00:00Z")
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), nextOccurrenceOf(LocalTime.of(2, 30), berlin, realNow))
    }

    @Test
    fun `a start time on the autumn DST day uses that day's own offset`() {
        // Berlin falls back to CET (UTC+1) on 25 October 2026; 23:00 that evening is 22:00 UTC, not 21:00.
        val realNow = Instant.parse("2026-10-25T12:00:00Z")
        assertEquals(Instant.parse("2026-10-25T22:00:00Z"), nextOccurrenceOf(LocalTime.of(23, 0), berlin, realNow))
    }
}
