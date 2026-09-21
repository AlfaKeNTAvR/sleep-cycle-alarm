package com.nikita.sleepcycle.ui.state

// File purpose: owner request - the night screen must say which alarm is coming (morning vs nap vs finished)
// and why, and must not show a bare "--:--" once the morning alarm has already rung while the band still
// reads asleep (H8). Covers buildGoingToBedContent, buildNapAsleepContent and buildWokeUpContent's own mode
// label and reason-text derivation, including the H8 sliding-nap case where a NAP-mode plan actually carries
// the still-pending morning alarm and must not be mislabeled a nap (see alarmModeLabel's own doc).

import com.nikita.sleepcycle.alarm.AlarmLabel
import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.SleepState
import com.nikita.sleepcycle.night.DebugOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildGoingToBedContentTest {
    private val settings = NightSettings(null, 5)
    private val view = testEngineView(SleepState.ASLEEP)

    @Test fun `full cycles mode labels the morning alarm`() {
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T07:00", referenceOnset = "2026-09-17T00:00", cycles = 5)
        val content = buildGoingToBedContent(settings, plan, view, testZone, DebugOptions(), morningAlarmAt = null)
        assertEquals(AlarmLabel.MORNING, content.modeLabel)
    }

    @Test fun `deadline-only mode also labels the morning alarm`() {
        val plan = testAlarmPlan(mode = AlarmMode.DEADLINE_ONLY, wakeAt = "2026-09-17T07:00", referenceOnset = "2026-09-17T00:00")
        val content = buildGoingToBedContent(settings, plan, view, testZone, DebugOptions(), morningAlarmAt = null)
        assertEquals(AlarmLabel.MORNING, content.modeLabel)
    }

    @Test fun `reason text is the engine's own sentence, unmodified`() {
        val reason = "Asleep since 00:00, 5 of 5 picked cycles fit, alarm 07:00."
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T07:00", referenceOnset = "2026-09-17T00:00", reason = reason)
        val content = buildGoingToBedContent(settings, plan, view, testZone, DebugOptions(), morningAlarmAt = null)
        assertEquals(reason, content.reasonText)
    }

    @Test fun `H8 already-rang case shows the real rung time instead of the dash`() {
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = null, referenceOnset = "2026-09-17T00:00", cycles = 5)
        val content = buildGoingToBedContent(settings, plan, view, testZone, DebugOptions(), morningAlarmAt = instant("2026-09-17T07:00"))
        assertTrue(content.alarmAlreadyRang)
        assertEquals("07:00", content.alarmTimeLabel)
    }

    @Test fun `a genuinely missing alarm with no latched morning time still falls back to the dash`() {
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = null, referenceOnset = "2026-09-17T00:00", cycles = 5)
        val content = buildGoingToBedContent(settings, plan, view, testZone, DebugOptions(), morningAlarmAt = null)
        assertFalse(content.alarmAlreadyRang)
        assertEquals(MISSING_TIME_LABEL, content.alarmTimeLabel)
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

    @Test fun `a nap alarm already fired with nothing left to arm still labels nap`() {
        val plan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = null)
        val content = buildWokeUpContent(plan, deadline = null, view, testZone, napOnly = true, DebugOptions(), morningAlarmAt = instant("2026-09-17T07:00"))
        assertEquals(AlarmLabel.NAP, content.modeLabel)
    }

    @Test fun `reason text passes through unmodified`() {
        val reason = "some engine reason"
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T07:00", reason = reason)
        val content = buildWokeUpContent(plan, deadline = null, view, testZone, napOnly = false, DebugOptions(), morningAlarmAt = null)
        assertEquals(reason, content.reasonText)
    }
}
