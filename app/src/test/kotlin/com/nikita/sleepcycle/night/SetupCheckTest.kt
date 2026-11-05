package com.nikita.sleepcycle.night

// File purpose: buildSuccessReport's data-freshness line and its readiness verdict. D2/D7 removed the whole
// band-alarm-slot side of the setup check (usable slots, other alarms, smart-wakeup poaching, conflicting
// titles) along with the rest of the band alarm machinery - there is no band alarm to set, so none of that
// applies to setup anymore. [phoneLines] is always passed through unchanged; its own content is
// AlarmNotificationReadiness's concern, not this file's.

import com.nikita.sleepcycle.alarm.AlarmNotificationReadiness
import com.nikita.sleepcycle.bridge.BandDataResult
import com.nikita.sleepcycle.bridge.StaleReason
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

        val report = buildSuccessReport(result, NOW, phoneLines = emptyList(), heartRateCheck = BedtimeHeartRateCheck.WARN_ONLY)

        assertTrue(report.isReady)
        val connectedLine = report.lines.single { it.text.startsWith("Connected.") }
        assertEquals(SetupCheckLineSeverity.INFO, connectedLine.severity)
        assertTrue(connectedLine.text.contains("3 sleep segments"))
    }

    @Test
    fun `stale data blocks readiness with an ACTION_NEEDED connected line`() {
        val result = success(newestSampleAt = NOW.minus(java.time.Duration.ofDays(2)))

        val report = buildSuccessReport(result, NOW, phoneLines = emptyList(), heartRateCheck = BedtimeHeartRateCheck.WARN_ONLY)

        assertFalse(report.isReady)
        val connectedLine = report.lines.single { it.text.startsWith("Connected.") }
        assertEquals(SetupCheckLineSeverity.ACTION_NEEDED, connectedLine.severity)
        assertTrue(connectedLine.text.contains("stale"))
    }

    @Test
    fun `no heart-rate samples at all is reported plainly, not as a null crash`() {
        val result = success(newestSampleAt = null)

        val report = buildSuccessReport(result, NOW, phoneLines = emptyList(), heartRateCheck = BedtimeHeartRateCheck.WARN_ONLY)

        assertFalse(report.isReady)
        assertTrue(report.lines.single { it.text.startsWith("Connected.") }.text.contains("no recent heart-rate samples"))
    }

    @Test
    fun `the phone-alarm readiness lines are appended after the connected line, unchanged`() {
        val phoneLines = listOf(SetupCheckLine("Notifications are enabled.", SetupCheckLineSeverity.INFO))

        val report = buildSuccessReport(success(newestSampleAt = NOW), NOW, phoneLines = phoneLines, heartRateCheck = BedtimeHeartRateCheck.WARN_ONLY)

        assertEquals(phoneLines.single(), report.lines.last())
    }

    // ISSUES.md #4, owner decision 2026-10-03: a low alarm volume (under half its range) is a warning in Setup.
    @Test
    fun `a low alarm volume is a warning line that names the volume, and a sound one adds no warning`() {
        val ready = AlarmNotificationReadiness(
            canUseFullScreenIntent = true, notificationsEnabled = true, alarmChannelBlocked = false, alarmChannelDowngraded = false,
            alarmVolume = 3, alarmVolumeMax = 7
        )
        val low = phoneAlarmReadinessLines(ready).single { it.text.contains("alarm volume", ignoreCase = true) }
        assertEquals(SetupCheckLineSeverity.WARNING, low.severity)
        assertTrue(low.text.contains("3 of 7"), low.text)

        val loud = phoneAlarmReadinessLines(ready.copy(alarmVolume = 4))
        assertTrue(loud.none { it.severity != SetupCheckLineSeverity.INFO }, loud.toString())
    }

    // PER-130: 2026-11-02 - the sync succeeded and heart-rate rows kept arriving, but every one carried -1.
    // P6: that failure is now the "Require a reading" choice in Settings.
    @Test
    fun `with Require a reading, rows arriving with no real heart rate fail the test with the not-measuring line and its fix`() {
        val result = success(newestSampleAt = null, newestHeartRateRowAt = NOW.minusSeconds(60))

        val report = buildSuccessReport(result, NOW, phoneLines = emptyList(), heartRateCheck = BedtimeHeartRateCheck.REQUIRE_READING)

        assertFalse(report.isReady)
        assertEquals(StaleReason.NO_HEART_RATE_VALUES, report.staleReason)
        val bandLine = report.lines.first()
        assertEquals(SetupCheckLineSeverity.ACTION_NEEDED, bandLine.severity)
        assertEquals(
            "Band connected but not measuring heart rate. In Gadgetbridge band settings, switch \"Enable automatic heart rate measuring\" off and on.",
            bandLine.text
        )
    }

    // P6 (owner decision, 2026-11-05): at bedtime the band often has no recent reading even with automatic
    // heart rate on, so by default the test passes with a note; the night warns if none arrives.
    @Test
    fun `with Warn only, rows arriving with no real heart rate pass the test with a warning note`() {
        val result = success(newestSampleAt = null, newestHeartRateRowAt = NOW.minusSeconds(60))

        val report = buildSuccessReport(result, NOW, phoneLines = emptyList(), heartRateCheck = BedtimeHeartRateCheck.WARN_ONLY)

        assertTrue(report.isReady)
        assertEquals(StaleReason.NO_HEART_RATE_VALUES, report.staleReason, "kept for setup.jsonl")
        val bandLine = report.lines.first()
        assertEquals(SetupCheckLineSeverity.WARNING, bandLine.severity)
        assertEquals("No heart-rate reading yet. If none arrives after you start, the app will warn you.", bandLine.text)
    }

    @Test
    fun `with Warn only, a band that brings nothing new still fails`() {
        val report = buildSuccessReport(
            success(newestSampleAt = null, newestHeartRateRowAt = null), NOW, phoneLines = emptyList(), heartRateCheck = BedtimeHeartRateCheck.WARN_ONLY,
        )

        assertFalse(report.isReady)
        assertEquals(StaleReason.NO_NEW_SAMPLES, report.staleReason)
    }

    @Test
    fun `no rows at all keeps the plain stale line, not the not-measuring one`() {
        val report = buildSuccessReport(success(newestSampleAt = null, newestHeartRateRowAt = null), NOW, phoneLines = emptyList(), heartRateCheck = BedtimeHeartRateCheck.WARN_ONLY)

        assertEquals(StaleReason.NO_NEW_SAMPLES, report.staleReason)
        assertTrue(report.lines.first().text.startsWith("Connected."))
    }

    private fun success(newestSampleAt: Instant?, newestHeartRateRowAt: Instant? = newestSampleAt) = BandDataResult.Success(
        segments = List(3) { com.nikita.sleepcycle.engine.SleepSegment(NOW.minusSeconds(3600), NOW.minusSeconds(1800), com.nikita.sleepcycle.engine.SegmentKind.LIGHT) },
        newestSampleAt = newestSampleAt,
        exportFileModifiedAt = NOW,
        newestHeartRateRowAt = newestHeartRateRowAt
    )
}
