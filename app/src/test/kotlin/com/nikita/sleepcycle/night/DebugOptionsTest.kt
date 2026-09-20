package com.nikita.sleepcycle.night

// File purpose: resolveDebugOptions is the ONE seam that decides whether debug options can take effect -
// this is the test that a release build can never run a simulated night no matter what got left in DataStore.
// Also A1: which switches are live (for the warning banner) and the idle-reset rule. U3: isAnyEnabled and
// activeDebugSwitches key off [DebugOptions.warp], not [DebugOptions.speed] - a jump at speed 1 counts as
// simulated time too.

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class DebugOptionsTest {
    private val anchorReal = Instant.parse("2026-09-17T20:00:00Z")
    private val anchorVirtual = Instant.parse("2026-09-18T03:00:00Z")
    private val speedWarp = ClockWarp(speed = 60, anchorReal = anchorReal, anchorVirtual = anchorReal)
    private val allOn = DebugOptions(simulatedBandData = true, warp = speedWarp)

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
    fun `isAnyEnabled is false when every switch is off and there is no warp`() {
        assertEquals(false, DebugOptions().isAnyEnabled)
    }

    @Test
    fun `isAnyEnabled is true when only simulated band data is on`() {
        assertEquals(true, DebugOptions(simulatedBandData = true).isAnyEnabled)
    }

    @Test
    fun `isAnyEnabled is true when a warp is active, even with speed left at 1 (U2's jump-at-1x state)`() {
        val jumpAtOneX = ClockWarp(speed = 1, anchorReal = anchorReal, anchorVirtual = anchorVirtual)
        assertTrue(DebugOptions(warp = jumpAtOneX).isAnyEnabled)
    }

    /**
     * V7 REPLACES this file's own older test here ("isAnyEnabled is false when speed is non-1 but no warp
     * object is carried... the field, not speed, is the source of truth"): that scenario - a non-1 speed with
     * no warp - used to be constructible at all, because `speed` was its own settable field alongside `warp`.
     * V7 deletes that field; `speed` is now DERIVED (`warp?.speed ?: 1`), so the disagreeing state the old test
     * defended against is no longer just unlikely, it is impossible to even construct - the compiler enforces
     * what that test used to check at runtime.
     */
    @Test
    fun `V7 speed is always derived from warp - there is no way to construct one that disagrees with the other`() {
        assertEquals(1, DebugOptions().speed)
        assertEquals(1, DebugOptions(simulatedBandData = true).speed)
        val warp = ClockWarp(speed = 600, anchorReal = anchorReal, anchorVirtual = anchorVirtual)
        assertEquals(600, DebugOptions(warp = warp).speed)
        assertEquals(warp.speed, DebugOptions(warp = warp).speed)
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
    fun `activeDebugSwitches names simulated time alone when a warp is active`() {
        assertEquals(listOf(ActiveDebugSwitch.SIMULATED_TIME), activeDebugSwitches(DebugOptions(warp = speedWarp)))
    }

    @Test
    fun `activeDebugSwitches names every switch that is on, in a stable order`() {
        assertEquals(
            listOf(ActiveDebugSwitch.SIMULATED_SLEEP_DATA, ActiveDebugSwitch.SIMULATED_TIME),
            activeDebugSwitches(allOn)
        )
    }

    // ---- U1: the speed selector and jump require simulated band data -------------------------------------

    @Test
    fun `the speed selector is allowed only while simulated band data is on`() {
        assertTrue(isSpeedSelectorAllowed(simulatedBandData = true))
        assertFalse(isSpeedSelectorAllowed(simulatedBandData = false))
    }

    @Test
    fun `the jump action requires simulated band data on AND no active night`() {
        assertTrue(isJumpToTimeAllowed(simulatedBandData = true, nightActive = false))
        assertFalse(isJumpToTimeAllowed(simulatedBandData = false, nightActive = false))
        assertFalse(isJumpToTimeAllowed(simulatedBandData = true, nightActive = true))
        assertFalse(isJumpToTimeAllowed(simulatedBandData = false, nightActive = true))
    }

    // ---- V4: turning simulated band data off (or on) is refused outright while a night is active ------------

    @Test
    fun `the simulated band data toggle is allowed only while no night is active`() {
        assertTrue(isSimulatedBandDataToggleAllowed(nightActive = false))
        assertFalse(isSimulatedBandDataToggleAllowed(nightActive = true))
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
