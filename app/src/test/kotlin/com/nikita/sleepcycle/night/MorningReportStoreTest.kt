package com.nikita.sleepcycle.night

import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.SegmentKind
import com.nikita.sleepcycle.engine.SleepSegment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class MorningReportStoreTest {
    @Test
    fun `round trips a morning report snapshot through JSON`() {
        val nightState = NightState(
            startedAt = Instant.parse("2026-09-16T21:00:00Z"),
            settings = NightSettings(deadline = null, pickedCycles = 5, phoneBackupEnabled = false),
            lastPlan = null,
            requestedBandAlarm = null,
            confirmedBandAlarm = BandAlarmCommitment("SCA-A", 7, 30, Instant.parse("2026-09-17T05:00:00Z")),
            lastSyncAt = Instant.parse("2026-09-17T05:05:00Z"),
            lastSyncOk = true,
            lastSegments = listOf(
                SleepSegment(Instant.parse("2026-09-16T21:30:00Z"), Instant.parse("2026-09-17T01:00:00Z"), SegmentKind.LIGHT)
            ),
            lastExportFileModifiedAt = Instant.parse("2026-09-17T05:04:00Z"),
            lastSyncFailureCause = null
        )
        val snapshot = MorningReportSnapshot(nightState, endedAt = Instant.parse("2026-09-17T07:15:00Z"))

        val roundTripped = decodeMorningReportSnapshot(encodeMorningReportSnapshot(snapshot))

        assertEquals(snapshot, roundTripped)
    }
}
