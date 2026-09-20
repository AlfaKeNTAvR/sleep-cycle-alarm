package com.nikita.sleepcycle.night

// File purpose: unit tests for NightStartTimezone.kt - the fact night-20260920-0104.jsonl was missing
// entirely, forcing the offset to be inferred by comparing the log's first line against the filename's local
// time. The real night_start was at 2026-09-20T05:04:20.013670Z; the owner's inferred offset was UTC-4.

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

class NightStartTimezoneTest {
    @Test
    fun `records the zone id and the UTC-4 offset the owner had to infer by hand`() {
        val fields = nightStartTimezoneFields(Instant.parse("2026-09-20T05:04:20.013670Z"), ZoneId.of("America/New_York"))

        assertEquals("America/New_York", fields["zoneId"])
        assertEquals("-04:00", fields["utcOffset"], "New York is on EDT (UTC-4) in September")
    }

    @Test
    fun `a fixed UTC zone records a zero offset`() {
        val fields = nightStartTimezoneFields(Instant.parse("2026-01-15T00:00:00Z"), ZoneId.of("UTC"))

        assertEquals("UTC", fields["zoneId"])
        assertEquals("Z", fields["utcOffset"])
    }

    @Test
    fun `the offset reflects the instant it is asked about, not read time - winter is UTC-5 in New York`() {
        val fields = nightStartTimezoneFields(Instant.parse("2026-01-15T05:00:00Z"), ZoneId.of("America/New_York"))

        assertEquals("-05:00", fields["utcOffset"], "New York is on EST (UTC-5) in January")
    }
}
