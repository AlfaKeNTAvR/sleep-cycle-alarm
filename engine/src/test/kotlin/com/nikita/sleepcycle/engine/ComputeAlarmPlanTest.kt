package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import java.time.Instant

class ComputeAlarmPlanTest {
    private fun plan(
        segments: List<SleepSegment>, setting: NightSettings, now: String, previous: AlarmPlan? = null,
        wakeAlarmFiredAt: Instant? = null, napAlarmsUsed: Int = 0, lastNapAlarmFiredAt: Instant? = null
    ) = computeAlarmPlan(
        segments, setting, instant(now), previous?.wakeAt, testZone, EngineConfig(), wakeAlarmFiredAt, napAlarmsUsed, lastNapAlarmFiredAt
    )

    @Test fun `no deadline uses the picked cycles from the reference onset`() {
        val asleep = listOf(segment("2026-09-17T00:30", "2026-09-17T01:00", SegmentKind.LIGHT))
        val result = plan(asleep, settings(cycles = 5), "2026-09-17T01:01")
        assertEquals(AlarmMode.FULL_CYCLES, result.mode)
        assertEquals("08:00", formatTime(result.wakeAt!!, testZone))
    }

    @Test fun `a cycle ending exactly at the deadline fits`() {
        val asleep = listOf(segment("2026-09-17T00:00", "2026-09-17T00:05", SegmentKind.LIGHT))
        val result = plan(asleep, settings("2026-09-17T01:30", cycles = 3), "2026-09-17T00:06")
        assertEquals(AlarmMode.FULL_CYCLES, result.mode)
        assertEquals(1, result.cycles)
        assertEquals("01:30", formatTime(result.wakeAt!!, testZone))
    }

    @Test fun `one minute past the boundary does not fit`() {
        val asleep = listOf(segment("2026-09-17T00:00", "2026-09-17T00:05", SegmentKind.LIGHT))
        val result = plan(asleep, settings("2026-09-17T01:29", cycles = 3), "2026-09-17T00:06")
        assertEquals(AlarmMode.DEADLINE_ONLY, result.mode)
        assertEquals(0, result.cycles)
        assertEquals("01:29", formatTime(result.wakeAt!!, testZone))
    }

    @Test fun `the projected onset slides with now, dropping a cycle once it stops fitting`() {
        val early = plan(emptyList(), settings("2026-09-17T08:30", cycles = 6), "2026-09-17T00:40")
        assertEquals(5, early.cycles)
        assertEquals("08:25", formatTime(early.wakeAt!!, testZone))
        val later = plan(emptyList(), settings("2026-09-17T08:30", cycles = 6), "2026-09-17T01:30")
        assertEquals(4, later.cycles)
        assertEquals("07:45", formatTime(later.wakeAt!!, testZone))
    }

    @Test fun `first sleep too close to the deadline is DEADLINE_ONLY, not a nap`() {
        val asleep = listOf(segment("2026-09-17T07:30", "2026-09-17T07:31", SegmentKind.LIGHT))
        val result = plan(asleep, settings("2026-09-17T08:30"), "2026-09-17T07:31")
        assertEquals(AlarmMode.DEADLINE_ONLY, result.mode)
        assertEquals("08:30", formatTime(result.wakeAt!!, testZone))
    }

    @Test fun `an awakening with a full cycle still fitting before the deadline restarts FULL_CYCLES`() {
        val segments = listOf(
            segment("2026-09-17T00:00", "2026-09-17T06:30", SegmentKind.LIGHT),
            segment("2026-09-17T06:30", "2026-09-17T06:45", SegmentKind.AWAKE),
            segment("2026-09-17T06:45", "2026-09-17T06:50", SegmentKind.LIGHT)
        )
        val result = plan(segments, settings("2026-09-17T08:30"), "2026-09-17T06:50")
        assertEquals(AlarmMode.FULL_CYCLES, result.mode)
        assertEquals("08:15", formatTime(result.wakeAt!!, testZone))
    }

    @Test fun `no deadline, an awakening counts what is still owed of the night's total, never a fresh full count`() {
        // Behaviour change, rule 2 as a night total: 5 h of the picked 7.5 h are already slept, so 2.5 h are
        // still owed, which rounds to 2 cycles from the projected 03:25 onset - not a fresh 7.5 h ending 10:55.
        val previous = AlarmPlan(
            AlarmMode.FULL_CYCLES, instant("2026-09-17T05:30"), 5, instant("2026-09-17T22:00"), false, "r"
        )
        val segments = listOf(
            segment("2026-09-16T22:00", "2026-09-17T03:00", SegmentKind.LIGHT),
            segment("2026-09-17T03:00", "2026-09-17T03:06", SegmentKind.AWAKE)
        )
        val result = plan(segments, settings(cycles = 5), "2026-09-17T03:10", previous)
        assertEquals(AlarmMode.FULL_CYCLES, result.mode)
        assertEquals(2, result.cycles)
        assertEquals("06:25", formatTime(result.wakeAt!!, testZone))
    }

    @Test fun `nap mode slides while awake, then fixes once asleep`() {
        // Each round's "now" stays before the previous round's own alarm: once state is AWAKE and now
        // reaches or passes that alarm, rule 1 (already awake at alarm time) ends the night, by design.
        // The 08:00 deadline is what makes this a nap at all: one more cycle no longer fits before it.
        val setting = settings(deadline = "2026-09-17T08:00", cycles = 5)
        val previous = AlarmPlan(
            AlarmMode.FULL_CYCLES, instant("2026-09-17T08:00"), 5, instant("2026-09-17T00:30"), false, "r"
        )
        val segments = listOf(
            segment("2026-09-17T00:30", "2026-09-17T07:00", SegmentKind.LIGHT),
            segment("2026-09-17T07:00", "2026-09-17T07:05", SegmentKind.AWAKE)
        )
        val awake = plan(segments, setting, "2026-09-17T07:05", previous)
        assertEquals(AlarmMode.NAP, awake.mode)
        assertEquals("07:25", formatTime(awake.wakeAt!!, testZone))

        val stillAwake = plan(segments, setting, "2026-09-17T07:15", awake)
        assertEquals(AlarmMode.NAP, stillAwake.mode)
        assertEquals("07:35", formatTime(stillAwake.wakeAt!!, testZone))

        val backAsleep = segments + segment("2026-09-17T07:16", "2026-09-17T07:20", SegmentKind.LIGHT)
        val asleepAgain = plan(backAsleep, setting, "2026-09-17T07:20", stillAwake)
        assertEquals(AlarmMode.NAP, asleepAgain.mode)
        assertEquals("07:36", formatTime(asleepAgain.wakeAt!!, testZone))

        val stillAsleep = plan(
            backAsleep + segment("2026-09-17T07:20", "2026-09-17T07:30", SegmentKind.LIGHT),
            setting, "2026-09-17T07:30", asleepAgain
        )
        assertEquals(AlarmMode.NAP, stillAsleep.mode)
        assertEquals("07:36", formatTime(stillAsleep.wakeAt!!, testZone))
    }

    @Test fun `late nap detection stays NAP, pulled forward, not a stale alarm from the previous plan`() {
        val previous = AlarmPlan(
            AlarmMode.NAP, instant("2026-09-17T01:20"), 0, instant("2026-09-17T00:30"), false, "r"
        )
        val segments = listOf(
            segment("2026-09-17T00:00", "2026-09-17T00:30", SegmentKind.LIGHT),
            segment("2026-09-17T00:30", "2026-09-17T00:50", SegmentKind.AWAKE),
            segment("2026-09-17T00:50", "2026-09-17T01:15", SegmentKind.LIGHT)
        )
        // The 01:20 deadline is what makes this a nap: no full cycle fits after the 00:50 return to sleep. The
        // raw nap alarm (01:10) is before now + minAlarmLead, so it is pulled forward to 01:17.
        val result = plan(segments, settings(deadline = "2026-09-17T01:20", cycles = 5), "2026-09-17T01:15", previous)
        assertEquals(AlarmMode.NAP, result.mode)
        assertEquals("01:17", formatTime(result.wakeAt!!, testZone))
    }

    @Test fun `deadline exactly at now is FINISHED without throwing`() {
        val result = plan(listOf(segment("2026-09-17T00:30", "2026-09-17T01:00", SegmentKind.LIGHT)), settings("2026-09-17T08:31"), "2026-09-17T08:31")
        assertEquals(AlarmMode.FINISHED, result.mode)
        assertNull(result.wakeAt)
    }

    @Test fun `a deadline 30 seconds away is pulled forward but still capped at the deadline`() {
        val asleep = listOf(segment("2026-09-17T07:00", "2026-09-17T07:30", SegmentKind.LIGHT))
        val result = plan(asleep, settings("2026-09-17T08:29:30"), "2026-09-17T08:29:00")
        // No whole cycle fits in 30 s, so this is DEADLINE_ONLY; the raw alarm (the deadline itself) is closer
        // than minAlarmLead, but D8 caps the pull-forward at the deadline rather than giving up on the night.
        assertEquals(AlarmMode.DEADLINE_ONLY, result.mode)
        assertEquals(instant("2026-09-17T08:29:30"), result.wakeAt)
    }

    @Test fun `D3 an awake mark after the previous alarm no longer FINISHES the night by itself`() {
        val previous = AlarmPlan(
            AlarmMode.FULL_CYCLES, instant("2026-09-17T08:00"), 5, instant("2026-09-17T00:30"), false, "r"
        )
        val segments = listOf(
            segment("2026-09-17T00:30", "2026-09-17T08:00", SegmentKind.LIGHT),
            segment("2026-09-17T08:00", "2026-09-17T08:05", SegmentKind.AWAKE)
        )
        // No wakeAlarmFiredAt is passed here (the caller's own guard, NightState.phoneAlarmFiredFor, is an app-
        // layer concern) - the picked total is exactly used up, so rule 7's own test now governs this window: a
        // sliding nap alarm, not FINISHED. The night only ends here via the owner's own confirmation (D6, at
        // the app layer, never reaching the engine again) or the deadline/nap cap (see ExtraNightScenariosTest
        // for the D5 nap-cap sequence).
        val result = plan(segments, settings(cycles = 5), "2026-09-17T08:05", previous)
        assertEquals(AlarmMode.NAP, result.mode)
    }

    @Test fun `a night crossing midnight computes correctly`() {
        val asleep = listOf(segment("2026-09-16T23:05", "2026-09-16T23:10", SegmentKind.LIGHT))
        val result = plan(asleep, settings("2026-09-17T07:00", cycles = 4), "2026-09-16T23:10")
        assertEquals(AlarmMode.FULL_CYCLES, result.mode)
        assertEquals("05:05", formatTime(result.wakeAt!!, testZone))
    }

    @Test fun `empty, unsorted, overlapping and zero-length segments never throw`() {
        val segments = listOf(
            segment("2026-09-17T01:00", "2026-09-17T02:00", SegmentKind.LIGHT),
            segment("2026-09-17T00:30", "2026-09-17T00:30", SegmentKind.AWAKE),
            segment("2026-09-17T00:00", "2026-09-17T01:30", SegmentKind.DEEP)
        )
        assertDoesNotThrow { plan(emptyList(), settings(cycles = 3), "2026-09-17T01:00") }
        assertDoesNotThrow { plan(segments, settings(cycles = 3), "2026-09-17T02:00") }
    }
}
