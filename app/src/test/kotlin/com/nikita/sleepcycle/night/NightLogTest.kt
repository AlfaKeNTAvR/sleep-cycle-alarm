package com.nikita.sleepcycle.night

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun `parses a formatted line back into the event that wrote it`() {
        val event = NightLogEvent(
            at = Instant.parse("2026-09-17T00:30:00Z"),
            type = "sync",
            fields = mapOf("ok" to "true", "durationMs" to "1234")
        )

        assertEquals(event, parseNightLogLine(formatNightLogLine(event)))
    }

    @Test
    fun `parses a line that carries no fields`() {
        val event = NightLogEvent(at = Instant.parse("2026-09-17T08:14:00Z"), type = "night_end", fields = emptyMap())

        assertEquals(event, parseNightLogLine(formatNightLogLine(event)))
    }

    @Test
    fun `a blank, truncated or non-object line parses as nothing rather than throwing`() {
        assertEquals(null, parseNightLogLine(""))
        assertEquals(null, parseNightLogLine("   "))
        assertEquals(null, parseNightLogLine("""{"at":"2026-09-17T00:30:00Z","type":"sync","fiel"""))
        assertEquals(null, parseNightLogLine("""{"type":"sync","fields":{}}"""), "a line with no at is not a usable event")
        assertEquals(null, parseNightLogLine("""{"at":"not a time","type":"sync","fields":{}}"""))
    }

    /**
     * V9 REVERSES this file's own older test here ("appendLogLine stamps the moment of the write, not the
     * event's own possibly-stale at"): [appendLogLine] used to overwrite every event's `at` with `nowInstant()`
     * at write time, which the old test modelled as fixing an out-of-order write. That blanket re-stamp broke
     * something worse than it fixed: the debug test-alarm events and `clock_jumped` deliberately pass a REAL or
     * pre-jump instant (T8, DebugScreenController.applyClockJump), and the re-stamp silently overwrote both
     * with a virtual `nowInstant()` - for `clock_jumped`, one that was already past the jump, so the event
     * ended up stamped with the time it jumped TO instead of the time it happened. See appendLogLine's own doc
     * for the full reasoning, including why the "out of order" risk this used to guard against was, in
     * practice, at most cosmetic. [appendLogLine] now honours [NightLogEvent.at] exactly as the caller built it.
     */
    @Test
    fun `appendLogLine honours the caller's own at exactly, never re-stamping it`() {
        val file = File.createTempFile("night-log-at-test", ".jsonl")
        file.deleteOnExit()

        // A deliberately real, pre-jump instant - exactly what DebugScreenController.applyClockJump passes for
        // a clock_jumped event (V9's own named case).
        val deliberateAt = Instant.parse("2026-09-17T01:44:10Z")
        val event = NightLogEvent(deliberateAt, "clock_jumped", mapOf("to" to "2026-09-18T03:00:00Z"))

        appendLogLine(file, event)

        val line = Files.readAllLines(file.toPath()).single()
        assertEquals(deliberateAt, Instant.parse(JSONObject(line).getString("at")), "appendLogLine must not overwrite the caller's own deliberately-chosen at")
        assertEquals(event, parseNightLogLine(line))
    }

    @Test
    fun `two events appended in sequence each keep their own distinct caller-supplied at`() {
        val file = File.createTempFile("night-log-at-sequence-test", ".jsonl")
        file.deleteOnExit()

        val firstAt = Instant.parse("2026-09-17T01:44:10Z")
        val secondAt = Instant.parse("2026-09-17T01:44:15Z")
        appendLogLine(file, NightLogEvent(firstAt, "sync", emptyMap()))
        appendLogLine(file, NightLogEvent(secondAt, "data", emptyMap()))

        val lines = Files.readAllLines(file.toPath())
        assertEquals(firstAt, Instant.parse(JSONObject(lines[0]).getString("at")))
        assertEquals(secondAt, Instant.parse(JSONObject(lines[1]).getString("at")))
    }
}
