package com.nikita.sleepcycle.night

// File purpose: T7 - resolveEngineConfig must always return the real EngineConfig now, regardless of debug
// options (a fast debug night comes from warping AppClock instead - see SimulatedClockTest.kt/
// WarpedNightSequenceTest.kt), and that real EngineConfig must still be legal per the engine's own
// validateConfig - checked indirectly through computeAlarmPlan, since validateConfig itself is internal to
// :engine and not visible from :app.

import com.nikita.sleepcycle.alarm.AUTO_STOP_AFTER
import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.computeAlarmPlan
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class EngineConfigResolutionTest {
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-09-17T00:00:00Z")

    @Test
    fun `resolves to the real EngineConfig when every debug switch is off`() {
        assertEquals(EngineConfig(), resolveEngineConfig(DebugOptions(), AfterAlarmSettings()))
    }

    @Test
    fun `T7 resolves to the SAME real EngineConfig regardless of speed - a fast debug night warps the clock instead`() {
        val anchor = Instant.parse("2026-09-17T20:00:00Z")
        for (speed in SIMULATION_SPEEDS) {
            assertEquals(EngineConfig(), resolveEngineConfig(DebugOptions(warp = ClockWarp(speed, anchor, anchor)), AfterAlarmSettings()))
        }
    }

    @Test
    fun `T7 resolves to the same real EngineConfig regardless of simulatedBandData too`() {
        val anchor = Instant.parse("2026-09-17T20:00:00Z")
        assertEquals(EngineConfig(), resolveEngineConfig(DebugOptions(simulatedBandData = true), AfterAlarmSettings()))
        assertEquals(
            resolveEngineConfig(DebugOptions(simulatedBandData = false, warp = null), AfterAlarmSettings()),
            resolveEngineConfig(DebugOptions(simulatedBandData = true, warp = ClockWarp(600, anchor, anchor)), AfterAlarmSettings())
        )
    }

    @Test
    fun `the real EngineConfig satisfies the engine's own validateConfig, exercised through computeAlarmPlan`() {
        val config = resolveEngineConfig(DebugOptions(), AfterAlarmSettings())
        val settings = NightSettings(deadline = null, pickedCycles = 4)
        assertDoesNotThrow {
            computeAlarmPlan(
                emptyList(), settings, now, morningAlarmAt = null, zone, config,
                wakeAlarmFiredAt = null, napAlarmsUsed = 0, lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
            )
        }
    }

    // ---- F1: AlarmRingService's own auto-stop. The outOfBedDelay > ringAutoStopAfter rule is gone (owner spec,
    // 2026-10-02): the nudge is measured from the ring's end, and Settings offers nudges from 5 min. ---------

    @Test
    fun `F1 AlarmRingService's real fallback AUTO_STOP_AFTER agrees with the real EngineConfig, not a second drifting constant`() {
        assertEquals(resolveEngineConfig(DebugOptions(), AfterAlarmSettings()).ringAutoStopAfter, AUTO_STOP_AFTER)
    }

    // ---- P3 (owner spec, 2026-09-30) SUPERSEDES H7.1's 15 min: the out-of-bed nudge is 10 min after the ring ends -

    @Test
    fun `P3 the real out-of-bed delay is 10 minutes`() {
        assertEquals(Duration.ofMinutes(10), EngineConfig().outOfBedDelay)
    }

    // ---- H7.3: preNudgeCheckLead must land strictly between the nudge being armed and it firing -------------

    @Test
    fun `H7_3 the real preNudgeCheckLead stays strictly under the real outOfBedDelay`() {
        val config = resolveEngineConfig(DebugOptions(), AfterAlarmSettings())
        assertTrue(
            config.outOfBedDelay > config.preNudgeCheckLead,
            "outOfBedDelay (${config.outOfBedDelay}) must outlast preNudgeCheckLead (${config.preNudgeCheckLead})"
        )
    }
}
