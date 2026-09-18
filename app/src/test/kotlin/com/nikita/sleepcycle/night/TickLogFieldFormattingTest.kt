package com.nikita.sleepcycle.night

// File purpose: the first tick of a night is immediate, not alarm-delivered, so scheduledFor/receivedAt are
// genuinely absent - formatTickTimeField must read that as "immediate", not an empty string that looks like
// a bug when scanning a night log (night-20260917-2143.jsonl, night-20260917-2144.jsonl).

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class TickLogFieldFormattingTest {
    @Test
    fun `a null tick time formats as immediate, not an empty string`() {
        assertEquals("immediate", formatTickTimeField(null))
    }

    @Test
    fun `a real tick time formats as its own instant string`() {
        val at = Instant.parse("2026-09-17T01:44:00Z")
        assertEquals(at.toString(), formatTickTimeField(at))
    }
}
