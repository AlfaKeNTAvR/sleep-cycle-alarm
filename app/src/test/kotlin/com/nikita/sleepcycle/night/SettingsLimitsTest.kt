package com.nikita.sleepcycle.night

// File purpose: the Settings screen's limits (owner spec, 2026-10-02) - what each stepper offers, that stored
// values outside them read back inside, and that every offered "After the alarm" value is a timing the engine
// accepts and actually uses.

import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.computeAlarmPlan
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

class SettingsLimitsTest {

    @Test
    fun `defaults are the agreed ones`() {
        assertEquals(AfterAlarmSettings(nudgeMinutes = 10, napMinutes = 20), AfterAlarmSettings())
        assertEquals(BedtimeAudioSettings(fadeEnabled = true, fadeStartPercent = 25, pauseWhenAsleep = true), BedtimeAudioSettings())
        assertEquals(SleepRatingSettings(enabled = true, askAgainLater = true, askAt = LocalTime.of(15, 0)), SleepRatingSettings())
    }

    @Test
    fun `the nudge steps five minutes at a time between 5 and 15`() {
        assertEquals(15, stepSetting(SettingStepper.NUDGE_MINUTES, 10, +1))
        assertEquals(5, stepSetting(SettingStepper.NUDGE_MINUTES, 10, -1))
        assertEquals(15, stepSetting(SettingStepper.NUDGE_MINUTES, 15, +1))
        assertEquals(5, stepSetting(SettingStepper.NUDGE_MINUTES, 5, -1))
    }

    @Test
    fun `the nap length steps five minutes at a time between 10 and 30`() {
        assertEquals(25, stepSetting(SettingStepper.NAP_MINUTES, 20, +1))
        assertEquals(30, stepSetting(SettingStepper.NAP_MINUTES, 30, +1))
        assertEquals(10, stepSetting(SettingStepper.NAP_MINUTES, 10, -1))
    }

    @Test
    fun `the fade starting volume steps five percent at a time between 10 and 50`() {
        assertEquals(30, stepSetting(SettingStepper.FADE_START_PERCENT, 25, +1))
        assertEquals(50, stepSetting(SettingStepper.FADE_START_PERCENT, 50, +1))
        assertEquals(10, stepSetting(SettingStepper.FADE_START_PERCENT, 10, -1))
    }

    @Test
    fun `a stepper's button is offered only while there is room in that direction`() {
        assertFalse(canStepSetting(SettingStepper.NUDGE_MINUTES, 15, +1))
        assertTrue(canStepSetting(SettingStepper.NUDGE_MINUTES, 15, -1))
        assertFalse(canStepSetting(SettingStepper.FADE_START_PERCENT, 10, -1))
    }

    @Test
    fun `the fade runs only while media is also paused on falling asleep`() {
        assertTrue(shouldFadeMedia(BedtimeAudioSettings(pauseWhenAsleep = true, fadeEnabled = true)))
        assertFalse(shouldFadeMedia(BedtimeAudioSettings(pauseWhenAsleep = false, fadeEnabled = true)))
        assertFalse(shouldFadeMedia(BedtimeAudioSettings(pauseWhenAsleep = true, fadeEnabled = false)))
    }

    @Test
    fun `a stored value outside the limits reads back at the nearest limit`() {
        assertEquals(5, clampSetting(SettingStepper.NUDGE_MINUTES, 2))
        assertEquals(30, clampSetting(SettingStepper.NAP_MINUTES, 90))
        assertEquals(50, clampSetting(SettingStepper.FADE_START_PERCENT, 100))
        assertEquals(12, clampSetting(SettingStepper.NUDGE_MINUTES, 12))
    }

    @Test
    fun `the night's after-alarm settings become the engine's nudge and nap timing`() {
        val config = resolveEngineConfig(DebugOptions(), AfterAlarmSettings(nudgeMinutes = 7, napMinutes = 30))
        assertEquals(Duration.ofMinutes(7), config.outOfBedDelay)
        assertEquals(Duration.ofMinutes(30), config.napLength)
    }

    @Test
    fun `the engine accepts every nudge and nap length the Settings screen offers`() {
        val now = Instant.parse("2026-10-02T23:00:00Z")
        for (nudge in SettingStepper.NUDGE_MINUTES.range) for (nap in SettingStepper.NAP_MINUTES.range step SettingStepper.NAP_MINUTES.step) {
            val config = resolveEngineConfig(DebugOptions(), AfterAlarmSettings(nudge, nap))
            assertDoesNotThrow({
                computeAlarmPlan(
                    emptyList(), NightSettings(deadline = null, pickedCycles = 5), now, morningAlarmAt = null, ZoneOffset.UTC, config,
                    wakeAlarmFiredAt = null, napAlarmsUsed = 0, lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
                )
            }, "nudge $nudge min, nap $nap min")
        }
    }
}
