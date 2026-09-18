package com.nikita.sleepcycle.night

// File purpose: buildSuccessReport's smart-wakeup-slot line - shown whenever a smart-wakeup slot is still
// disabled and untitled (poachable by Gadgetbridge's own picker), naming the real slot and blocking
// isReady, per the real export on the owner's Honor Band 5 (slot 0: disabled, untitled, SMART_WAKEUP=1,
// window 60).

import com.nikita.sleepcycle.bridge.BandAlarmSlot
import com.nikita.sleepcycle.bridge.BandDataResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

private val NOW = Instant.parse("2026-09-17T00:00:00Z")

class SetupCheckTest {
    @Test
    fun `a poachable smart-wakeup slot adds an ACTION_NEEDED line naming the slot and blocks readiness`() {
        val slots = listOf(
            slot(position = 0, enabled = false, hour = 5, minute = 29, title = null, smartWakeup = true, smartWakeupWindowMinutes = 60),
            slot(position = 1, enabled = false, hour = 3, minute = 17, title = null),
            slot(position = 2, enabled = false, hour = 7, minute = 0, title = "Alarm")
        )

        val report = buildSuccessReport(success(slots), NOW, phoneLines = emptyList())

        val smartLine = report.lines.singleOrNull { it.text.contains("Band alarm 1") }
        assertTrue(smartLine != null, "expected a line naming Band alarm 1")
        assertEquals(SetupCheckLineSeverity.ACTION_NEEDED, smartLine!!.severity)
        assertTrue(smartLine.text.contains("smart alarm"))
        assertTrue(smartLine.text.contains("leave it switched off"))
        assertFalse(report.isReady, "a poachable smart slot must block isReady")
    }

    @Test
    fun `a parked smart-wakeup slot - titled and disabled - adds no ACTION_NEEDED line and does not block readiness`() {
        val slots = listOf(
            slot(position = 0, enabled = false, hour = 0, minute = 0, title = "Smart", smartWakeup = true, smartWakeupWindowMinutes = 60),
            slot(position = 1, enabled = false, hour = 3, minute = 17, title = null),
            slot(position = 2, enabled = false, hour = 7, minute = 0, title = null)
        )

        val report = buildSuccessReport(success(slots), NOW, phoneLines = emptyList())

        assertTrue(report.lines.none { it.severity == SetupCheckLineSeverity.ACTION_NEEDED })
        assertTrue(report.isReady)
    }

    @Test
    fun `exactly one usable slot is ready, with a non-blocking line about freeing a second`() {
        val slots = listOf(
            slot(position = 0, enabled = false, hour = 0, minute = 0, title = "Smart", smartWakeup = true, smartWakeupWindowMinutes = 60),
            slot(position = 1, enabled = false, hour = 3, minute = 17, title = null),
            slot(position = 2, enabled = false, hour = 7, minute = 0, title = "Alarm")
        )

        val report = buildSuccessReport(success(slots), NOW, phoneLines = emptyList())

        assertTrue(report.isReady, "one usable slot is enough to run a night")
        val adviceLine = report.lines.singleOrNull { it.text.contains("Only one usable band alarm") }
        assertTrue(adviceLine != null, "expected the one-slot advice line")
        assertEquals(SetupCheckLineSeverity.INFO, adviceLine!!.severity, "advice, never a blocker")
        assertTrue(adviceLine.text.contains("clear its title"), "must say how to free a second alarm")
    }

    @Test
    fun `no usable slot at all still blocks readiness`() {
        val slots = listOf(
            slot(position = 0, enabled = false, hour = 0, minute = 0, title = "Smart", smartWakeup = true, smartWakeupWindowMinutes = 60),
            slot(position = 1, enabled = true, hour = 6, minute = 0, title = "Work"),
            slot(position = 2, enabled = false, hour = 7, minute = 0, title = "Alarm")
        )

        val report = buildSuccessReport(success(slots), NOW, phoneLines = emptyList())

        assertFalse(report.isReady)
        assertTrue(report.lines.any { it.severity == SetupCheckLineSeverity.ACTION_NEEDED && it.text.contains("clear the title of 1 more") })
    }

    @Test
    fun `two usable slots add no one-slot advice line`() {
        val slots = listOf(
            slot(position = 0, enabled = false, hour = 3, minute = 17, title = null),
            slot(position = 1, enabled = false, hour = 7, minute = 0, title = null)
        )

        val report = buildSuccessReport(success(slots), NOW, phoneLines = emptyList())

        assertTrue(report.lines.none { it.text.contains("Only one usable band alarm") })
    }

    @Test
    fun `no smart-wakeup slots at all adds no smart-alarm line`() {
        val slots = listOf(
            slot(position = 0, enabled = false, hour = 0, minute = 0, title = null),
            slot(position = 1, enabled = false, hour = 0, minute = 0, title = null)
        )

        val report = buildSuccessReport(success(slots), NOW, phoneLines = emptyList())

        assertTrue(report.lines.none { it.text.contains("smart alarm") })
    }

    private fun success(slots: List<BandAlarmSlot>) = BandDataResult.Success(
        segments = emptyList(),
        newestSampleAt = NOW,
        bandAlarms = slots,
        exportFileModifiedAt = NOW
    )

    private fun slot(
        position: Int,
        enabled: Boolean,
        hour: Int,
        minute: Int,
        title: String?,
        smartWakeup: Boolean = false,
        smartWakeupWindowMinutes: Int? = null
    ) = BandAlarmSlot(position, enabled, hour, minute, title, smartWakeup, repetition = 0, smartWakeupWindowMinutes = smartWakeupWindowMinutes)
}
