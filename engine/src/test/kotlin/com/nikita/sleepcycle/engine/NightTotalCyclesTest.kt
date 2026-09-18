package com.nikita.sleepcycle.engine

// File purpose: rule 2 as a TOTAL for the night (spec step 2, "Cycles still owed"). The picked sleep length
// is the whole night's budget, not a fresh count from every onset: sleep already had is subtracted, the
// remainder is rounded to the nearest whole cycle, and nothing owed turns a return to sleep into a nap even
// with no deadline. Includes the owner's own two worked examples.

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NightTotalCyclesTest {
    private fun plan(
        segments: List<SleepSegment>, setting: NightSettings, now: String, previous: AlarmPlan? = null
    ) = computeAlarmPlan(segments, setting, instant(now), previous, testZone, EngineConfig())

    /** The night's first plan: asleep at 23:00 with 7.5 h picked, so the band alarm is 06:30. */
    private fun firstPlanOfTheNight(): AlarmPlan =
        plan(listOf(segment("2026-09-16T23:00", "2026-09-16T23:05", SegmentKind.LIGHT)), settings(cycles = 5), "2026-09-16T23:05")

    /** The night's total if the sleeper stays asleep until [AlarmPlan.bandAlarm]: what was already slept plus this last stretch. */
    private fun totalSleepIfSleptToAlarm(alreadySlept: Duration, lastOnset: String, plan: AlarmPlan): Duration =
        alreadySlept.plus(Duration.between(instant(lastOnset), plan.bandAlarm!!))

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

    @Test fun `hours into the new stretch the alarm still counts from ITS onset, with only completed sleep subtracted`() {
        // Every other already-slept case is measured one minute into the new stretch, where subtracting the
        // current stretch by mistake would change the answer by one minute and no test would notice. Here the
        // sleeper has been back asleep for two hours: 3 h completed, so 3 cycles are owed from the 02:10
        // onset (06:40). Counting the current stretch too would leave 5 h slept, 2 cycles, and 05:10.
        val segments = listOf(
            segment("2026-09-16T23:00", "2026-09-17T02:00", SegmentKind.LIGHT),
            segment("2026-09-17T02:00", "2026-09-17T02:10", SegmentKind.AWAKE),
            segment("2026-09-17T02:10", "2026-09-17T04:10", SegmentKind.LIGHT)
        )

        val result = plan(segments, settings(cycles = 5), "2026-09-17T04:10", firstPlanOfTheNight())

        assertEquals(AlarmMode.FULL_CYCLES, result.mode)
        assertEquals(Duration.ofHours(3), result.sleptSoFar, "only the COMPLETED stretch counts as already slept")
        assertEquals(3, result.owedCycles)
        assertEquals(3, result.cycles)
        assertEquals("06:40", formatTime(result.bandAlarm!!, testZone))
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
        // Nothing caps a no-deadline nap, so it runs the full 20 min from the 07:40 return to sleep.
        assertEquals("08:00", formatTime(result.bandAlarm!!, testZone))
    }

    @Test fun `the total already exceeded with a deadline is a nap capped by that deadline`() {
        val result = plan(exceededTotalSegments(), settings(deadline = "2026-09-17T07:50", cycles = 5), "2026-09-17T07:41")

        assertEquals(AlarmMode.NAP, result.mode)
        assertEquals("07:50", formatTime(result.bandAlarm!!, testZone))
    }

    @Test fun `a nap for an exceeded total that is already past becomes OVERDUE, not a second nap`() {
        val previousNap = AlarmPlan(
            AlarmMode.NAP, instant("2026-09-17T08:00"), null, 0, instant("2026-09-17T07:40"), false, "r"
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
            AlarmMode.NAP, instant("2026-09-17T08:00"), null, 0, instant("2026-09-17T07:40"), false, "r"
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

    // ---- Only the total, never an earlier plan's alarm, ends a night with no deadline ------------------------

    @Test fun `no deadline - an owed cycle is slept in full even when the previous plan's alarm is minutes away`() {
        // 6.5 h slept of the picked 7.5 h leaves 1 h, which rounds to 1 whole cycle still owed. The previous
        // FULL_CYCLES plan's own alarm (06:30) is less than a cycle from the new onset, and before 2026-09-17
        // that stale alarm turned this into a 20 min nap ending the night at 6 h 50 min. The picked total is
        // now the only cap when there is no deadline, so the owed cycle is slept in full.
        val result = plan(sixAndAHalfHoursSlept(), settings(cycles = 5), "2026-09-17T05:41", firstPlanOfTheNight())

        assertEquals(AlarmMode.FULL_CYCLES, result.mode)
        assertEquals(1, result.cycles)
        assertEquals("07:10", formatTime(result.bandAlarm!!, testZone))
        // 6 h 30 slept plus the 1 h 30 cycle just started: 8 h, the closest whole-cycle landing to the picked 7.5 h.
        assertEquals(Duration.ofHours(8), totalSleepIfSleptToAlarm(Duration.ofMinutes(390), "2026-09-17T05:40", result))
    }

    @Test fun `the same night WITH a deadline that leaves less than a cycle is still a nap, capped by the deadline`() {
        val result = plan(
            sixAndAHalfHoursSlept(), settings(deadline = "2026-09-17T05:55", cycles = 5), "2026-09-17T05:41",
            firstPlanOfTheNight()
        )

        assertEquals(AlarmMode.NAP, result.mode)
        // The nap would run to 06:00, 20 min after the 05:40 return to sleep; the deadline cuts it to 05:55.
        assertEquals("05:55", formatTime(result.bandAlarm!!, testZone))
    }

    @Test fun `a whole no-deadline night with three awakenings sleeps the picked total, never napping early`() {
        // Asleep 23:00, awake 00:30-01:00, 03:30-04:00 and 06:00-06:15. At the third return to sleep the
        // previous plan's alarm (07:00) is less than a cycle away, which used to force a nap and end the night
        // at 6 h 20 min. Counting only the total, the last owed cycle is slept and the night lands on 7.5 h.
        val setting = settings(cycles = 5)

        val asleep = plan(listOf(segment("2026-09-16T23:00", "2026-09-16T23:05", SegmentKind.LIGHT)), setting, "2026-09-16T23:05")
        assertEquals(AlarmMode.FULL_CYCLES, asleep.mode)
        assertEquals("06:30", formatTime(asleep.bandAlarm!!, testZone))

        // 1 h 30 slept, 6 h still owed: 4 whole cycles from the 01:00 onset.
        val afterFirst = plan(nightUpTo("2026-09-17T01:01"), setting, "2026-09-17T01:01", asleep)
        assertEquals(AlarmMode.FULL_CYCLES, afterFirst.mode)
        assertEquals(4, afterFirst.cycles)
        assertEquals("07:00", formatTime(afterFirst.bandAlarm!!, testZone))

        // 4 h slept, 3 h 30 still owed, which rounds to 2 cycles from the 04:00 onset.
        val afterSecond = plan(nightUpTo("2026-09-17T04:01"), setting, "2026-09-17T04:01", afterFirst)
        assertEquals(AlarmMode.FULL_CYCLES, afterSecond.mode)
        assertEquals(2, afterSecond.cycles)
        assertEquals("07:00", formatTime(afterSecond.bandAlarm!!, testZone))

        // 6 h slept, exactly 1 cycle still owed from the 06:15 onset - the case that used to become a nap.
        val afterThird = plan(nightUpTo("2026-09-17T06:16"), setting, "2026-09-17T06:16", afterSecond)
        assertEquals(AlarmMode.FULL_CYCLES, afterThird.mode)
        assertEquals(1, afterThird.cycles)
        assertEquals("07:45", formatTime(afterThird.bandAlarm!!, testZone))
        assertEquals(
            Duration.ofMinutes(450),
            totalSleepIfSleptToAlarm(Duration.ofHours(6), "2026-09-17T06:15", afterThird)
        )
    }

    /** 6 h 30 slept in one completed stretch from 23:00, awake 05:30-05:40, then back asleep at 05:40. */
    private fun sixAndAHalfHoursSlept(): List<SleepSegment> = listOf(
        segment("2026-09-16T23:00", "2026-09-17T05:30", SegmentKind.LIGHT),
        segment("2026-09-17T05:30", "2026-09-17T05:40", SegmentKind.AWAKE),
        segment("2026-09-17T05:40", "2026-09-17T05:41", SegmentKind.LIGHT)
    )

    /** The three-awakening night's marks, clipped to [now] so each sync sees only what the band has reported by then. */
    private fun nightUpTo(now: String): List<SleepSegment> {
        val marks = listOf(
            segment("2026-09-16T23:00", "2026-09-17T00:30", SegmentKind.LIGHT),
            segment("2026-09-17T00:30", "2026-09-17T01:00", SegmentKind.AWAKE),
            segment("2026-09-17T01:00", "2026-09-17T03:30", SegmentKind.LIGHT),
            segment("2026-09-17T03:30", "2026-09-17T04:00", SegmentKind.AWAKE),
            segment("2026-09-17T04:00", "2026-09-17T06:00", SegmentKind.LIGHT),
            segment("2026-09-17T06:00", "2026-09-17T06:15", SegmentKind.AWAKE),
            segment("2026-09-17T06:15", "2026-09-17T08:00", SegmentKind.LIGHT)
        )
        val cutoff = instant(now)
        return marks.filter { it.start.isBefore(cutoff) }.map { if (it.end.isAfter(cutoff)) it.copy(end = cutoff) else it }
    }

    /** 8.5 h slept in one completed stretch, then awake and back asleep: more than the picked 7.5 h, so nothing is still owed. */
    private fun exceededTotalSegments(): List<SleepSegment> = listOf(
        segment("2026-09-16T23:00", "2026-09-17T07:30", SegmentKind.LIGHT),
        segment("2026-09-17T07:30", "2026-09-17T07:40", SegmentKind.AWAKE),
        segment("2026-09-17T07:40", "2026-09-17T07:41", SegmentKind.LIGHT)
    )
}
