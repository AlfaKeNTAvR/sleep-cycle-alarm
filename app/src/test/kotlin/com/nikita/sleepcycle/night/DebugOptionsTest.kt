package com.nikita.sleepcycle.night

// File purpose: resolveDebugOptions is the ONE seam that decides whether debug options can take effect -
// this is the test that a release build can never run a simulated night no matter what got left in DataStore.
// Also A1: which switches are live (for the warning banner) and the idle-reset rule.

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class DebugOptionsTest {
    private val allOn = DebugOptions(simulatedBandData = true, fastNight = true, bandCommandMode = BandCommandMode.DRY_RUN)

    @Test
    fun `a release build always resolves to all-off, regardless of what is stored`() {
        assertEquals(DebugOptions(), resolveDebugOptions(isDebugBuild = false, stored = allOn))
    }

    @Test
    fun `a debug build resolves to exactly what is stored`() {
        assertEquals(allOn, resolveDebugOptions(isDebugBuild = true, stored = allOn))
    }

    @Test
    fun `a release build resolves an all-off stored value to all-off too`() {
        assertEquals(DebugOptions(), resolveDebugOptions(isDebugBuild = false, stored = DebugOptions()))
    }

    @Test
    fun `isAnyEnabled is false when every switch is off`() {
        assertEquals(false, DebugOptions().isAnyEnabled)
    }

    @Test
    fun `isAnyEnabled is true when only simulated band data is on`() {
        assertEquals(true, DebugOptions(simulatedBandData = true).isAnyEnabled)
    }

    @Test
    fun `isAnyEnabled is true when only fast night is on`() {
        assertEquals(true, DebugOptions(fastNight = true).isAnyEnabled)
    }

    @Test
    fun `isAnyEnabled is true when only dry-run band commands is on`() {
        assertEquals(true, DebugOptions(bandCommandMode = BandCommandMode.DRY_RUN).isAnyEnabled)
    }

    @Test
    fun `activeDebugSwitches is empty when every switch is off`() {
        assertEquals(emptyList<ActiveDebugSwitch>(), activeDebugSwitches(DebugOptions()))
    }

    @Test
    fun `activeDebugSwitches names simulated band data alone`() {
        assertEquals(listOf(ActiveDebugSwitch.SIMULATED_SLEEP_DATA), activeDebugSwitches(DebugOptions(simulatedBandData = true)))
    }

    @Test
    fun `activeDebugSwitches names dry-run band commands alone`() {
        assertEquals(listOf(ActiveDebugSwitch.BAND_COMMANDS_NOT_SENT), activeDebugSwitches(DebugOptions(bandCommandMode = BandCommandMode.DRY_RUN)))
    }

    @Test
    fun `activeDebugSwitches names fast night alone`() {
        assertEquals(listOf(ActiveDebugSwitch.FAST_NIGHT), activeDebugSwitches(DebugOptions(fastNight = true)))
    }

    @Test
    fun `activeDebugSwitches names every switch that is on, in a stable order`() {
        assertEquals(
            listOf(ActiveDebugSwitch.SIMULATED_SLEEP_DATA, ActiveDebugSwitch.BAND_COMMANDS_NOT_SENT, ActiveDebugSwitch.FAST_NIGHT),
            activeDebugSwitches(allOn)
        )
    }

    @Test
    fun `no auto-reset when the switches were never changed`() {
        assertFalse(shouldAutoResetDebugOptions(lastChangedAt = null, now = Instant.parse("2026-09-17T20:00:00Z")))
    }

    @Test
    fun `no auto-reset before the idle threshold has passed`() {
        val changedAt = Instant.parse("2026-09-17T15:00:00Z")
        val now = changedAt.plus(DEBUG_OPTIONS_IDLE_RESET).minus(Duration.ofMinutes(1))
        assertFalse(shouldAutoResetDebugOptions(changedAt, now))
    }

    @Test
    fun `auto-resets exactly at the idle threshold`() {
        val changedAt = Instant.parse("2026-09-17T15:00:00Z")
        val now = changedAt.plus(DEBUG_OPTIONS_IDLE_RESET)
        assertTrue(shouldAutoResetDebugOptions(changedAt, now))
    }

    @Test
    fun `auto-resets well past the idle threshold`() {
        val changedAt = Instant.parse("2026-09-17T15:00:00Z")
        val now = changedAt.plus(Duration.ofHours(9))
        assertTrue(shouldAutoResetDebugOptions(changedAt, now))
    }
}
