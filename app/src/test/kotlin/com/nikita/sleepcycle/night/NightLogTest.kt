package com.nikita.sleepcycle.night

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
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
}
