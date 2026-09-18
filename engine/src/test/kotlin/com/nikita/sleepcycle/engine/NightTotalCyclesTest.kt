package com.nikita.sleepcycle.engine

// File purpose: rule 2 as a TOTAL for the night (spec step 2, "Cycles still owed"). The picked sleep length
// is the whole night's budget, not a fresh count from every onset: sleep already had is subtracted, the
// remainder is rounded to the nearest whole cycle, and nothing owed turns a return to sleep into a nap even
// with no deadline. Includes the owner's own two worked examples.

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NightTotalCyclesTest {
    private fun plan(
        segments: List<SleepSegment>, setting: NightSettings, now: String, previous: AlarmPlan? = null
    ) = computeAlarmPlan(segments, setting, instant(now), previous, testZone, EngineConfig())

    /** The night's first plan: asleep at 23:00 with 7.5 h picked, so the band alarm and the wake boundary are both 06:30. */
    private fun firstPlanOfTheNight(): AlarmPlan =
        plan(listOf(segment("2026-09-16T23:00", "2026-09-16T23:05", SegmentKind.LIGHT)), settings(cycles = 5), "2026-09-16T23:05")

    // ---- The owner's two worked examples --------------------------------------------------------------------

    @Test fun `owner example - 3 h slept of 7_5 h leaves 3 cycles from the new onset, 7_5 h in total`() {
        val segments = listOf(
            segment("2026-09-16T23:00", "2026-09-17T02:00", SegmentKind.LIGHT),
            segment("2026-09-17T02:00", "2026-09-17T02:10", SegmentKind.AWAKE),
            segment("2026-09-17T02:10", "2026-09-17T02:11", SegmentKind.LIGHT)
        )

        val result = plan(segments, settings(cycles = 5), "2026-09-17T02:11", firstPlanOfTheNight())

        assertEquals(AlarmMode.FULL_CYCLES, result.mode)
        assertEquals(3, result.cycles)
        // 02:10 + 4.5 h. Total for the night: 3 h already slept plus 4.5 h more = the picked 7.5 h exactly.
        assertEquals("06:40", formatTime(result.bandAlarm!!, testZone))
    }

    @Test fun `owner example - 2 h slept of 7_5 h leaves 5_5 h, which rounds UP to 4 cycles and 8 h in total`() {
        val segments = listOf(
            segment("2026-09-16T23:00", "2026-09-17T01:00", SegmentKind.LIGHT),
            segment("2026-09-17T01:00", "2026-09-17T01:10", SegmentKind.AWAKE),
            segment("2026-09-17T01:10", "2026-09-17T01:11", SegmentKind.LIGHT)
        )

        val result = plan(segments, settings(cycles = 5), "2026-09-17T01:11", firstPlanOfTheNight())

        assertEquals(AlarmMode.FULL_CYCLES, result.mode)
        assertEquals(4, result.cycles)
        // 5.5 h remaining is 3.67 cycles, which rounds to 4: 01:10 + 6 h, for 8 h in total.
        assertEquals("07:10", formatTime(result.bandAlarm!!, testZone))
    }

    // ---- The first stretch of the night must behave exactly as before ---------------------------------------

    @Test fun `the first stretch of the night is never counted against itself`() {
        val segments = listOf(segment("2026-09-16T23:00", "2026-09-17T02:00", SegmentKind.LIGHT))

        val result = plan(segments, settings(cycles = 5), "2026-09-17T02:00")

        assertEquals(AlarmMode.FULL_CYCLES, result.mode)
        assertEquals(5, result.cycles)
        assertEquals("06:30", formatTime(result.bandAlarm!!, testZone))
    }

    @Test fun `an awake blip shorter than minAwakening never splits the stretch, so nothing counts as already slept`() {
        val segments = listOf(
            segment("2026-09-16T23:00", "2026-09-17T02:00", SegmentKind.LIGHT),
            segment("2026-09-17T01:00", "2026-09-17T01:00:30", SegmentKind.AWAKE)
        )

        val result = plan(segments, settings(cycles = 5), "2026-09-17T02:00")

        assertEquals(5, result.cycles)
        assertEquals("06:30", formatTime(result.bandAlarm!!, testZone))
    }

    // ---- Several awakenings in one night --------------------------------------------------------------------

    @Test fun `two completed stretches both count against the night's total`() {
        val segments = listOf(
            segment("2026-09-16T23:00", "2026-09-17T00:30", SegmentKind.LIGHT),
            segment("2026-09-17T00:30", "2026-09-17T00:40", SegmentKind.AWAKE),
            segment("2026-09-17T00:40", "2026-09-17T02:10", SegmentKind.LIGHT),
            segment("2026-09-17T02:10", "2026-09-17T02:20", SegmentKind.AWAKE),
            segment("2026-09-17T02:20", "2026-09-17T02:21", SegmentKind.LIGHT)
        )

        val result = plan(segments, settings(cycles = 5), "2026-09-17T02:21")

        // 1.5 h + 1.5 h = 3 h slept, so 4.5 h (3 cycles) are still owed: 02:20 + 4.5 h.
        assertEquals(AlarmMode.FULL_CYCLES, result.mode)
        assertEquals(3, result.cycles)
        assertEquals("06:50", formatTime(result.bandAlarm!!, testZone))
    }

    // ---- Rounding to the nearest cycle ----------------------------------------------------------------------

    @Test fun `exactly half a cycle remaining rounds UP to one more cycle`() {
        // 6 h 45 min slept of the picked 7.5 h leaves exactly 45 min, half of one 90 min cycle.
        val segments = listOf(
            segment("2026-09-16T23:00", "2026-09-17T05:45", SegmentKind.LIGHT),
            segment("2026-09-17T05:45", "2026-09-17T05:55", SegmentKind.AWAKE),
            segment("2026-09-17T05:55", "2026-09-17T05:56", SegmentKind.LIGHT)
        )

        val result = plan(segments, settings(cycles = 5), "2026-09-17T05:56")

        assertEquals(AlarmMode.FULL_CYCLES, result.mode)
        assertEquals(1, result.cycles)
        assertEquals("07:25", formatTime(result.bandAlarm!!, testZone))
    }

    @Test fun `just under half a cycle remaining rounds DOWN to nothing owed, which is a nap`() {
        // 6 h 46 min slept leaves 44 min: less than half a cycle, so nothing whole is owed.
        val segments = listOf(
            segment("2026-09-16T23:00", "2026-09-17T05:46", SegmentKind.LIGHT),
            segment("2026-09-17T05:46", "2026-09-17T05:56", SegmentKind.AWAKE),
            segment("2026-09-17T05:56", "2026-09-17T05:57", SegmentKind.LIGHT)
        )

        val result = plan(segments, settings(cycles = 5), "2026-09-17T05:57")

        assertEquals(AlarmMode.NAP, result.mode)
        assertEquals("06:16", formatTime(result.bandAlarm!!, testZone))
    }

    // ---- Nothing owed: nap with or without a deadline --------------------------------------------------------

    @Test fun `the total already exceeded with no deadline is a nap, needing no wake boundary at all`() {
        val result = plan(exceededTotalSegments(), settings(cycles = 5), "2026-09-17T07:41")

        assertEquals(AlarmMode.NAP, result.mode)
        assertEquals(0, result.cycles)
        assertEquals("08:00", formatTime(result.bandAlarm!!, testZone))
        assertNull(result.wakeBoundary, "a no-deadline nap before any FULL_CYCLES plan has no boundary to cap it")
    }

    @Test fun `the total already exceeded with a deadline is a nap capped by that deadline`() {
        val result = plan(exceededTotalSegments(), settings(deadline = "2026-09-17T07:50", cycles = 5), "2026-09-17T07:41")

        assertEquals(AlarmMode.NAP, result.mode)
        assertEquals("07:50", formatTime(result.bandAlarm!!, testZone))
    }

    @Test fun `a nap for an exceeded total that is already past becomes OVERDUE, not a second nap`() {
        val previousNap = AlarmPlan(
            AlarmMode.NAP, instant("2026-09-17T08:00"), null, 0, instant("2026-09-17T07:40"), false, null, "r"
        )
        val segments = listOf(
            segment("2026-09-16T23:00", "2026-09-17T07:30", SegmentKind.LIGHT),
            segment("2026-09-17T07:30", "2026-09-17T07:40", SegmentKind.AWAKE),
            segment("2026-09-17T07:40", "2026-09-17T08:05", SegmentKind.LIGHT)
        )

        val result = plan(segments, settings(cycles = 5), "2026-09-17T08:05", previousNap)

        assertEquals(AlarmMode.OVERDUE, result.mode)
        assertEquals("08:07", formatTime(result.bandAlarm!!, testZone))
        assertEquals("08:00", formatTime(result.overdueSince!!, testZone))
    }

    @Test fun `waking at or after the alarm still FINISHES the night even with nothing owed`() {
        val previousNap = AlarmPlan(
            AlarmMode.NAP, instant("2026-09-17T08:00"), null, 0, instant("2026-09-17T07:40"), false, null, "r"
        )
        val segments = listOf(
            segment("2026-09-16T23:00", "2026-09-17T07:30", SegmentKind.LIGHT),
            segment("2026-09-17T07:30", "2026-09-17T08:05", SegmentKind.AWAKE)
        )

        val result = plan(segments, settings(cycles = 5), "2026-09-17T08:05", previousNap)

        assertEquals(AlarmMode.FINISHED, result.mode)
        assertNull(result.bandAlarm)
    }

    // ---- The deadline still caps what is owed ----------------------------------------------------------------

    @Test fun `with a deadline the alarm is the smaller of what is owed and what still fits`() {
        val segments = listOf(
            segment("2026-09-16T23:00", "2026-09-17T02:00", SegmentKind.LIGHT),
            segment("2026-09-17T02:00", "2026-09-17T02:10", SegmentKind.AWAKE),
            segment("2026-09-17T02:10", "2026-09-17T02:11", SegmentKind.LIGHT)
        )

        // 3 cycles owed, 4 still fit before 08:30: the owed count binds.
        val owedBinds = plan(segments, settings(deadline = "2026-09-17T08:30", cycles = 5), "2026-09-17T02:11")
        assertEquals(3, owedBinds.cycles)
        assertEquals("06:40", formatTime(owedBinds.bandAlarm!!, testZone))

        // 3 cycles owed, only 1 fits before 05:00: the deadline binds.
        val deadlineBinds = plan(segments, settings(deadline = "2026-09-17T05:00", cycles = 5), "2026-09-17T02:11")
        assertEquals(1, deadlineBinds.cycles)
        assertEquals("03:40", formatTime(deadlineBinds.bandAlarm!!, testZone))
    }

    // ---- Documented interaction with the wake boundary -------------------------------------------------------

    @Test fun `documented - on a night with no deadline the wake boundary still pre-empts the owed count`() {
        // 6.5 h slept of the picked 7.5 h leaves 1 h, which rounds to 1 whole cycle still owed. But the wake
        // boundary (the previous FULL_CYCLES plan's own alarm, 06:30) is less than a cycle away from the new
        // onset, so rule 7's boundary test fires first and a 20 min nap wins over that owed cycle - ending the
        // night at about 6 h 50 min instead of the picked 7.5 h. Flagged for the owner: with the night total in
        // place, the no-deadline arm of the boundary test is the only thing that can still cut the total short.
        val segments = listOf(
            segment("2026-09-16T23:00", "2026-09-17T05:30", SegmentKind.LIGHT),
            segment("2026-09-17T05:30", "2026-09-17T05:40", SegmentKind.AWAKE),
            segment("2026-09-17T05:40", "2026-09-17T05:41", SegmentKind.LIGHT)
        )

        val result = plan(segments, settings(cycles = 5), "2026-09-17T05:41", firstPlanOfTheNight())

        assertEquals(AlarmMode.NAP, result.mode)
        assertEquals("06:00", formatTime(result.bandAlarm!!, testZone))
    }

    /** 8.5 h slept in one completed stretch, then awake and back asleep: more than the picked 7.5 h, so nothing is still owed. */
    private fun exceededTotalSegments(): List<SleepSegment> = listOf(
        segment("2026-09-16T23:00", "2026-09-17T07:30", SegmentKind.LIGHT),
        segment("2026-09-17T07:30", "2026-09-17T07:40", SegmentKind.AWAKE),
        segment("2026-09-17T07:40", "2026-09-17T07:41", SegmentKind.LIGHT)
    )
}
