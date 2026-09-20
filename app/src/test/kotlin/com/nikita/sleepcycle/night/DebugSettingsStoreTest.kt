package com.nikita.sleepcycle.night

// File purpose: JSON round trip and tolerant decoding for the persisted simulated sleep event timeline,
// including T9's migration of the old three-button event-kind names onto the new two-state ASLEEP/AWAKE.

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class DebugSettingsStoreTest {
    @Test
    fun `round trips an empty event list`() {
        assertTrue(decodeSimulatedSleepEventsTolerant(encodeSimulatedSleepEvents(emptyList())).isEmpty())
    }

    @Test
    fun `round trips a full simulated timeline`() {
        val events = listOf(
            SimulatedSleepEvent(SimulatedSleepEventKind.ASLEEP, Instant.parse("2026-09-17T22:00:00Z")),
            SimulatedSleepEvent(SimulatedSleepEventKind.AWAKE, Instant.parse("2026-09-17T22:30:00Z")),
            SimulatedSleepEvent(SimulatedSleepEventKind.ASLEEP, Instant.parse("2026-09-17T22:35:00Z")),
        )

        val roundTripped = decodeSimulatedSleepEventsTolerant(encodeSimulatedSleepEvents(events))

        assertEquals(events, roundTripped)
    }

    @Test
    fun `malformed JSON decodes as an empty list rather than throwing`() {
        assertTrue(decodeSimulatedSleepEventsTolerant("not json at all").isEmpty())
    }

    @Test
    fun `an unknown event kind is dropped without losing the rest of the timeline`() {
        val good = SimulatedSleepEvent(SimulatedSleepEventKind.ASLEEP, Instant.parse("2026-09-17T22:00:00Z"))
        val json = org.json.JSONArray().apply {
            put(org.json.JSONObject().apply { put("kind", good.kind.name); put("at", good.at.toString()) })
            put(org.json.JSONObject().apply { put("kind", "SOME_FUTURE_KIND"); put("at", "2026-09-17T22:05:00Z") })
        }

        val decoded = decodeSimulatedSleepEventsTolerant(json.toString())

        assertEquals(listOf(good), decoded)
    }

    // ---- T9: old persisted event-kind names migrate onto the new two-state kind -------------------------

    @Test
    fun `FELL_ASLEEP and FELL_BACK_ASLEEP both migrate to ASLEEP, WOKE_UP migrates to AWAKE`() {
        val json = org.json.JSONArray().apply {
            put(org.json.JSONObject().apply { put("kind", "FELL_ASLEEP"); put("at", "2026-09-17T22:00:00Z") })
            put(org.json.JSONObject().apply { put("kind", "WOKE_UP"); put("at", "2026-09-17T22:30:00Z") })
            put(org.json.JSONObject().apply { put("kind", "FELL_BACK_ASLEEP"); put("at", "2026-09-17T22:35:00Z") })
        }

        val decoded = decodeSimulatedSleepEventsTolerant(json.toString())

        assertEquals(
            listOf(
                SimulatedSleepEvent(SimulatedSleepEventKind.ASLEEP, Instant.parse("2026-09-17T22:00:00Z")),
                SimulatedSleepEvent(SimulatedSleepEventKind.AWAKE, Instant.parse("2026-09-17T22:30:00Z")),
                SimulatedSleepEvent(SimulatedSleepEventKind.ASLEEP, Instant.parse("2026-09-17T22:35:00Z")),
            ),
            decoded
        )
    }
}
