package com.nikita.sleepcycle.night

// File purpose: buildSuccessReport's data-freshness line and its readiness verdict. D2/D7 removed the whole
// band-alarm-slot side of the setup check (usable slots, other alarms, smart-wakeup poaching, conflicting
// titles) along with the rest of the band alarm machinery - there is no band alarm to set, so none of that
// applies to setup anymore. [phoneLines] is always passed through unchanged; its own content is
// AlarmNotificationReadiness's concern, not this file's.

import com.nikita.sleepcycle.bridge.BandDataResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

private val NOW = Instant.parse("2026-09-17T00:00:00Z")

class SetupCheckTest {
    @Test
    fun `fresh data is ready, with an INFO connected line`() {
        val result = success(newestSampleAt = NOW.minusSeconds(60))

        val report = buildSuccessReport(result, NOW, phoneLines = emptyList())

        assertTrue(report.isReady)
        val connectedLine = report.lines.single { it.text.startsWith("Connected.") }
        assertEquals(SetupCheckLineSeverity.INFO, connectedLine.severity)
        assertTrue(connectedLine.text.contains("3 sleep segments"))
    }

    @Test
    fun `stale data blocks readiness with an ACTION_NEEDED connected line`() {
        val result = success(newestSampleAt = NOW.minus(java.time.Duration.ofDays(2)))

        val report = buildSuccessReport(result, NOW, phoneLines = emptyList())

        assertFalse(report.isReady)
        val connectedLine = report.lines.single { it.text.startsWith("Connected.") }
        assertEquals(SetupCheckLineSeverity.ACTION_NEEDED, connectedLine.severity)
        assertTrue(connectedLine.text.contains("stale"))
    }

    @Test
    fun `no heart-rate samples at all is reported plainly, not as a null crash`() {
        val result = success(newestSampleAt = null)

        val report = buildSuccessReport(result, NOW, phoneLines = emptyList())

        assertFalse(report.isReady)
        assertTrue(report.lines.single { it.text.startsWith("Connected.") }.text.contains("no recent heart-rate samples"))
    }

    @Test
    fun `the phone-alarm readiness lines are appended after the connected line, unchanged`() {
        val phoneLines = listOf(SetupCheckLine("Notifications are enabled.", SetupCheckLineSeverity.INFO))

        val report = buildSuccessReport(success(newestSampleAt = NOW), NOW, phoneLines = phoneLines)

        assertEquals(phoneLines.single(), report.lines.last())
    }

    private fun success(newestSampleAt: Instant?) = BandDataResult.Success(
        segments = List(3) { com.nikita.sleepcycle.engine.SleepSegment(NOW.minusSeconds(3600), NOW.minusSeconds(1800), com.nikita.sleepcycle.engine.SegmentKind.LIGHT) },
        newestSampleAt = newestSampleAt,
        exportFileModifiedAt = NOW
    )
}
