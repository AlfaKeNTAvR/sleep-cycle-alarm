package com.nikita.sleepcycle.ui.state

// File purpose: P1 - the morning report built at End night must tell "no band data ever reached the app" apart
// from "the band reported no sleep". Driven from the real night of 2026-09-29 into 09-30 (night-20260930-0053):
// started 00:53:53 local (05:53:53Z), every one of its 36 syncs failed with "timed out after PT1M", zero
// segments, ended at 08:38:51 local (13:38:51Z). That night's report said "No sleep recorded tonight".

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.SegmentKind
import com.nikita.sleepcycle.engine.SleepSegment
import com.nikita.sleepcycle.night.NightState
import com.nikita.sleepcycle.night.buildNightEngineView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

private val CHICAGO: ZoneId = ZoneId.of("America/Chicago")
private val REAL_NIGHT_STARTED_AT: Instant = Instant.parse("2026-09-30T05:53:53.548Z")
private val REAL_NIGHT_ENDED_AT: Instant = Instant.parse("2026-09-30T13:38:51.081Z")
private val REAL_NIGHT_LAST_SYNC_AT: Instant = Instant.parse("2026-09-30T13:36:59.236Z")

class NoBandDataReportTest {
    /** The real night's state at the moment End night was pressed: no segments, last sync failed. */
    private fun realNightState(
        lastSyncOk: Boolean? = false,
        lastSyncFailureCause: String? = "timed out after PT1M",
        segments: List<SleepSegment> = emptyList(),
    ) = NightState(
        startedAt = REAL_NIGHT_STARTED_AT,
        settings = NightSettings(deadline = null, pickedCycles = 5),
        lastPlan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES),
        lastSyncAt = REAL_NIGHT_LAST_SYNC_AT,
        lastSyncOk = lastSyncOk,
        lastSegments = segments,
        lastExportFileModifiedAt = null,
        lastSyncFailureCause = lastSyncFailureCause,
    )

    private fun reportAtEndNight(state: NightState): NightScreenContent.MorningReport =
        buildMorningReportContent(buildNightEngineView(state, REAL_NIGHT_ENDED_AT), REAL_NIGHT_ENDED_AT, CHICAGO)

    @Test fun `P1 the real night - every sync failed, zero stretches - reports that no band data reached the app`() {
        val report = reportAtEndNight(realNightState())

        assertTrue(report.noBandData)
        assertEquals(emptyList<StretchLine>(), report.stretches)
        assertEquals("08:38", report.endedAtTimeLabel)
    }

    @Test fun `P1 a night whose band synced fine but reported no sleep keeps the no-sleep wording`() {
        val report = reportAtEndNight(realNightState(lastSyncOk = true, lastSyncFailureCause = null))

        assertFalse(report.noBandData)
    }

    @Test fun `P1 a night ended before any sync finished keeps the no-sleep wording`() {
        val report = reportAtEndNight(realNightState(lastSyncOk = null, lastSyncFailureCause = null))

        assertFalse(report.noBandData)
    }

    @Test fun `P1 a night with real sleep whose last sync failed shows its stretches, not the no-data wording`() {
        // Asleep 01:10 to 06:40 local (06:10Z to 11:40Z) synced earlier, then the band stopped syncing: the
        // report shows that one 5 h 30 stretch.
        val asleep = SleepSegment(Instant.parse("2026-09-30T06:10:00Z"), Instant.parse("2026-09-30T11:40:00Z"), SegmentKind.LIGHT)

        val report = reportAtEndNight(realNightState(segments = listOf(asleep)))

        assertFalse(report.noBandData)
        assertEquals(listOf(StretchLine("01:10", "06:40", "5 h 30", "3.7")), report.stretches)
    }
}
