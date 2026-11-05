package com.nikita.sleepcycle.night

// File purpose: P6 (owner decision, 2026-11-05) - the Settings choice for the bedtime heart-rate check: Warn only
// by default, stored and logged by its log value, and an unknown stored value reading back as the default.

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BedtimeHeartRateCheckTest {
    @Test
    fun `the default is Warn only`() {
        assertEquals(BedtimeHeartRateCheck.WARN_ONLY, settingsWithDefaults.heartRateCheck)
    }

    @Test
    fun `each choice reads back from its stored value`() {
        assertEquals(BedtimeHeartRateCheck.WARN_ONLY, bedtimeHeartRateCheckFromStored("warn_only"))
        assertEquals(BedtimeHeartRateCheck.REQUIRE_READING, bedtimeHeartRateCheckFromStored("require_reading"))
    }

    @Test
    fun `nothing stored, or a value this build does not know, reads as Warn only`() {
        assertEquals(BedtimeHeartRateCheck.WARN_ONLY, bedtimeHeartRateCheckFromStored(null))
        assertEquals(BedtimeHeartRateCheck.WARN_ONLY, bedtimeHeartRateCheckFromStored("strict"))
    }
}

/** The defaults an [AppSettings] built without the field gets. */
private val settingsWithDefaults = AppSettings(
    deviceMac = null, exportUri = null, lastDeadline = null, deadlineEnabled = false, pickedCycles = 5, lastSetupCheckPassedAt = null,
)
