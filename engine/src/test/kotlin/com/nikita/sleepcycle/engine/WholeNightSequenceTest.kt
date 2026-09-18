package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration

/** Replays a night as a series of syncs, feeding each plan back in as `previousPlan`, per the engine's real call shape. */
class WholeNightSequenceTest {
    private fun plan(
        segments: List<SleepSegment>, setting: NightSettings, now: String, previous: AlarmPlan?
    ) = computeAlarmPlan(segments, setting, instant(now), previous, testZone, EngineConfig())

    @Test fun `sleeping through the alarm escalates to OVERDUE and locks the phone backup`() {
        val night = listOf(segment("2026-09-17T00:30", "2026-09-17T08:14", SegmentKind.LIGHT))
        val setting = settings(cycles = 5, backup = true)

        val established = plan(night, setting, "2026-09-17T07:00", null)
        assertEquals(AlarmMode.FULL_CYCLES, established.mode)
        assertEquals("08:00", formatTime(established.bandAlarm!!, testZone))
        assertEquals("08:15", formatTime(established.phoneAlarm!!, testZone))

        val firstOverdue = plan(night, setting, "2026-09-17T08:05", established)
        assertEquals(AlarmMode.OVERDUE, firstOverdue.mode)
        assertEquals("08:07", formatTime(firstOverdue.bandAlarm!!, testZone))
        assertEquals("08:15", formatTime(firstOverdue.phoneAlarm!!, testZone))

        // C1: a sync landing mid-minute rounds a FRESH overdue alarm UP to the next whole minute (the band only
        // takes hour:minute; truncating down, as the app used to do on its own, could land the target less than
        // minAlarmLead away, which is exactly the bug this rounding fixes). Branches off `established`, not
        // chained into the rest of this sequence.
        val midMinuteOverdue = plan(night, setting, "2026-09-17T08:05:30", established)
        assertEquals(AlarmMode.OVERDUE, midMinuteOverdue.mode)
        assertEquals("08:08", formatTime(midMinuteOverdue.bandAlarm!!, testZone))

        val staysPut = plan(night, setting, "2026-09-17T08:06", firstOverdue)
        assertEquals(AlarmMode.OVERDUE, staysPut.mode)
        assertEquals("08:07", formatTime(staysPut.bandAlarm!!, testZone))
        assertEquals("08:15", formatTime(staysPut.phoneAlarm!!, testZone))

        val movesOn = plan(night, setting, "2026-09-17T08:10", staysPut)
        assertEquals(AlarmMode.OVERDUE, movesOn.mode)
        assertEquals("08:12", formatTime(movesOn.bandAlarm!!, testZone))
        assertEquals("08:15", formatTime(movesOn.phoneAlarm!!, testZone))
    }

    @Test fun `a full night with a brief awakening owes only the rest of the total, then overdues, then finishes at the deadline`() {
        val setting = settings(deadline = "2026-09-17T08:30", cycles = 5)

        val fallsAsleep = plan(listOf(segment("2026-09-17T00:30", "2026-09-17T00:45", SegmentKind.LIGHT)), setting, "2026-09-17T00:45", null)
        assertEquals(AlarmMode.FULL_CYCLES, fallsAsleep.mode)
        assertEquals("08:00", formatTime(fallsAsleep.bandAlarm!!, testZone))

        val wakesBriefly = plan(
            listOf(segment("2026-09-17T00:30", "2026-09-17T06:30", SegmentKind.LIGHT), segment("2026-09-17T06:30", "2026-09-17T06:35", SegmentKind.AWAKE)),
            setting, "2026-09-17T06:35", fallsAsleep
        )
        assertEquals(AlarmMode.FULL_CYCLES, wakesBriefly.mode)
        assertEquals("08:20", formatTime(wakesBriefly.bandAlarm!!, testZone))
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
        assertEquals("08:06", formatTime(fallsBackAsleep.bandAlarm!!, testZone))

        val overdue = plan(
            listOf(
                segment("2026-09-17T00:30", "2026-09-17T06:30", SegmentKind.LIGHT),
                segment("2026-09-17T06:30", "2026-09-17T06:36", SegmentKind.AWAKE),
                segment("2026-09-17T06:36", "2026-09-17T06:45", SegmentKind.LIGHT)
            ),
            setting, "2026-09-17T08:07", fallsBackAsleep
        )
        assertEquals(AlarmMode.OVERDUE, overdue.mode)
        assertEquals("08:09", formatTime(overdue.bandAlarm!!, testZone))

        val finished = plan(
            listOf(
                segment("2026-09-17T00:30", "2026-09-17T06:30", SegmentKind.LIGHT),
                segment("2026-09-17T06:30", "2026-09-17T06:36", SegmentKind.AWAKE),
                segment("2026-09-17T06:36", "2026-09-17T06:45", SegmentKind.LIGHT)
            ),
            setting, "2026-09-17T08:31", overdue
        )
        assertEquals(AlarmMode.FINISHED, finished.mode)
        assertNull(finished.bandAlarm)
        assertNull(finished.referenceOnset)
    }

    @Test fun `the same brief awakening with NO deadline owes one cycle, where a per-stretch count would owe five`() {
        // Nothing caps this night but the picked total, so the alarm time itself is the whole proof: 6 h of
        // 7.5 h slept leaves one cycle from the 06:50 projected onset (08:20). Counting cycles per stretch,
        // as the engine did before the total rule, would plan five more cycles and wake the sleeper at 14:20.
        val setting = settings(cycles = 5)

        val fallsAsleep = plan(listOf(segment("2026-09-17T00:30", "2026-09-17T00:45", SegmentKind.LIGHT)), setting, "2026-09-17T00:45", null)
        assertEquals("08:00", formatTime(fallsAsleep.bandAlarm!!, testZone))

        val wakesBriefly = plan(
            listOf(segment("2026-09-17T00:30", "2026-09-17T06:30", SegmentKind.LIGHT), segment("2026-09-17T06:30", "2026-09-17T06:35", SegmentKind.AWAKE)),
            setting, "2026-09-17T06:35", fallsAsleep
        )

        assertEquals(AlarmMode.FULL_CYCLES, wakesBriefly.mode)
        assertEquals(1, wakesBriefly.owedCycles)
        assertEquals(1, wakesBriefly.cycles, "with no deadline nothing caps the owed count")
        assertEquals("08:20", formatTime(wakesBriefly.bandAlarm!!, testZone))
    }
}
