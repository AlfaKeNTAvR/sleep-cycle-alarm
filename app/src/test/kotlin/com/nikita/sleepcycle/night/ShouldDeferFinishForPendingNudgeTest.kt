package com.nikita.sleepcycle.night

// File purpose: L2.1 - the pure half of NightController.finishNightIfNeeded's deferral. finishNightIfNeeded
// itself cannot be exercised directly in a JVM test (it needs a real Context for the store read, disk I/O and
// the transaction lock), but the one decision that matters - whether a pending nudge instant means the
// FINISHED bookkeeping should not run yet - is a plain predicate, so that part is directly testable without
// one. See docs/decisions.md's L2 record and NightController.kt's own L2.1 doc for the reasoning.

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ShouldDeferFinishForPendingNudgeTest {
    @Test
    fun `L2_1 a pending nudge instant defers the FINISHED bookkeeping`() {
        assertTrue(shouldDeferFinishForPendingNudge(Instant.parse("2026-09-21T07:15:00Z")))
    }

    @Test
    fun `L2_1 no pending nudge means nothing to defer for - the bookkeeping runs`() {
        assertFalse(shouldDeferFinishForPendingNudge(null))
    }
}
