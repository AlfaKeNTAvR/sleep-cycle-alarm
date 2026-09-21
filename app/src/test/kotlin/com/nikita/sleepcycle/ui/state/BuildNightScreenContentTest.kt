package com.nikita.sleepcycle.ui.state

// File purpose: owner request - the night screen must say which alarm is coming (morning vs nap vs finished)
// and why, and must not show a bare "--:--" once the morning alarm has already rung while the band still
// reads asleep (H8). Covers buildGoingToBedContent, buildNapAsleepContent and buildWokeUpContent's own mode
// label and reason-text derivation, including the H8 sliding-nap case where a NAP-mode plan actually carries
// the still-pending morning alarm and must not be mislabeled a nap (see alarmModeLabel's own doc).

import com.nikita.sleepcycle.alarm.AlarmLabel
import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.SleepState
import com.nikita.sleepcycle.night.DebugOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildGoingToBedContentTest {
    @Test fun `full cycles mode labels the morning alarm`() {
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T07:00", referenceOnset = "2026-09-17T00:00", cycles = 5)
        val content = buildGoingToBedContent(plan, testZone, DebugOptions(), morningAlarmAt = null, deadline = null)
        assertEquals(AlarmLabel.MORNING, content.modeLabel)
    }

    @Test fun `deadline-only mode also labels the morning alarm`() {
        val plan = testAlarmPlan(mode = AlarmMode.DEADLINE_ONLY, wakeAt = "2026-09-17T07:00", referenceOnset = "2026-09-17T00:00")
        val content = buildGoingToBedContent(plan, testZone, DebugOptions(), morningAlarmAt = null, deadline = null)
        assertEquals(AlarmLabel.MORNING, content.modeLabel)
    }

    @Test fun `reason text is the engine's own sentence, unmodified`() {
        val reason = "Asleep since 00:00, 5 of 5 picked cycles fit, alarm 07:00."
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T07:00", referenceOnset = "2026-09-17T00:00", reason = reason)
        val content = buildGoingToBedContent(plan, testZone, DebugOptions(), morningAlarmAt = null, deadline = null)
        assertEquals(reason, content.reasonText)
    }

    @Test fun `H8 already-rang case shows the real rung time instead of the dash`() {
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = null, referenceOnset = "2026-09-17T00:00", cycles = 5)
        val content = buildGoingToBedContent(plan, testZone, DebugOptions(), morningAlarmAt = instant("2026-09-17T07:00"), deadline = null)
        assertTrue(content.alarmAlreadyRang)
        assertEquals("07:00", content.alarmTimeLabel)
    }

    @Test fun `a genuinely missing alarm with no latched morning time falls back to the dash and no subtitle`() {
        // Should-fix 9 of the 09/21 review round 2: this defensive-only branch (a partially restored state
        // file - wakeAt and morningAlarmAt both null) used to fall through to NightSubtitle.SLEEP_LENGTH,
        // which would have shown "X h of sleep" next to the dash and "no alarm armed" - a promise nothing here
        // backs up. NightSubtitle.UNKNOWN suppresses that subtitle line instead.
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = null, referenceOnset = "2026-09-17T00:00", cycles = 5)
        val content = buildGoingToBedContent(plan, testZone, DebugOptions(), morningAlarmAt = null, deadline = null)
        assertFalse(content.alarmAlreadyRang)
        assertEquals(MISSING_TIME_LABEL, content.alarmTimeLabel)
        assertEquals(NightSubtitle.UNKNOWN, content.subtitle)
    }

    @Test fun `already-rang subtitle suppresses the reason text underneath the mode header`() {
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = null, referenceOnset = "2026-09-17T00:00", cycles = 5, reason = "some engine reason")
        val content = buildGoingToBedContent(plan, testZone, DebugOptions(), morningAlarmAt = instant("2026-09-17T07:00"), deadline = null)
        assertEquals(NightSubtitle.ALREADY_RANG, content.subtitle)
        assertNull(content.reasonText)
    }

    @Test fun `already-rang takes precedence over deadline-only for the subtitle`() {
        // H8's already-rang case can be reached by a DEADLINE_ONLY plan too, so both conditions can be true at
        // once (should-fix 7 of the 09/21 review) - already-rang must win, since it is about what already
        // happened, not what is still ahead.
        val plan = testAlarmPlan(mode = AlarmMode.DEADLINE_ONLY, wakeAt = null, referenceOnset = "2026-09-17T00:00")
        val content = buildGoingToBedContent(plan, testZone, DebugOptions(), morningAlarmAt = instant("2026-09-17T07:00"), deadline = instant("2026-09-17T07:00"))
        assertEquals(NightSubtitle.ALREADY_RANG, content.subtitle)
    }

    @Test fun `deadline-only mode picks the deadline-only subtitle and shows no separate deadline caption`() {
        val plan = testAlarmPlan(mode = AlarmMode.DEADLINE_ONLY, wakeAt = "2026-09-17T07:00", referenceOnset = "2026-09-17T00:00")
        val content = buildGoingToBedContent(plan, testZone, DebugOptions(), morningAlarmAt = null, deadline = instant("2026-09-17T07:00"))
        assertEquals(NightSubtitle.DEADLINE_ONLY, content.subtitle)
        assertNull(content.deadlineTimeLabel)
    }

    @Test fun `ordinary sleep-length subtitle carries the deadline as a plain caption`() {
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T06:00", referenceOnset = "2026-09-17T00:00", cycles = 4)
        val content = buildGoingToBedContent(plan, testZone, DebugOptions(), morningAlarmAt = null, deadline = instant("2026-09-17T07:00"))
        assertEquals(NightSubtitle.SLEEP_LENGTH, content.subtitle)
        assertEquals("07:00", content.deadlineTimeLabel)
    }

    @Test fun `no deadline set means no deadline caption`() {
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T06:00", referenceOnset = "2026-09-17T00:00", cycles = 4)
        val content = buildGoingToBedContent(plan, testZone, DebugOptions(), morningAlarmAt = null, deadline = null)
        assertEquals(NightSubtitle.SLEEP_LENGTH, content.subtitle)
        assertNull(content.deadlineTimeLabel)
    }

    @Test fun `already-rang state still carries the deadline caption (09_21 review round 2 must-fix 2)`() {
        // The already-rang state is exactly when the owner is deciding whether to go back to sleep - the
        // deadline caption must not disappear there. An earlier version suppressed it here on the wrong theory
        // that a passed deadline made it moot; ALREADY_RANG is only reachable while the deadline is still ahead.
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = null, referenceOnset = "2026-09-17T00:00", cycles = 5)
        val content = buildGoingToBedContent(plan, testZone, DebugOptions(), morningAlarmAt = instant("2026-09-17T07:00"), deadline = instant("2026-09-17T07:30"))
        assertEquals(NightSubtitle.ALREADY_RANG, content.subtitle)
        assertEquals("07:30", content.deadlineTimeLabel)
    }
}

class BuildNapAsleepContentTest {
    @Test fun `mode label is always the nap alarm`() {
        val plan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = "2026-09-17T03:20", referenceOnset = "2026-09-17T03:00")
        val content = buildNapAsleepContent(plan, testZone, morningAlarmAt = instant("2026-09-17T07:00"))
        assertEquals(AlarmLabel.NAP, content.modeLabel)
    }

    @Test fun `reason text passes through unmodified`() {
        val reason = "Nap mode, asleep since 03:00, alarm 03:20."
        val plan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = "2026-09-17T03:20", referenceOnset = "2026-09-17T03:00", reason = reason)
        val content = buildNapAsleepContent(plan, testZone, morningAlarmAt = null)
        assertEquals(reason, content.reasonText)
    }
}

class BuildWokeUpContentTest {
    private val view = testEngineView(SleepState.AWAKE)

    @Test fun `full cycles mode labels the morning alarm`() {
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T07:00", cycles = 3)
        val content = buildWokeUpContent(plan, deadline = null, view, testZone, napOnly = false, DebugOptions(), morningAlarmAt = null)
        assertEquals(AlarmLabel.MORNING, content.modeLabel)
    }

    @Test fun `a genuine nap unrelated to the morning alarm labels nap`() {
        val plan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = "2026-09-17T03:20")
        val content = buildWokeUpContent(plan, deadline = null, view, testZone, napOnly = true, DebugOptions(), morningAlarmAt = instant("2026-09-17T07:00"))
        assertEquals(AlarmLabel.NAP, content.modeLabel)
    }

    @Test fun `H8 sliding nap carrying the still-pending morning alarm labels morning, not nap`() {
        // Rule 7's AWAKE branch can hand a NAP-mode plan the night's own still-pending morning alarm as its
        // own wakeAt (awakeNapTarget in WakeAlarm.kt) - the plan is NAP, but the ring is the morning alarm.
        val plan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = "2026-09-17T07:00")
        val content = buildWokeUpContent(plan, deadline = null, view, testZone, napOnly = true, DebugOptions(), morningAlarmAt = instant("2026-09-17T07:00"))
        assertEquals(AlarmLabel.MORNING, content.modeLabel)
    }

    @Test fun `a nap alarm already fired with nothing left to arm shows no alarm armed`() {
        // F5 (09/21 review's must-fix 1): rule 7's sliding nap has nothing left to slide to once the wake
        // alarm has fired, so wakeAt is null here. The old code guessed NAP from the plan's mode alone, which
        // read as a wrong "Nap alarm" on an ordinary morning right after the owner was woken by the real one.
        // Nothing is armed, so this must be null, not a guessed label - see alarmModeLabel's own doc.
        val plan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = null)
        val content = buildWokeUpContent(plan, deadline = null, view, testZone, napOnly = true, DebugOptions(), morningAlarmAt = instant("2026-09-17T07:00"))
        assertNull(content.modeLabel)
    }

    @Test fun `reason text passes through unmodified`() {
        val reason = "some engine reason"
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T07:00", reason = reason)
        val content = buildWokeUpContent(plan, deadline = null, view, testZone, napOnly = false, DebugOptions(), morningAlarmAt = null)
        assertEquals(reason, content.reasonText)
    }
}
