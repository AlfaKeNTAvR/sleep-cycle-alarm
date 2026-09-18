package com.nikita.sleepcycle.night

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.AlarmPlan
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.SegmentKind
import com.nikita.sleepcycle.engine.SleepSegment
import com.nikita.sleepcycle.engine.SleepState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class NightUiSupportTest {
    private val startedAt = Instant.parse("2026-09-16T21:00:00Z")

    @Test
    fun `derives NOT_YET_ASLEEP and an empty summary when there are no segments yet`() {
        val state = nightState(segments = emptyList(), settings = NightSettings(null, 5, false))

        val view = buildNightEngineView(state, now = startedAt)

        assertEquals(SleepState.NOT_YET_ASLEEP, view.sleepState)
        assertTrue(view.summary.stretches.isEmpty())
    }

    @Test
    fun `derives ASLEEP and a one-stretch summary from a single sleep segment`() {
        val onset = Instant.parse("2026-09-16T21:30:00Z")
        val end = Instant.parse("2026-09-17T01:00:00Z")
        val state = nightState(
            segments = listOf(SleepSegment(onset, end, SegmentKind.LIGHT)),
            settings = NightSettings(null, 5, false)
        )

        val view = buildNightEngineView(state, now = end)

        assertEquals(SleepState.ASLEEP, view.sleepState)
        assertEquals(1, view.summary.stretches.size)
        assertEquals(onset, view.summary.stretches.single().onset)
    }

    @Test
    fun `wake options come from the last plan's reference onset when a plan exists`() {
        val state = nightState(segments = emptyList(), settings = NightSettings(null, 3, false))

        val view = buildNightEngineView(state, now = startedAt)

        // No plan yet: falls back to `now` as the reference onset, so options exist and start after now.
        assertTrue(view.wakeOptions.isNotEmpty())
        assertTrue(view.wakeOptions.all { it.wakeTime.isAfter(startedAt) })
    }

    @Test
    fun `after an awakening the timeline offers only what the plan still owes, never a fresh full night`() {
        // 7.5 h picked, 6 h already slept, awake and back asleep at 05:30: the engine plans ONE more cycle.
        // Listing the whole picked length here offered wake-ups out to 13 h, hours past anything the engine
        // would ever set.
        val onset = Instant.parse("2026-09-17T05:30:00Z")
        val plan = AlarmPlan(
            mode = AlarmMode.FULL_CYCLES,
            bandAlarm = Instant.parse("2026-09-17T07:00:00Z"),
            phoneAlarm = null,
            cycles = 1,
            referenceOnset = onset,
            onsetIsProjected = false,
            reason = "one cycle still owed"
        )
        val state = nightState(segments = emptyList(), settings = NightSettings(null, 5, false), lastPlan = plan)

        val view = buildNightEngineView(state, now = onset)

        assertEquals(listOf(1), view.wakeOptions.map { it.cycles })
        assertEquals(Instant.parse("2026-09-17T07:00:00Z"), view.wakeOptions.single().wakeTime)
    }

    @Test
    fun `before any plan exists the timeline still offers the whole picked length`() {
        val state = nightState(segments = emptyList(), settings = NightSettings(null, 5, false))

        val view = buildNightEngineView(state, now = startedAt)

        assertEquals(listOf(1, 2, 3, 4, 5), view.wakeOptions.map { it.cycles }, "nothing slept yet, so the whole picked night is still owed")
    }

    @Test
    fun `a plan owing nothing at all offers no wake-up options`() {
        val onset = Instant.parse("2026-09-17T07:40:00Z")
        val plan = AlarmPlan(
            mode = AlarmMode.NAP,
            bandAlarm = Instant.parse("2026-09-17T08:00:00Z"),
            phoneAlarm = null,
            cycles = 0,
            referenceOnset = onset,
            onsetIsProjected = false,
            reason = "nap"
        )
        val state = nightState(segments = emptyList(), settings = NightSettings(null, 5, false), lastPlan = plan)

        val view = buildNightEngineView(state, now = onset)

        assertTrue(view.wakeOptions.isEmpty(), "the picked total is used up; there is no further cycle to offer")
    }

    private fun nightState(
        segments: List<SleepSegment>,
        settings: NightSettings,
        requestedBandAlarm: BandAlarmCommitment? = null,
        confirmedBandAlarm: BandAlarmCommitment? = null,
        lastPlan: AlarmPlan? = null,
    ) = NightState(
        startedAt = startedAt,
        settings = settings,
        lastPlan = lastPlan,
        requestedBandAlarm = requestedBandAlarm,
        confirmedBandAlarm = confirmedBandAlarm,
        lastSyncAt = null,
        lastSyncOk = null,
        lastSegments = segments,
        lastExportFileModifiedAt = null,
        lastSyncFailureCause = null
    )

    @Test
    fun `band alarm status is Requested when a request is outstanding, even with an older confirmed alarm`() {
        val state = nightState(
            segments = emptyList(),
            settings = NightSettings(null, 5, false),
            requestedBandAlarm = BandAlarmCommitment("SCA-B", 7, 45, startedAt),
            confirmedBandAlarm = BandAlarmCommitment("SCA-A", 7, 30, startedAt),
        )

        val status = bandAlarmStatusFor(state)

        assertEquals(BandAlarmStatus.Requested(7, 45), status)
    }

    @Test
    fun `band alarm status is Confirmed once there is no outstanding request`() {
        val state = nightState(
            segments = emptyList(),
            settings = NightSettings(null, 5, false),
            confirmedBandAlarm = BandAlarmCommitment("SCA-A", 7, 45, startedAt),
        )

        val status = bandAlarmStatusFor(state)

        assertEquals(BandAlarmStatus.Confirmed(7, 45), status)
    }

    @Test
    fun `band alarm status is null when neither a request nor a confirmation is on file`() {
        val state = nightState(segments = emptyList(), settings = NightSettings(null, 5, false))

        assertEquals(null, bandAlarmStatusFor(state))
    }
}
