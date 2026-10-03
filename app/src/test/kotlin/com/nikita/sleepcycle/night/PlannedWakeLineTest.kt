package com.nikita.sleepcycle.night

// File purpose: the tracking notification's planned-wake line follows the phone's time zone (round2.md #9,
// ISSUES.md #15). The zone used to be captured once at class load, so after a time zone change mid-night the
// notification kept showing the old zone's time until the process restarted.

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.TimeZone

class PlannedWakeLineTest {
    private val originalZone: TimeZone = TimeZone.getDefault()

    @AfterEach
    fun restoreZone() = TimeZone.setDefault(originalZone)

    @Test
    fun `the planned wake time is shown in the zone the phone is in now, not the one it started in`() {
        val wakeAt = Instant.parse("2026-10-03T04:30:00Z")
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertEquals("Planned wake time: 04:30", plannedWakeLine(wakeAt))

        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
        assertEquals("Planned wake time: 07:30", plannedWakeLine(wakeAt))
    }

    @Test
    fun `before the first sync there is no planned wake time yet`() {
        assertEquals("Waiting for first sync", plannedWakeLine(null))
    }
}
