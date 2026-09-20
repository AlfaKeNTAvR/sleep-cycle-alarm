package com.nikita.sleepcycle.night

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.AlarmPlan
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.SegmentKind
import com.nikita.sleepcycle.engine.SleepSegment
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class NightStateTest {
    @Test
    fun `round trips a full night state through JSON`() {
        val plan = AlarmPlan(
            mode = AlarmMode.FULL_CYCLES,
            wakeAt = Instant.parse("2026-09-17T05:00:00Z"),
            cycles = 5,
            referenceOnset = Instant.parse("2026-09-16T21:30:00Z"),
            onsetIsProjected = false,
            reason = "Asleep since 00:30, 5 of 5 picked cycles fit, alarm 05:00"
        )
        val state = NightState(
            startedAt = Instant.parse("2026-09-16T21:00:00Z"),
            settings = NightSettings(deadline = Instant.parse("2026-09-17T05:30:00Z"), pickedCycles = 5),
            lastPlan = plan,
            lastSyncAt = Instant.parse("2026-09-17T04:45:00Z"),
            lastSyncOk = true,
            lastSegments = listOf(
                SleepSegment(Instant.parse("2026-09-16T21:30:00Z"), Instant.parse("2026-09-17T01:00:00Z"), SegmentKind.LIGHT)
            ),
            lastExportFileModifiedAt = Instant.parse("2026-09-17T04:44:30Z"),
            lastSyncFailureCause = null
        )

        val roundTripped = decodeNightState(encodeNightState(state))

        assertEquals(state, roundTripped)
    }

    @Test
    fun `round trips a sync failure cause`() {
        val state = baseState().copy(lastSyncAt = Instant.parse("2026-09-17T04:45:00Z"), lastSyncOk = false, lastSyncFailureCause = "timed out after PT60S")

        val roundTripped = decodeNightState(encodeNightState(state))

        assertEquals("timed out after PT60S", roundTripped.lastSyncFailureCause)
    }

    @Test
    fun `round trips a night with no plan yet and every optional field null`() {
        val state = baseState()

        val roundTripped = decodeNightState(encodeNightState(state))

        assertEquals(state, roundTripped)
        assertNull(roundTripped.lastPlan)
        assertNull(roundTripped.lastSyncOk)
        assertNull(roundTripped.lastExportFileModifiedAt)
    }

    @Test
    fun `round trips a FINISHED plan with a null wake time`() {
        val plan = AlarmPlan(
            mode = AlarmMode.FINISHED,
            wakeAt = null,
            cycles = 0,
            referenceOnset = null,
            onsetIsProjected = false,
            reason = "Deadline passed"
        )
        val state = baseState().copy(lastPlan = plan, lastSyncAt = Instant.parse("2026-09-17T05:30:00Z"), lastSyncOk = true)

        val roundTripped = decodeNightState(encodeNightState(state))

        assertEquals(state, roundTripped)
        assertNull(roundTripped.lastPlan?.wakeAt)
        assertNull(roundTripped.lastPlan?.referenceOnset)
    }

    @Test
    fun `round trips debug options`() {
        val warp = ClockWarp(60, Instant.parse("2026-09-17T20:00:00Z"), Instant.parse("2026-09-17T20:00:00Z"))
        val state = baseState().copy(debugOptions = DebugOptions(simulatedBandData = true, warp = warp))

        val roundTripped = decodeNightState(encodeNightState(state))

        assertEquals(state.debugOptions, roundTripped.debugOptions)
    }

    @Test
    fun `decodes a state saved before debugOptions existed as all-off, same as a normal night`() {
        val json = fullStateJson()
        json.remove("debugOptions")

        val state = decodeNightState(json.toString())

        assertEquals(DebugOptions(), state.debugOptions)
    }

    @Test
    fun `a malformed debugOptions block decodes as all-off rather than failing the whole state`() {
        val json = fullStateJson()
        json.put("debugOptions", JSONObject().apply { put("simulatedBandData", "not-a-boolean") })

        val state = decodeNightState(json.toString())

        assertEquals(DebugOptions(), state.debugOptions)
    }

    /** T7 (required test): a state persisted by the old build carries `fastNight` (a boolean), not `speed` - that key is simply never read (same as every other deleted-field case this decoder handles), so it degrades to speed 1 rather than crashing, with no fastNight-to-speed fallback. */
    @Test
    fun `T7 an old debugOptions block carrying fastNight instead of speed decodes with speed 1`() {
        val json = fullStateJson()
        json.put("debugOptions", JSONObject().apply { put("simulatedBandData", false); put("fastNight", true) })

        val state = decodeNightState(json.toString())

        assertEquals(DebugOptions(simulatedBandData = false), state.debugOptions)
        assertEquals(1, state.debugOptions.speed)
    }

    /** T7: a state persisted before `speed` existed at all (but after `simulatedBandData` did) also degrades to speed 1. */
    @Test
    fun `T7 a debugOptions block with simulatedBandData but no speed field decodes with speed 1`() {
        val json = fullStateJson()
        json.put("debugOptions", JSONObject().apply { put("simulatedBandData", true) })

        val state = decodeNightState(json.toString())

        assertEquals(DebugOptions(simulatedBandData = true), state.debugOptions)
        assertEquals(1, state.debugOptions.speed)
    }

    /** U3: [NightState.debugOptions] is a frozen snapshot, so the warp active at night start must round trip through it exactly like speed/simulatedBandData already do. */
    @Test
    fun `round trips an active clock warp inside debug options`() {
        val warp = ClockWarp(60, Instant.parse("2026-09-17T20:00:00Z"), Instant.parse("2026-09-18T03:00:00Z"))
        val state = baseState().copy(debugOptions = DebugOptions(simulatedBandData = true, warp = warp))

        val roundTripped = decodeNightState(encodeNightState(state))

        assertEquals(state.debugOptions, roundTripped.debugOptions)
        assertEquals(warp, roundTripped.debugOptions.warp)
    }

    /** U3: a state saved before `warp` existed (or with no warp active at night start) decodes with warp null, same as every other absent-field case here. */
    @Test
    fun `a debugOptions block with no warp field decodes with warp null`() {
        val json = fullStateJson()
        json.put("debugOptions", JSONObject().apply { put("simulatedBandData", false); put("speed", 1) })

        val state = decodeNightState(json.toString())

        assertNull(state.debugOptions.warp)
    }

    /** U3: a malformed warp block degrades to null rather than failing the whole state, same tolerant-decode style as every other optional field here. */
    @Test
    fun `a malformed warp block decodes with warp null rather than failing the whole state`() {
        val json = fullStateJson()
        json.put(
            "debugOptions",
            JSONObject().apply {
                put("simulatedBandData", true)
                put("speed", 60)
                put("warp", JSONObject().apply { put("speed", "not-a-number") })
            }
        )

        val state = decodeNightState(json.toString())

        assertNull(state.debugOptions.warp)
    }

    @Test
    fun `decodes a state saved before lastExportFileModifiedAt existed`() {
        val json = JSONObject().apply {
            put("startedAt", "2026-09-16T21:00:00Z")
            put(
                "settings",
                JSONObject().apply {
                    put("deadline", JSONObject.NULL)
                    put("pickedCycles", 5)
                }
            )
            put("lastPlan", JSONObject.NULL)
            put("lastSyncAt", JSONObject.NULL)
            put("lastSyncOk", JSONObject.NULL)
            put("lastSegments", org.json.JSONArray())
            // lastExportFileModifiedAt deliberately absent.
        }

        val state = decodeNightState(json.toString())

        assertNull(state.lastExportFileModifiedAt)
        assertEquals(5, state.settings.pickedCycles)
    }

    @Test
    fun `an unknown plan mode is dropped and the rest of the state still loads`() {
        val json = fullStateJson().apply {
            getJSONObject("lastPlan").put("mode", "SOME_FUTURE_MODE")
        }

        val state = decodeNightState(json.toString())

        assertNull(state.lastPlan)
        assertEquals(Instant.parse("2026-09-16T21:00:00Z"), state.startedAt)
    }

    @Test
    fun `a plan whose mode is the deleted OVERDUE value is dropped without throwing (D8)`() {
        val json = fullStateJson().apply {
            getJSONObject("lastPlan").put("mode", "OVERDUE")
        }

        val state = decodeNightState(json.toString())

        assertNull(state.lastPlan)
    }

    @Test
    fun `a malformed segment is dropped without losing the rest of the segments`() {
        val json = fullStateJson()
        val segments = json.getJSONArray("lastSegments")
        segments.put(JSONObject().apply { put("start", "not-an-instant"); put("end", "2026-09-17T01:00:00Z"); put("kind", "LIGHT") })

        val state = decodeNightState(json.toString())

        assertEquals(1, state.lastSegments.size)
    }

    @Test
    fun `truncated JSON with a missing startedAt throws rather than silently returning garbage`() {
        val json = fullStateJson().apply { remove("startedAt") }

        assertTrue(
            runCatching { decodeNightState(json.toString()) }.isFailure,
            "missing startedAt is a structural failure, not something decodeNightState should paper over"
        )
    }

    /**
     * D2: a state file written by the old build can still carry the whole deleted band-alarm-machinery shape
     * (requestedBandAlarm, confirmedBandAlarm, pendingDismissTitles, smartWakeupWarning, lastBandAlarmSet,
     * singleSlotResendsUsed, finishedCleanupTicksUsed) plus the old plan's bandAlarm/phoneAlarm/overdueSince
     * and the old settings' phoneBackupEnabled. None of those keys are read by the current decoder, so they
     * are simply ignored rather than migrated - the state still loads without throwing.
     */
    @Test
    fun `a state file written by the old build decodes without throwing, ignoring the deleted band-alarm keys`() {
        val json = JSONObject().apply {
            put("startedAt", "2026-09-16T21:00:00Z")
            put(
                "settings",
                JSONObject().apply {
                    put("deadline", JSONObject.NULL)
                    put("pickedCycles", 5)
                    put("phoneBackupEnabled", true)
                }
            )
            put(
                "lastPlan",
                JSONObject().apply {
                    put("mode", "FULL_CYCLES")
                    put("bandAlarm", "2026-09-17T05:00:00Z")
                    put("phoneAlarm", "2026-09-17T05:15:00Z")
                    put("cycles", 5)
                    put("referenceOnset", "2026-09-16T21:30:00Z")
                    put("onsetIsProjected", false)
                    put("reason", "reason")
                    put("overdueSince", JSONObject.NULL)
                }
            )
            put("requestedBandAlarm", JSONObject().apply { put("title", "SCA-B"); put("hour", 5); put("minute", 0); put("at", "2026-09-17T04:50:00Z") })
            put("confirmedBandAlarm", JSONObject.NULL)
            put("lastSyncAt", JSONObject.NULL)
            put("lastSyncOk", JSONObject.NULL)
            put("lastSegments", org.json.JSONArray())
            put("pendingDismissTitles", org.json.JSONArray().put("SCA-A"))
            put("smartWakeupWarning", JSONObject.NULL)
            put("lastBandAlarmSet", JSONObject.NULL)
            put("singleSlotResendsUsed", 2)
            put("finishedCleanupTicksUsed", 1)
        }

        // Calling decodeNightState directly, not through assertDoesNotThrow, is itself the "does not throw"
        // assertion here: a thrown exception fails this test exactly as surely, and Kotlin's lambda-to-Unit
        // conversion makes assertDoesNotThrow's generic, value-returning overload ambiguous with its
        // Executable one.
        val state = decodeNightState(json.toString())

        assertEquals(5, state.settings.pickedCycles)
        // The old plan decodes structurally (mode/cycles/etc. all still present), but wakeAt is null: the
        // old build never wrote that key, and no migration from the deleted `bandAlarm` key is attempted.
        assertEquals(AlarmMode.FULL_CYCLES, state.lastPlan?.mode)
        assertNull(state.lastPlan?.wakeAt)
    }

    @Test
    fun `round trips awakeConfirmedAt and napAlarmsUsed`() {
        val state = baseState().copy(awakeConfirmedAt = Instant.parse("2026-09-17T05:12:00Z"), napAlarmsUsed = 2)

        val roundTripped = decodeNightState(encodeNightState(state))

        assertEquals(state, roundTripped)
        assertEquals(Instant.parse("2026-09-17T05:12:00Z"), roundTripped.awakeConfirmedAt)
        assertEquals(2, roundTripped.napAlarmsUsed)
    }

    @Test
    fun `F6 round trips wakeAlarmFiredAt`() {
        val state = baseState().copy(wakeAlarmFiredAt = Instant.parse("2026-09-17T07:00:00Z"))

        val roundTripped = decodeNightState(encodeNightState(state))

        assertEquals(state, roundTripped)
        assertEquals(Instant.parse("2026-09-17T07:00:00Z"), roundTripped.wakeAlarmFiredAt)
    }

    @Test
    fun `H1 round trips morningAlarmAt`() {
        val state = baseState().copy(morningAlarmAt = Instant.parse("2026-09-17T06:45:00Z"))

        val roundTripped = decodeNightState(encodeNightState(state))

        assertEquals(state, roundTripped)
        assertEquals(Instant.parse("2026-09-17T06:45:00Z"), roundTripped.morningAlarmAt)
    }

    @Test
    fun `H2 round trips lastNapAlarmFiredAt`() {
        val state = baseState().copy(lastNapAlarmFiredAt = Instant.parse("2026-09-17T07:30:00Z"))

        val roundTripped = decodeNightState(encodeNightState(state))

        assertEquals(state, roundTripped)
        assertEquals(Instant.parse("2026-09-17T07:30:00Z"), roundTripped.lastNapAlarmFiredAt)
    }

    @Test
    fun `decodes a state saved before H1 H2 existed as no latched alarm and no nap fired, same as a new night`() {
        val json = fullStateJson()
        json.remove("morningAlarmAt")
        json.remove("lastNapAlarmFiredAt")

        val state = decodeNightState(json.toString())

        assertNull(state.morningAlarmAt)
        assertNull(state.lastNapAlarmFiredAt)
    }

    @Test
    fun `F2 a napAlarmsUsed above the cap decodes clamped, never handed to the engine over the limit`() {
        val json = fullStateJson()
        json.put("napAlarmsUsed", 9)

        val state = decodeNightState(json.toString())

        assertEquals(com.nikita.sleepcycle.engine.MAX_NAP_ALARMS, state.napAlarmsUsed)
    }

    @Test
    fun `G6 a napAlarmsUsed that is not a number decodes as 0 rather than throwing away the whole state`() {
        val json = fullStateJson()
        json.put("napAlarmsUsed", "not-a-number")

        val state = decodeNightState(json.toString())

        assertEquals(0, state.napAlarmsUsed)
    }

    @Test
    fun `G6 an unparseable awakeConfirmedAt decodes as null rather than throwing away the whole state`() {
        val json = fullStateJson()
        json.put("awakeConfirmedAt", "not-an-instant")

        val state = decodeNightState(json.toString())

        assertNull(state.awakeConfirmedAt)
    }

    @Test
    fun `decodes a state saved before D3 D5 F6 existed as unconfirmed with no naps used and no wake alarm fired, same as a new night`() {
        val json = fullStateJson()
        json.remove("awakeConfirmedAt")
        json.remove("napAlarmsUsed")
        json.remove("wakeAlarmFiredAt")

        val state = decodeNightState(json.toString())

        assertNull(state.awakeConfirmedAt)
        assertEquals(0, state.napAlarmsUsed)
        assertNull(state.wakeAlarmFiredAt)
    }

    private fun baseState(): NightState = NightState(
        startedAt = Instant.parse("2026-09-16T21:00:00Z"),
        settings = NightSettings(deadline = null, pickedCycles = 3),
        lastPlan = null,
        lastSyncAt = null,
        lastSyncOk = null,
        lastSegments = emptyList(),
        lastExportFileModifiedAt = null,
        lastSyncFailureCause = null
    )

    private fun fullStateJson(): JSONObject {
        val plan = AlarmPlan(
            mode = AlarmMode.FULL_CYCLES,
            wakeAt = Instant.parse("2026-09-17T05:00:00Z"),
            cycles = 5,
            referenceOnset = Instant.parse("2026-09-16T21:30:00Z"),
            onsetIsProjected = false,
            reason = "reason"
        )
        val state = baseState().copy(
            lastPlan = plan,
            lastSegments = listOf(SleepSegment(Instant.parse("2026-09-16T21:30:00Z"), Instant.parse("2026-09-17T01:00:00Z"), SegmentKind.LIGHT))
        )
        return JSONObject(encodeNightState(state))
    }
}
