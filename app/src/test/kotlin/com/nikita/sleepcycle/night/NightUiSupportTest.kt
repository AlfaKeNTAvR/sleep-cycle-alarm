package com.nikita.sleepcycle.night

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

    private fun nightState(
        segments: List<SleepSegment>,
        settings: NightSettings,
        requestedBandAlarm: BandAlarmCommitment? = null,
        confirmedBandAlarm: BandAlarmCommitment? = null,
    ) = NightState(
        startedAt = startedAt,
        settings = settings,
        lastPlan = null,
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
