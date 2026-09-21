package com.nikita.sleepcycle.ui.state

// File purpose: owner request - the night screen must say which alarm is coming (morning vs nap vs nudge) and
// when, and must never name an alarm that is not armed. Covers buildNextAlarmContent's label, hero time and
// countdown, including the H8 sliding-nap case where a NAP-mode plan actually carries the still-pending
// morning alarm and must not be mislabeled a nap (see alarmModeLabel's own doc), and H8's other case, the
// morning alarm that already rang while the band still reads asleep.

import com.nikita.sleepcycle.alarm.AlarmLabel
import com.nikita.sleepcycle.engine.AlarmMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BuildNextAlarmContentTest {
    private val now = instant("2026-09-17T06:40")

    @Test fun `full cycles mode labels the morning alarm`() {
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T07:00", referenceOnset = "2026-09-17T00:00", cycles = 5)
        val content = buildNextAlarmContent(plan, testZone, morningAlarmAt = null, now = now)
        assertEquals(AlarmLabel.MORNING, content.modeLabel)
    }

    @Test fun `deadline-only mode also labels the morning alarm`() {
        val plan = testAlarmPlan(mode = AlarmMode.DEADLINE_ONLY, wakeAt = "2026-09-17T07:00", referenceOnset = "2026-09-17T00:00")
        val content = buildNextAlarmContent(plan, testZone, morningAlarmAt = null, now = now)
        assertEquals(AlarmLabel.MORNING, content.modeLabel)
    }

    @Test fun `a genuine nap unrelated to the morning alarm labels nap`() {
        val plan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = "2026-09-17T07:00", referenceOnset = "2026-09-17T06:40")
        val content = buildNextAlarmContent(plan, testZone, morningAlarmAt = instant("2026-09-17T09:00"), now = now)
        assertEquals(AlarmLabel.NAP, content.modeLabel)
    }

    @Test fun `H8 sliding nap carrying the still-pending morning alarm labels morning, not nap`() {
        // Rule 7's AWAKE branch can hand a NAP-mode plan the night's own still-pending morning alarm as its
        // own wakeAt (awakeNapTarget in WakeAlarm.kt) - the plan is NAP, but the ring is the morning alarm.
        val plan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = "2026-09-17T07:00")
        val content = buildNextAlarmContent(plan, testZone, morningAlarmAt = instant("2026-09-17T07:00"), now = now)
        assertEquals(AlarmLabel.MORNING, content.modeLabel)
    }

    @Test fun `the hero time is the armed alarm and the line under it counts down to it`() {
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T07:00", referenceOnset = "2026-09-17T00:00", cycles = 5)
        val content = buildNextAlarmContent(plan, testZone, morningAlarmAt = null, now = now)
        assertEquals("07:00", content.alarmTimeLabel)
        assertEquals("20 min", content.countdownLabel)
        assertNull(content.rangAtTimeLabel)
    }

    @Test fun `a countdown longer than an hour carries hours and minutes`() {
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T12:18", referenceOnset = "2026-09-17T00:00", cycles = 5)
        val content = buildNextAlarmContent(plan, testZone, morningAlarmAt = null, now = now)
        assertEquals("5 h 38", content.countdownLabel)
    }

    @Test fun `H8 already-rang shows the dash, no countdown, and the time it rang`() {
        // N1 moved the rung time off the hero slot: the hero answers "when does the next alarm ring", and
        // there is no next alarm here, so a past time sitting in that slot would read as one that is coming.
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = null, referenceOnset = "2026-09-17T00:00", cycles = 5)
        val content = buildNextAlarmContent(plan, testZone, morningAlarmAt = instant("2026-09-17T06:00"), now = now)
        assertNull(content.modeLabel)
        assertEquals(MISSING_TIME_LABEL, content.alarmTimeLabel)
        assertNull(content.countdownLabel)
        assertEquals("06:00", content.rangAtTimeLabel)
    }

    @Test fun `a nap alarm already fired with nothing left to arm shows no alarm armed`() {
        // F5 (09/21 review's must-fix 1): rule 7's sliding nap has nothing left to slide to once the wake
        // alarm has fired, so wakeAt is null here. The old code guessed NAP from the plan's mode alone, which
        // read as a wrong "Nap alarm" on an ordinary morning right after the owner was woken by the real one.
        val plan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = null)
        val content = buildNextAlarmContent(plan, testZone, morningAlarmAt = instant("2026-09-17T06:00"), now = now)
        assertNull(content.modeLabel)
    }

    @Test fun `nothing armed and nothing latched leaves every line but the dash empty`() {
        // The defensive fallback: a partially restored state file, wakeAt and morningAlarmAt both null. The
        // screen has nothing true to say under the hero, so it says nothing rather than filling the line.
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = null, referenceOnset = "2026-09-17T00:00", cycles = 5)
        val content = buildNextAlarmContent(plan, testZone, morningAlarmAt = null, now = now)
        assertNull(content.modeLabel)
        assertEquals(MISSING_TIME_LABEL, content.alarmTimeLabel)
        assertNull(content.countdownLabel)
        assertNull(content.rangAtTimeLabel)
    }

    @Test fun `an alarm whose instant has passed but which has not fired yet counts down to zero`() {
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T06:39", referenceOnset = "2026-09-17T00:00", cycles = 5)
        val content = buildNextAlarmContent(plan, testZone, morningAlarmAt = null, now = now)
        assertEquals("0 min", content.countdownLabel)
    }
}
