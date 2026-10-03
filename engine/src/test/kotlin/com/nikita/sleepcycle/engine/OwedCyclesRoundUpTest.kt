package com.nikita.sleepcycle.engine

// File purpose: owner decision, 2026-10-03 (phone night 2026-10-03, alarm 08:48): the cycles still owed after an
// awakening round UP, so a night with no deadline gives at least the picked sleep and never ends on a 20 min nap
// mid-cycle; a leftover of up to 10 min is forgiven so a few minutes never cost a whole extra cycle. Supersedes
// the 2026-09-17 "nearest whole cycle" rule. Worked examples use the owner's 5 picked cycles (7.5 h).

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OwedCyclesRoundUpTest {
    private fun owedAfter(sleptMinutes: Long) = countOwedCycles(Duration.ofMinutes(sleptMinutes), settings(cycles = 5), EngineConfig())

    @Test fun `exact whole cycles still owed stay as they are`() {
        // 3 h slept: 4.5 h left, exactly 3 cycles.
        assertEquals(3, owedAfter(180))
    }

    @Test fun `a part cycle still owed rounds up to a whole one`() {
        // 2 h 40 slept: 4 h 50 left, 3.2 cycles. Nearest gave 3, 20 min short of the pick.
        assertEquals(4, owedAfter(160))
    }

    @Test fun `40 min still owed is one more cycle, not a nap`() {
        // The phone night of 2026-10-03: 6 h 50 slept, 40 min left. Nearest gave 0 and a 20 min nap alarm.
        assertEquals(1, owedAfter(410))
    }

    @Test fun `up to 10 min over whole cycles is forgiven`() {
        // 3 cycles and 8 min left stays 3 cycles; 3 cycles and 10 min too.
        assertEquals(3, owedAfter(450 - 270 - 8))
        assertEquals(3, owedAfter(450 - 270 - 10))
    }

    @Test fun `more than 10 min over whole cycles is one more cycle`() {
        assertEquals(4, owedAfter(450 - 270 - 11))
    }

    @Test fun `10 min or less left in the whole night owes nothing`() {
        assertEquals(0, owedAfter(440))
        assertEquals(0, owedAfter(450))
        assertEquals(0, owedAfter(500))
    }

    @Test fun `the night of 2026-10-03 replayed - back asleep at 08_28 with 40 min owed rings a cycle later, not at 08_48`() {
        // 6 h 50 min of earlier sleep, then an awakening and back asleep at 08:28.
        val segments = listOf(
            segment("2026-10-03T01:20", "2026-10-03T08:10", SegmentKind.LIGHT),
            segment("2026-10-03T08:10", "2026-10-03T08:28", SegmentKind.AWAKE),
            segment("2026-10-03T08:28", "2026-10-03T08:46", SegmentKind.LIGHT),
        )
        val plan = computeAlarmPlan(segments, settings(cycles = 5), instant("2026-10-03T08:46"), null, testZone, EngineConfig(), null, 0, null, null)
        assertEquals(AlarmMode.FULL_CYCLES, plan.mode)
        assertEquals(instant("2026-10-03T09:58"), plan.wakeAt)
    }
}
