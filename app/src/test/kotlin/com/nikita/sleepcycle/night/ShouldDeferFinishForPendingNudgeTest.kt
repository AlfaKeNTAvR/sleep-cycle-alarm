package com.nikita.sleepcycle.night

// File purpose: L2.1/L3.1 - the pure half of NightController.finishNightIfNeeded's deferral. finishNightIfNeeded
// itself cannot be exercised directly in a JVM test (it needs a real Context for the store read, disk I/O and
// the transaction lock), but the one decision that matters - whether a pending nudge instant means the
// FINISHED bookkeeping should not run yet - is a plain predicate, so that part is directly testable without
// one. See docs/decisions.md's L2/L3 records and NightController.kt's own L2.1/L3.1 doc for the reasoning.

import com.nikita.sleepcycle.engine.EngineConfig
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ShouldDeferFinishForPendingNudgeTest {
    private val config = EngineConfig()

    @Test
    fun `L2_1 a pending nudge instant defers the FINISHED bookkeeping`() {
        val pendingNudgeAt = Instant.parse("2026-09-21T07:15:00Z")
        val now = pendingNudgeAt.plusSeconds(5)
        assertTrue(shouldDeferFinishForPendingNudge(pendingNudgeAt, now, config))
    }

    @Test
    fun `L2_1 no pending nudge means nothing to defer for - the bookkeeping runs`() {
        assertFalse(shouldDeferFinishForPendingNudge(null, Instant.parse("2026-09-21T07:15:00Z"), config))
    }

    // ---- L3.1: the staleness bound, mirroring BootReceiver.restoredOutOfBedNudgeAt's own outOfBedDelay bound --

    @Test
    fun `L3_1 exactly outOfBedDelay past the pending instant still defers`() {
        val pendingNudgeAt = Instant.parse("2026-09-21T06:45:00Z")
        val now = pendingNudgeAt.plus(config.outOfBedDelay)
        assertTrue(shouldDeferFinishForPendingNudge(pendingNudgeAt, now, config))
    }

    @Test
    fun `L3_1 one second past outOfBedDelay no longer defers - the record is dead`() {
        val pendingNudgeAt = Instant.parse("2026-09-21T06:45:00Z")
        val now = pendingNudgeAt.plus(config.outOfBedDelay).plusSeconds(1)
        assertFalse(shouldDeferFinishForPendingNudge(pendingNudgeAt, now, config))
    }

    @Test
    fun `L3_1 null pending nudge still does not defer, however far now is`() {
        assertFalse(shouldDeferFinishForPendingNudge(null, Instant.parse("2026-09-21T12:00:00Z"), config))
    }
}
