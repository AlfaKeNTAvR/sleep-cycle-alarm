package com.nikita.sleepcycle.night

// File purpose: C4 - FINISHED normally stops ticking outright, but a dismissal queued on the terminal tick
// can never be verified by read-back unless ticking continues a bounded number of extra times.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.EngineConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FinishedCleanupTickTest {
    private val config = EngineConfig()

    @Test
    fun `the count is 0 whenever the mode is not FINISHED`() {
        assertEquals(0, nextFinishedCleanupTickCount(AlarmMode.OVERDUE, AlarmMode.FULL_CYCLES, previousCount = 5))
    }

    @Test
    fun `the count is 0 on the first FINISHED tick, even with a stale previous count`() {
        assertEquals(0, nextFinishedCleanupTickCount(AlarmMode.FINISHED, AlarmMode.OVERDUE, previousCount = 5))
    }

    @Test
    fun `the count increments on each further FINISHED tick`() {
        assertEquals(1, nextFinishedCleanupTickCount(AlarmMode.FINISHED, AlarmMode.FINISHED, previousCount = 0))
        assertEquals(6, nextFinishedCleanupTickCount(AlarmMode.FINISHED, AlarmMode.FINISHED, previousCount = 5))
    }

    @Test
    fun `no cleanup delay when nothing is pending dismissal`() {
        assertNull(finishedCleanupSyncDelay(emptySet(), cleanupTicksUsed = 0, config))
    }

    @Test
    fun `a cleanup delay of the normal sync cadence while a dismissal is pending and the bound is not reached`() {
        assertEquals(config.normalSyncDelay, finishedCleanupSyncDelay(setOf("SCA-A"), cleanupTicksUsed = 0, config))
        assertEquals(config.normalSyncDelay, finishedCleanupSyncDelay(setOf("SCA-A"), cleanupTicksUsed = MAX_FINISHED_CLEANUP_TICKS - 1, config))
    }

    @Test
    fun `no more cleanup delay once the bound is reached, even with a dismissal still pending`() {
        assertNull(finishedCleanupSyncDelay(setOf("SCA-A"), cleanupTicksUsed = MAX_FINISHED_CLEANUP_TICKS, config))
        assertNull(finishedCleanupSyncDelay(setOf("SCA-A"), cleanupTicksUsed = MAX_FINISHED_CLEANUP_TICKS + 3, config))
    }
}
