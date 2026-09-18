package com.nikita.sleepcycle.night

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class NightLogTest {
    @Test
    fun `formats an event as one JSON line with its type and fields`() {
        val event = NightLogEvent(
            at = Instant.parse("2026-09-17T00:30:00Z"),
            type = "sync",
            fields = mapOf("ok" to "true", "durationMs" to "1234")
        )

        val line = formatNightLogLine(event)
        val json = JSONObject(line)

        assertEquals("2026-09-17T00:30:00Z", json.getString("at"))
        assertEquals("sync", json.getString("type"))
        assertEquals("true", json.getJSONObject("fields").getString("ok"))
        assertEquals("1234", json.getJSONObject("fields").getString("durationMs"))
        assertEquals(-1, line.indexOf('\n'), "a log line must be exactly one line")
    }

    @Test
    fun `formats an event with no fields as an empty fields object`() {
        val event = NightLogEvent(at = Instant.parse("2026-09-17T08:14:00Z"), type = "night_end", fields = emptyMap())

        val json = JSONObject(formatNightLogLine(event))

        assertEquals(0, json.getJSONObject("fields").length())
    }

    @Test
    fun `a field value with quotes and newlines still round trips as one JSON line`() {
        val event = NightLogEvent(
            at = Instant.parse("2026-09-17T00:30:00Z"),
            type = "error",
            fields = mapOf("cause" to "failed to open \"export.db\"\nsecond line\ttabbed")
        )

        val line = formatNightLogLine(event)
        val json = JSONObject(line)

        assertEquals(-1, line.indexOf('\n'), "a log line must be exactly one physical line, even when a field's own value contains a newline")
        assertEquals("failed to open \"export.db\"\nsecond line\ttabbed", json.getJSONObject("fields").getString("cause"))
    }

    /**
     * Reproduces the defect from night-20260917-2143.jsonl: a call site captures `now` early, does some I/O,
     * then appends afterwards, so the event's own `at` is earlier than lines already on disk. [appendLogLine]
     * must stamp the moment of the write itself, so file order and `at` order always agree regardless of what
     * instant the caller happened to build the event with.
     */
    @Test
    fun `appendLogLine stamps the moment of the write, not the event's own possibly-stale at`() {
        val file = File.createTempFile("night-log-order-test", ".jsonl")
        file.deleteOnExit()

        // Simulates night_end: built with an instant captured BEFORE some intervening I/O, but appended after.
        val staleAt = Instant.parse("2026-09-17T01:44:10Z")
        val earlierEvent = NightLogEvent(staleAt, "band_alarm_table", emptyMap())
        appendLogLine(file, earlierEvent)
        val laterEvent = NightLogEvent(staleAt.minusSeconds(5), "night_end", emptyMap())
        appendLogLine(file, laterEvent)

        val lines = Files.readAllLines(file.toPath())
        assertEquals(2, lines.size)
        val firstAt = Instant.parse(JSONObject(lines[0]).getString("at"))
        val secondAt = Instant.parse(JSONObject(lines[1]).getString("at"))

        assertFalse(secondAt.isBefore(firstAt), "the second line written must never read as earlier than the first")
        assertTrue(firstAt != staleAt, "the write-time stamp must not just echo the caller's own possibly-stale at")
        assertTrue(secondAt != staleAt.minusSeconds(5), "the write-time stamp must not just echo the caller's own possibly-stale at")
    }
}
