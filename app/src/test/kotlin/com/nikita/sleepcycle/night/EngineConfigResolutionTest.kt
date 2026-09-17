package com.nikita.sleepcycle.night

// File purpose: resolveEngineConfig must supply a real EngineConfig by default and a fast one only when
// fastNight is on, and the fast one must be legal per the engine's own validateConfig - checked indirectly
// through computeAlarmPlan, since validateConfig itself is internal to :engine and not visible from :app.

import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.computeAlarmPlan
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
    fun `every debug option combination still resolves the same EngineConfig based on fastNight alone`() {
        assertEquals(
            resolveEngineConfig(DebugOptions(fastNight = true)),
            resolveEngineConfig(DebugOptions(simulatedBandData = true, fastNight = true, bandCommandMode = BandCommandMode.DRY_RUN))
        )
    }

    @Test
    fun `the fast EngineConfig satisfies the engine's own validateConfig, exercised through computeAlarmPlan`() {
        val config = resolveEngineConfig(DebugOptions(fastNight = true))
        val settings = NightSettings(deadline = null, pickedCycles = 4, phoneBackupEnabled = false)
        assertDoesNotThrow { computeAlarmPlan(emptyList(), settings, now, previousPlan = null, zone, config) }
    }

    @Test
    fun `the real EngineConfig also satisfies validateConfig, exercised through computeAlarmPlan`() {
        val config = resolveEngineConfig(DebugOptions(fastNight = false))
        val settings = NightSettings(deadline = null, pickedCycles = 4, phoneBackupEnabled = false)
        assertDoesNotThrow { computeAlarmPlan(emptyList(), settings, now, previousPlan = null, zone, config) }
    }

    /**
     * Pins the property that makes a nap observable on the desk demo instead of landing on OVERDUE the
     * instant sleep resumes: a nap detected up to one sync delay late (the tick that notices "back asleep"
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
}
