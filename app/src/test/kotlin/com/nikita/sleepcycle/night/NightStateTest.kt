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
            bandAlarm = Instant.parse("2026-09-17T05:00:00Z"),
            phoneAlarm = Instant.parse("2026-09-17T05:15:00Z"),
            cycles = 5,
            referenceOnset = Instant.parse("2026-09-16T21:30:00Z"),
            onsetIsProjected = false,
            wakeBoundary = Instant.parse("2026-09-17T05:00:00Z"),
            reason = "Asleep since 00:30, 5 of 5 picked cycles fit, band alarm 05:00",
            overdueSince = null
        )
        val state = NightState(
            startedAt = Instant.parse("2026-09-16T21:00:00Z"),
            settings = NightSettings(deadline = Instant.parse("2026-09-17T05:30:00Z"), pickedCycles = 5, phoneBackupEnabled = true),
            lastPlan = plan,
            requestedBandAlarm = BandAlarmCommitment("SCA-B", 5, 0, Instant.parse("2026-09-17T04:50:00Z")),
            confirmedBandAlarm = BandAlarmCommitment("SCA-A", 5, 15, Instant.parse("2026-09-17T04:30:00Z")),
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
    fun `round trips a plan with overdueSince set`() {
        val plan = AlarmPlan(
            mode = AlarmMode.OVERDUE,
            bandAlarm = Instant.parse("2026-09-17T08:07:00Z"),
            phoneAlarm = Instant.parse("2026-09-17T08:15:00Z"),
            cycles = 5,
            referenceOnset = Instant.parse("2026-09-17T00:30:00Z"),
            onsetIsProjected = false,
            wakeBoundary = Instant.parse("2026-09-17T08:00:00Z"),
            reason = "Still asleep past the planned alarm",
            overdueSince = Instant.parse("2026-09-17T08:05:00Z")
        )
        val state = baseState().copy(lastPlan = plan)

        val roundTripped = decodeNightState(encodeNightState(state))

        assertEquals(Instant.parse("2026-09-17T08:05:00Z"), roundTripped.lastPlan?.overdueSince)
    }

    @Test
    fun `round trips a night with no plan yet and every optional field null`() {
        val state = baseState()

        val roundTripped = decodeNightState(encodeNightState(state))

        assertEquals(state, roundTripped)
        assertNull(roundTripped.lastPlan)
        assertNull(roundTripped.lastSyncOk)
        assertNull(roundTripped.requestedBandAlarm)
        assertNull(roundTripped.confirmedBandAlarm)
        assertNull(roundTripped.lastExportFileModifiedAt)
    }

    @Test
    fun `round trips a FINISHED plan with a null band alarm`() {
        val plan = AlarmPlan(
            mode = AlarmMode.FINISHED,
            bandAlarm = null,
            phoneAlarm = Instant.parse("2026-09-17T05:30:00Z"),
            cycles = 0,
            referenceOnset = null,
            onsetIsProjected = false,
            wakeBoundary = Instant.parse("2026-09-17T05:30:00Z"),
            reason = "Deadline passed",
            overdueSince = null
        )
        val state = baseState().copy(lastPlan = plan, lastSyncAt = Instant.parse("2026-09-17T05:30:00Z"), lastSyncOk = true)

        val roundTripped = decodeNightState(encodeNightState(state))

        assertEquals(state, roundTripped)
        assertNull(roundTripped.lastPlan?.bandAlarm)
        assertNull(roundTripped.lastPlan?.referenceOnset)
    }

    @Test
    fun `round trips debug options`() {
        val state = baseState().copy(debugOptions = DebugOptions(simulatedBandData = true, fastNight = true, bandCommandMode = BandCommandMode.DRY_RUN))

        val roundTripped = decodeNightState(encodeNightState(state))

        assertEquals(state.debugOptions, roundTripped.debugOptions)
    }

    @Test
    fun `decodes a state saved before debugOptions existed as all-off, same as a normal night`() {
        val json = fullStateJson() // debugOptions deliberately absent, per fullStateJson's own encoding path... see below
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

    @Test
    fun `round trips a band alarm commitment's blindResendCount`() {
        val state = baseState().copy(requestedBandAlarm = BandAlarmCommitment(BAND_ALARM_TITLE_A, 8, 0, Instant.parse("2026-09-17T04:00:00Z"), blindResendCount = 3))

        val roundTripped = decodeNightState(encodeNightState(state))

        assertEquals(3, roundTripped.requestedBandAlarm?.blindResendCount)
    }

    @Test
    fun `a commitment saved before blindResendCount existed decodes it as 0 (C2)`() {
        val json = fullStateJson()
        json.getJSONObject("requestedBandAlarm").remove("blindResendCount")

        val state = decodeNightState(json.toString())

        assertEquals(0, state.requestedBandAlarm?.blindResendCount)
    }

    @Test
    fun `round trips finishedCleanupTicksUsed (C4)`() {
        val state = baseState().copy(finishedCleanupTicksUsed = 4)

        val roundTripped = decodeNightState(encodeNightState(state))

        assertEquals(4, roundTripped.finishedCleanupTicksUsed)
    }

    @Test
    fun `a state saved before finishedCleanupTicksUsed existed decodes it as 0 (C4)`() {
        val json = fullStateJson()
        json.remove("finishedCleanupTicksUsed")

        val state = decodeNightState(json.toString())

        assertEquals(0, state.finishedCleanupTicksUsed)
    }

    @Test
    fun `decodes a state saved before requestedBandAlarm and lastExportFileModifiedAt existed`() {
        val json = JSONObject().apply {
            put("startedAt", "2026-09-16T21:00:00Z")
            put(
                "settings",
                JSONObject().apply {
                    put("deadline", JSONObject.NULL)
                    put("pickedCycles", 5)
                    put("phoneBackupEnabled", false)
                }
            )
            put("lastPlan", JSONObject.NULL)
            put("lastSyncAt", JSONObject.NULL)
            put("lastSyncOk", JSONObject.NULL)
            put("lastSegments", org.json.JSONArray())
            // requestedBandAlarm, confirmedBandAlarm, lastExportFileModifiedAt deliberately absent.
        }

        val state = decodeNightState(json.toString())

        assertNull(state.requestedBandAlarm)
        assertNull(state.confirmedBandAlarm)
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
    fun `a malformed segment is dropped without losing the rest of the segments`() {
        val json = fullStateJson()
        val segments = json.getJSONArray("lastSegments")
        segments.put(JSONObject().apply { put("start", "not-an-instant"); put("end", "2026-09-17T01:00:00Z"); put("kind", "LIGHT") })

        val state = decodeNightState(json.toString())

        assertEquals(1, state.lastSegments.size)
    }

    @Test
    fun `a malformed confirmed band alarm is dropped without losing the rest of the state`() {
        val json = fullStateJson()
        json.put("confirmedBandAlarm", JSONObject().apply { put("title", "SCA-A") }) // missing hour/minute/at

        val state = decodeNightState(json.toString())

        assertNull(state.confirmedBandAlarm)
        assertEquals("SCA-B", state.requestedBandAlarm?.title)
    }

    @Test
    fun `truncated JSON with a missing startedAt throws rather than silently returning garbage`() {
        val json = fullStateJson().apply { remove("startedAt") }

        assertTrue(
            runCatching { decodeNightState(json.toString()) }.isFailure,
            "missing startedAt is a structural failure, not something decodeNightState should paper over"
        )
    }

    private fun baseState(): NightState = NightState(
        startedAt = Instant.parse("2026-09-16T21:00:00Z"),
        settings = NightSettings(deadline = null, pickedCycles = 3, phoneBackupEnabled = false),
        lastPlan = null,
        requestedBandAlarm = null,
        confirmedBandAlarm = null,
        lastSyncAt = null,
        lastSyncOk = null,
        lastSegments = emptyList(),
        lastExportFileModifiedAt = null,
        lastSyncFailureCause = null
    )

    private fun fullStateJson(): JSONObject {
        val plan = AlarmPlan(
            mode = AlarmMode.FULL_CYCLES,
            bandAlarm = Instant.parse("2026-09-17T05:00:00Z"),
            phoneAlarm = Instant.parse("2026-09-17T05:15:00Z"),
            cycles = 5,
            referenceOnset = Instant.parse("2026-09-16T21:30:00Z"),
            onsetIsProjected = false,
            wakeBoundary = Instant.parse("2026-09-17T05:00:00Z"),
            reason = "reason",
            overdueSince = null
        )
        val state = baseState().copy(
            lastPlan = plan,
            requestedBandAlarm = BandAlarmCommitment("SCA-B", 5, 0, Instant.parse("2026-09-17T04:50:00Z")),
            confirmedBandAlarm = BandAlarmCommitment("SCA-A", 5, 15, Instant.parse("2026-09-17T04:30:00Z")),
            lastSegments = listOf(SleepSegment(Instant.parse("2026-09-16T21:30:00Z"), Instant.parse("2026-09-17T01:00:00Z"), SegmentKind.LIGHT))
        )
        return JSONObject(encodeNightState(state))
    }
}
