package com.nikita.sleepcycle.night

// File purpose: unit tests for NightTickLogging.encodeSegmentsDeltaForLog (D) - the real complaint was that
// night-20260920-0104.jsonl dumped the WHOLE segment list on all 37 ticks whose data changed, including the
// same LIGHT interval repeated with a later end time as it grew. This checks the replacement only ever logs
// what changed: new segments, extended ones (with their previous end), and nothing for an unchanged one.

import com.nikita.sleepcycle.engine.SegmentKind
import com.nikita.sleepcycle.engine.SleepSegment
import org.json.JSONArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class SegmentLogDeltaTest {
    private fun at(clockTime: String) = Instant.parse("2026-09-20T${clockTime}:00Z")

    @Test
    fun `a segment not seen before is logged as new, in full`() {
        val segment = SleepSegment(at("05:39"), at("06:00"), SegmentKind.LIGHT)

        val array = JSONArray(encodeSegmentsDeltaForLog(listOf(segment), previousSegments = emptyList()))

        assertEquals(1, array.length())
        val entry = array.getJSONObject(0)
        assertEquals(at("05:39").toString(), entry.getString("start"))
        assertEquals(at("06:00").toString(), entry.getString("end"))
        assertEquals("LIGHT", entry.getString("kind"))
        assertEquals("new", entry.getString("status"))
    }

    @Test
    fun `a still-growing segment is logged as extended, carrying its previous end, not repeated as a fresh entry`() {
        val original = SleepSegment(at("05:39"), at("06:00"), SegmentKind.LIGHT)
        val grown = original.copy(end = at("06:15"))

        val array = JSONArray(encodeSegmentsDeltaForLog(listOf(grown), previousSegments = listOf(original)))

        assertEquals(1, array.length())
        val entry = array.getJSONObject(0)
        assertEquals(at("06:15").toString(), entry.getString("end"))
        assertEquals("extended", entry.getString("status"))
        assertEquals(at("06:00").toString(), entry.getString("previousEnd"))
    }

    @Test
    fun `a segment identical to what was already logged is dropped entirely, not repeated`() {
        val unchanged = SleepSegment(at("05:39"), at("06:00"), SegmentKind.LIGHT)
        val newSegment = SleepSegment(at("06:00"), at("06:15"), SegmentKind.DEEP)

        val array = JSONArray(encodeSegmentsDeltaForLog(listOf(unchanged, newSegment), previousSegments = listOf(unchanged)))

        assertEquals(1, array.length(), "the unchanged segment contributes nothing, only the new one is logged")
        assertEquals("DEEP", array.getJSONObject(0).getString("kind"))
    }

    @Test
    fun `a real growth sequence from the log logs only the delta on each tick, never the whole list again`() {
        val light = SleepSegment(at("05:39"), at("06:00"), SegmentKind.LIGHT)
        val deep = SleepSegment(at("06:00"), at("06:15"), SegmentKind.DEEP)
        val awake = SleepSegment(at("06:15"), at("06:17"), SegmentKind.AWAKE)

        // Tick A: DEEP just appeared.
        val tickA = JSONArray(encodeSegmentsDeltaForLog(listOf(light, deep), previousSegments = listOf(light)))
        assertEquals(1, tickA.length())
        assertEquals("DEEP", tickA.getJSONObject(0).getString("kind"))
        assertEquals("new", tickA.getJSONObject(0).getString("status"))

        // Tick B: AWAKE just appeared; LIGHT and DEEP are unchanged from tick A and must not reappear.
        val tickB = JSONArray(encodeSegmentsDeltaForLog(listOf(light, deep, awake), previousSegments = listOf(light, deep)))
        assertEquals(1, tickB.length())
        assertEquals("AWAKE", tickB.getJSONObject(0).getString("kind"))
    }

    @Test
    fun `two intervals that happen to share a start but differ in kind are treated as distinct segments, not an extension`() {
        val light = SleepSegment(at("06:15"), at("06:16"), SegmentKind.LIGHT)
        val awakeSameStart = SleepSegment(at("06:15"), at("06:17"), SegmentKind.AWAKE)

        val array = JSONArray(encodeSegmentsDeltaForLog(listOf(awakeSameStart), previousSegments = listOf(light)))

        assertEquals(1, array.length())
        assertEquals("new", array.getJSONObject(0).getString("status"), "different kind at the same start is a different segment, not light's own extension")
    }

    @Test
    fun `no segments at all encodes as an empty array`() {
        assertEquals("[]", encodeSegmentsDeltaForLog(emptyList(), previousSegments = emptyList()))
    }
}
