package com.nikita.sleepcycle.ui.state

// File purpose: the sleep length Start night actually uses (deadline.md test audit, 2026-10-03: untested until
// now). The picked length is kept while it fits before the deadline; otherwise the longest one that fits; and
// when none fits, the picked one unchanged (the deadline then rings on its own). Worked with the defaults:
// 15 min to fall asleep, 90 min cycles, lengths of 3 to 6 cycles.

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class ResolvePickedCyclesTest {
    private val now = Instant.parse("2026-10-02T23:00:00Z")

    @Test
    fun `a picked length that fits before the deadline is kept`() {
        // 3 cycles: asleep about 23:15, up at 03:45, before 06:00.
        assertEquals(3, resolvePickedCycles(3, now, deadline = Instant.parse("2026-10-03T06:00:00Z")))
    }

    @Test
    fun `a picked length that no longer fits falls back to the longest one that does`() {
        // 5 cycles would end 06:45, after 06:00; 4 cycles end 05:15.
        assertEquals(4, resolvePickedCycles(5, now, deadline = Instant.parse("2026-10-03T06:00:00Z")))
    }

    @Test
    fun `a cycle ending exactly at the deadline still fits`() {
        // 4 cycles end exactly at 05:15.
        assertEquals(4, resolvePickedCycles(6, now, deadline = Instant.parse("2026-10-03T05:15:00Z")))
    }

    @Test
    fun `when no length fits the picked one is left unchanged`() {
        // Even 3 cycles would end 03:45, after a 03:00 deadline.
        assertEquals(5, resolvePickedCycles(5, now, deadline = Instant.parse("2026-10-03T03:00:00Z")))
    }

    @Test
    fun `with no deadline the picked length is kept`() {
        assertEquals(6, resolvePickedCycles(6, now, deadline = null))
    }
}
