package com.nikita.sleepcycle.ui.state

// File purpose: PER-150 - the "No alarm" chip on Before bed, after the 3, 4, 5, 6 cycle chips. Picking it turns
// the "Wake me by" deadline off (the two contradict each other); turning the deadline back on moves the
// selection back to the longest cycle chip that fits. Goes through the same pure settings transitions and
// buildUiState hop NightViewModel uses.

import com.nikita.sleepcycle.night.AppSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalTime

class NoAlarmBeforeBedTest {
    private val now = instant("2026-09-30T23:00")

    private fun beforeBed(appSettings: AppSettings): BeforeBedUiState = buildUiState(
        appSettings = appSettings,
        nightState = null,
        engineView = null,
        now = now,
        zone = testZone,
        permissionStatus = testPermissionStatus(),
        gadgetbridgeInstalled = true,
        screen = Screen.BeforeBed,
        connectionTest = ConnectionTestState.Idle,
        nightLogFiles = emptyList(),
        nightLogRatings = emptyMap(),
        confirmingEndNight = false,
        showingMorningReport = false,
        morningReportEndedAt = null,
        pendingFollowUp = null,
        errorMessage = null,
    ).beforeBed

    @Test fun `a normal pick shows the cycle chip selected and No alarm not`() {
        val screen = beforeBed(testAppSettings(pickedCycles = 5))

        assertFalse(screen.noAlarmSelected)
        assertEquals(listOf(5), screen.sleepLengthOptions.filter { it.selected }.map { it.cycles })
    }

    @Test fun `picking No alarm selects it alone and turns the deadline off`() {
        val picked = withNoAlarmPicked(testAppSettings(deadlineEnabled = true, lastDeadline = LocalTime.of(8, 0)))
        val screen = beforeBed(picked)

        assertTrue(screen.noAlarmSelected)
        assertFalse(screen.deadlineEnabled)
        assertTrue(screen.sleepLengthOptions.none { it.selected }, "no cycle chip is selected alongside No alarm")
        assertTrue(screen.sleepLengthOptions.all { it.available }, "with the deadline off every length fits again")
    }

    @Test fun `picking a cycle chip after No alarm selects that chip`() {
        val picked = withSleepLengthPicked(withNoAlarmPicked(testAppSettings()), 4)
        val screen = beforeBed(picked)

        assertFalse(screen.noAlarmSelected)
        assertEquals(listOf(4), screen.sleepLengthOptions.filter { it.selected }.map { it.cycles })
    }

    @Test fun `turning the deadline back on moves the selection to the longest cycle chip that fits`() {
        // Deadline 05:00: from about 23:15 asleep, 3 cycles end 03:45, 4 cycles end 05:15 - only 3 fits.
        val noAlarm = withNoAlarmPicked(testAppSettings(pickedCycles = 6, lastDeadline = LocalTime.of(5, 0)))
        val switched = withDeadlineSwitched(noAlarm, enabled = true, now = now, zone = testZone)
        val screen = beforeBed(switched)

        assertFalse(screen.noAlarmSelected)
        assertTrue(screen.deadlineEnabled)
        assertEquals(listOf(3), screen.sleepLengthOptions.filter { it.selected }.map { it.cycles })
    }

    @Test fun `turning the deadline on with No alarm picked and a late deadline picks the longest chip, 6 cycles`() {
        val noAlarm = withNoAlarmPicked(testAppSettings(pickedCycles = 4, lastDeadline = LocalTime.of(10, 0)))

        assertEquals(6, withDeadlineSwitched(noAlarm, enabled = true, now = now, zone = testZone).pickedCycles)
    }

    @Test fun `turning the deadline on a normal night keeps the picked chip`() {
        val normal = testAppSettings(pickedCycles = 4, lastDeadline = LocalTime.of(10, 0))

        assertEquals(4, withDeadlineSwitched(normal, enabled = true, now = now, zone = testZone).pickedCycles)
    }

    @Test fun `turning the deadline off leaves No alarm unpicked`() {
        val normal = testAppSettings(deadlineEnabled = true, pickedCycles = 4)
        val switched = withDeadlineSwitched(normal, enabled = false, now = now, zone = testZone)

        assertFalse(switched.deadlineEnabled)
        assertFalse(switched.noAlarm)
    }
}
