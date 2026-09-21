package com.nikita.sleepcycle.night

// File purpose: FIX2 (owner-reported, 2026-09-21) - the pure arithmetic behind NightOrchestrator.scheduleNextTick,
// extracted so the "never at or before decisionNow" invariant is JVM-testable without Android. See
// NightOrchestrator.nextTickAt's own doc for the tight-loop bug this replaces (booking the next tick from a
// tick's stale entry instant instead of the instant its own decision was actually made at).

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.AlarmPlan
import com.nikita.sleepcycle.engine.EngineConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class NextTickAtTest {
    private val decisionNow = Instant.parse("2026-09-17T23:00:00Z")
    private val config = EngineConfig()

    private fun plan(mode: AlarmMode, wakeAt: Instant?) =
        AlarmPlan(mode, wakeAt, 0, Instant.parse("2026-09-17T22:00:00Z"), false, "r")

    @Test
    fun `a FINISHED plan books nothing - the night is over`() {
        assertNull(nextTickAt(plan(AlarmMode.FINISHED, wakeAt = null), decisionNow, config))
    }

    @Test
    fun `a NAP plan books the frequent delay, strictly after decisionNow`() {
        val at = nextTickAt(plan(AlarmMode.NAP, decisionNow.plusSeconds(600)), decisionNow, config)
        assertEquals(decisionNow.plus(config.frequentSyncDelay), at)
        assertTrue(at!!.isAfter(decisionNow))
    }

    @Test
    fun `a FULL_CYCLES plan far from its own alarm books the normal delay, strictly after decisionNow`() {
        val at = nextTickAt(plan(AlarmMode.FULL_CYCLES, decisionNow.plus(config.cycleLength)), decisionNow, config)
        assertEquals(decisionNow.plus(config.normalSyncDelay), at)
        assertTrue(at!!.isAfter(decisionNow))
    }

    @Test
    fun `a FULL_CYCLES plan near its own alarm books the frequent delay, strictly after decisionNow`() {
        val at = nextTickAt(plan(AlarmMode.FULL_CYCLES, decisionNow.plusSeconds(60)), decisionNow, config)
        assertEquals(decisionNow.plus(config.frequentSyncDelay), at)
        assertTrue(at!!.isAfter(decisionNow))
    }

    @Test
    fun `never at or before decisionNow - the FIX2 invariant, across every non-FINISHED mode`() {
        for (mode in listOf(AlarmMode.FULL_CYCLES, AlarmMode.DEADLINE_ONLY, AlarmMode.NAP)) {
            val at = nextTickAt(plan(mode, decisionNow.plusSeconds(30)), decisionNow, config)
            assertTrue(at != null && at.isAfter(decisionNow), "mode $mode must book strictly after decisionNow, got $at")
        }
    }
}
