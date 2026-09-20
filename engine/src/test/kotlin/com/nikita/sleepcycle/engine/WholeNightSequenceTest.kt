package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** Replays a night as a series of syncs, feeding each plan's own `wakeAt` back in as `morningAlarmAt` (the engine's real call shape threads the app-layer latch instead - see NightOrchestrator.latchMorningAlarmAt - but for every sequence below, which never chains two NAP plans in the AWAKE state, the two agree). */
class WholeNightSequenceTest {
    private fun plan(
        segments: List<SleepSegment>, setting: NightSettings, now: String, previous: AlarmPlan?,
        wakeAlarmFiredAt: Instant? = null, napAlarmsUsed: Int = 0, lastNapAlarmFiredAt: Instant? = null
    ) = computeAlarmPlan(
        segments, setting, instant(now), previous?.wakeAt, testZone, EngineConfig(), wakeAlarmFiredAt, napAlarmsUsed, lastNapAlarmFiredAt
    )

    @Test fun `a full night with a brief awakening owes only the rest of the total, then is pulled forward, then finishes at the deadline`() {
        val setting = settings(deadline = "2026-09-17T08:30", cycles = 5)

        val fallsAsleep = plan(listOf(segment("2026-09-17T00:30", "2026-09-17T00:45", SegmentKind.LIGHT)), setting, "2026-09-17T00:45", null)
        assertEquals(AlarmMode.FULL_CYCLES, fallsAsleep.mode)
        assertEquals("08:00", formatTime(fallsAsleep.wakeAt!!, testZone))

        val wakesBriefly = plan(
            listOf(segment("2026-09-17T00:30", "2026-09-17T06:30", SegmentKind.LIGHT), segment("2026-09-17T06:30", "2026-09-17T06:35", SegmentKind.AWAKE)),
            setting, "2026-09-17T06:35", fallsAsleep
        )
        assertEquals(AlarmMode.FULL_CYCLES, wakesBriefly.mode)
        assertEquals("08:20", formatTime(wakesBriefly.wakeAt!!, testZone))
        // 08:20 alone proves nothing: only one cycle fits between the 06:50 projected onset and the 08:30
        // deadline either way, so the old per-stretch count (5 cycles, capped to 1) lands on the same time.
        // What the total rule changes is the owed count BEFORE the cap - 6 h of the picked 7.5 h is slept, so
        // one cycle is owed, not five. The no-deadline sibling test below is where the two rules diverge.
        assertEquals(1, wakesBriefly.owedCycles)
        assertEquals(Duration.ofHours(6), wakesBriefly.sleptSoFar)

        val fallsBackAsleep = plan(
            listOf(
                segment("2026-09-17T00:30", "2026-09-17T06:30", SegmentKind.LIGHT),
                segment("2026-09-17T06:30", "2026-09-17T06:36", SegmentKind.AWAKE),
                segment("2026-09-17T06:36", "2026-09-17T06:45", SegmentKind.LIGHT)
            ),
            setting, "2026-09-17T06:45", wakesBriefly
        )
        assertEquals(AlarmMode.FULL_CYCLES, fallsBackAsleep.mode)
        assertEquals("08:06", formatTime(fallsBackAsleep.wakeAt!!, testZone))

        // Still asleep past the raw 08:06 alarm: D8 pulls it forward to now + minAlarmLead (rounded up), capped
        // by the deadline, without changing the mode - there is no more OVERDUE to escalate to.
        val pulledForward = plan(
            listOf(
                segment("2026-09-17T00:30", "2026-09-17T06:30", SegmentKind.LIGHT),
                segment("2026-09-17T06:30", "2026-09-17T06:36", SegmentKind.AWAKE),
                segment("2026-09-17T06:36", "2026-09-17T06:45", SegmentKind.LIGHT)
            ),
            setting, "2026-09-17T08:07", fallsBackAsleep
        )
        assertEquals(AlarmMode.FULL_CYCLES, pulledForward.mode)
        assertEquals("08:09", formatTime(pulledForward.wakeAt!!, testZone))

        val finished = plan(
            listOf(
                segment("2026-09-17T00:30", "2026-09-17T06:30", SegmentKind.LIGHT),
                segment("2026-09-17T06:30", "2026-09-17T06:36", SegmentKind.AWAKE),
                segment("2026-09-17T06:36", "2026-09-17T06:45", SegmentKind.LIGHT)
            ),
            setting, "2026-09-17T08:31", pulledForward
        )
        assertEquals(AlarmMode.FINISHED, finished.mode)
        assertNull(finished.wakeAt)
        assertNull(finished.referenceOnset)
    }

    @Test fun `the same brief awakening with NO deadline owes one cycle, where a per-stretch count would owe five`() {
        // Nothing caps this night but the picked total, so the alarm time itself is the whole proof: 6 h of
        // 7.5 h slept leaves one cycle from the 06:50 projected onset (08:20). Counting cycles per stretch,
        // as the engine did before the total rule, would plan five more cycles and wake the sleeper at 14:20.
        val setting = settings(cycles = 5)

        val fallsAsleep = plan(listOf(segment("2026-09-17T00:30", "2026-09-17T00:45", SegmentKind.LIGHT)), setting, "2026-09-17T00:45", null)
        assertEquals("08:00", formatTime(fallsAsleep.wakeAt!!, testZone))

        val wakesBriefly = plan(
            listOf(segment("2026-09-17T00:30", "2026-09-17T06:30", SegmentKind.LIGHT), segment("2026-09-17T06:30", "2026-09-17T06:35", SegmentKind.AWAKE)),
            setting, "2026-09-17T06:35", fallsAsleep
        )

        assertEquals(AlarmMode.FULL_CYCLES, wakesBriefly.mode)
        assertEquals(1, wakesBriefly.owedCycles)
        assertEquals(1, wakesBriefly.cycles, "with no deadline nothing caps the owed count")
        assertEquals("08:20", formatTime(wakesBriefly.wakeAt!!, testZone))
    }
}
