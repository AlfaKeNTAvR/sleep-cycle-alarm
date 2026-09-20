package com.nikita.sleepcycle.night

// File purpose: resolveEngineConfig must supply a real EngineConfig by default and a fast one only when
// fastNight is on, and the fast one must be legal per the engine's own validateConfig - checked indirectly
// through computeAlarmPlan, since validateConfig itself is internal to :engine and not visible from :app.

import com.nikita.sleepcycle.alarm.AUTO_STOP_AFTER
import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.computeAlarmPlan
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class EngineConfigResolutionTest {
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-09-17T00:00:00Z")

    @Test
    fun `resolves to the default EngineConfig when fast night is off`() {
        assertEquals(EngineConfig(), resolveEngineConfig(DebugOptions(fastNight = false)))
    }

    @Test
    fun `resolves to a shortened EngineConfig when fast night is on`() {
        val config = resolveEngineConfig(DebugOptions(fastNight = true))
        assertNotEquals(EngineConfig().cycleLength, config.cycleLength)
        assertTrue(config.cycleLength < EngineConfig().cycleLength, "the fast-night cycle length must be shorter than the real one")
    }

    @Test
    fun `D4 the fast night also shortens outOfBedDelay, so a debug tester sees the nudge without a 10 min wait`() {
        val config = resolveEngineConfig(DebugOptions(fastNight = true))
        assertNotEquals(EngineConfig().outOfBedDelay, config.outOfBedDelay)
        assertTrue(config.outOfBedDelay < EngineConfig().outOfBedDelay, "the fast-night out-of-bed delay must be shorter than the real one")
    }

    @Test
    fun `every debug option combination still resolves the same EngineConfig based on fastNight alone`() {
        assertEquals(
            resolveEngineConfig(DebugOptions(fastNight = true)),
            resolveEngineConfig(DebugOptions(simulatedBandData = true, fastNight = true))
        )
    }

    @Test
    fun `the fast EngineConfig satisfies the engine's own validateConfig, exercised through computeAlarmPlan`() {
        val config = resolveEngineConfig(DebugOptions(fastNight = true))
        val settings = NightSettings(deadline = null, pickedCycles = 4)
        assertDoesNotThrow {
            computeAlarmPlan(
                emptyList(), settings, now, morningAlarmAt = null, zone, config,
                wakeAlarmFiredAt = null, napAlarmsUsed = 0, lastNapAlarmFiredAt = null
            )
        }
    }

    @Test
    fun `the real EngineConfig also satisfies validateConfig, exercised through computeAlarmPlan`() {
        val config = resolveEngineConfig(DebugOptions(fastNight = false))
        val settings = NightSettings(deadline = null, pickedCycles = 4)
        assertDoesNotThrow {
            computeAlarmPlan(
                emptyList(), settings, now, morningAlarmAt = null, zone, config,
                wakeAlarmFiredAt = null, napAlarmsUsed = 0, lastNapAlarmFiredAt = null
            )
        }
    }

    // ---- F1: outOfBedDelay vs AlarmRingService's own auto-stop must never coincide again ------------------

    @Test
    fun `F1 the real out-of-bed delay stays strictly longer than the real ring auto-stop`() {
        val config = resolveEngineConfig(DebugOptions(fastNight = false))
        assertTrue(
            config.outOfBedDelay > config.ringAutoStopAfter,
            "outOfBedDelay (${config.outOfBedDelay}) must outlast ringAutoStopAfter (${config.ringAutoStopAfter}), or the nudge can fire into a service about to auto-stop"
        )
    }

    @Test
    fun `F1 the fast-night out-of-bed delay also stays strictly longer than its own ring auto-stop`() {
        val config = resolveEngineConfig(DebugOptions(fastNight = true))
        assertTrue(
            config.outOfBedDelay > config.ringAutoStopAfter,
            "the fast-night outOfBedDelay (${config.outOfBedDelay}) must outlast its ringAutoStopAfter (${config.ringAutoStopAfter})"
        )
    }

    @Test
    fun `F1 AlarmRingService's real fallback AUTO_STOP_AFTER agrees with the real EngineConfig, not a second drifting constant`() {
        assertEquals(resolveEngineConfig(DebugOptions(fastNight = false)).ringAutoStopAfter, AUTO_STOP_AFTER)
    }

    /**
     * Pins the property that makes a nap observable on the desk demo instead of needing a pull-forward (D8)
     * the instant sleep resumes: a nap detected up to one sync delay late (the tick that notices "back asleep"
     * might not land until a whole sync delay after it happened) must still have its own alarm
     * (onset + napLength) at least minAlarmLead ahead of that late tick's `now`, i.e.
     * napLength - syncDelay >= minAlarmLead. Uses frequentSyncDelay, the cadence actually in effect near an
     * alarm; normalSyncDelay equals it in the fast config, so either would give the same answer.
     */
    @Test
    fun `the fast nap length leaves at least one sync delay's worth of lead before its own alarm`() {
        val config = resolveEngineConfig(DebugOptions(fastNight = true))
        assertTrue(config.napLength.minus(config.frequentSyncDelay) >= config.minAlarmLead)
    }

    // ---- H7.1: the out-of-bed nudge is now 15 min, and the fast-night value stays proportionate ------------

    @Test
    fun `H7_1 the real out-of-bed delay is 15 minutes`() {
        assertEquals(Duration.ofMinutes(15), EngineConfig().outOfBedDelay)
    }

    @Test
    fun `H7_1 the fast-night out-of-bed delay is still shorter than the real one and still legal per validateConfig`() {
        val fast = resolveEngineConfig(DebugOptions(fastNight = true))
        assertTrue(fast.outOfBedDelay < EngineConfig().outOfBedDelay, "the fast-night out-of-bed delay must stay shorter than the real 15 min")
        assertTrue(fast.outOfBedDelay > fast.ringAutoStopAfter, "F1's own margin must still hold at the new value")
    }

    // ---- H7.3: preNudgeCheckLead must land strictly between the nudge being armed and it firing -------------

    @Test
    fun `H7_3 the real preNudgeCheckLead stays strictly under the real outOfBedDelay`() {
        val config = resolveEngineConfig(DebugOptions(fastNight = false))
        assertTrue(
            config.outOfBedDelay > config.preNudgeCheckLead,
            "outOfBedDelay (${config.outOfBedDelay}) must outlast preNudgeCheckLead (${config.preNudgeCheckLead})"
        )
    }

    @Test
    fun `H7_3 the fast-night preNudgeCheckLead also stays strictly under its own outOfBedDelay`() {
        val config = resolveEngineConfig(DebugOptions(fastNight = true))
        assertTrue(
            config.outOfBedDelay > config.preNudgeCheckLead,
            "the fast-night outOfBedDelay (${config.outOfBedDelay}) must outlast its preNudgeCheckLead (${config.preNudgeCheckLead})"
        )
    }
}
