package com.nikita.sleepcycle.ui.state

// File purpose: 09/21 review's should-fix 11 - every other test in this package calls
// buildGoingToBedContent/buildNapAsleepContent/buildWokeUpContent directly, so none of them exercise
// buildNightUiState's own job of forwarding NightState.morningAlarmAt into those builders. That wiring is the
// single most important thing this review's must-fix 1 depends on: if buildNightUiState silently passed null
// instead of state.morningAlarmAt, every content-builder test would still pass, and the "no alarm armed" /
// already-rang cases would never actually reach the screen. This file goes through buildNightUiState itself.

import com.nikita.sleepcycle.alarm.AlarmLabel
import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.SleepState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildNightUiStateTest {
    @Test fun `forwards the night's latched morning-alarm time into state A's already-rang case`() {
        // H8: the morning alarm has already rung (wakeAt null) while the band still reads ASLEEP.
        // NightState.morningAlarmAt is the only place that rung time is latched - if buildNightUiState failed
        // to forward it, this would silently fall back to the plain MISSING_TIME_LABEL dash instead.
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = null, referenceOnset = "2026-09-17T00:00", cycles = 5)
        val state = testNightState(lastPlan = plan, morningAlarmAt = "2026-09-17T07:00")
        val view = testEngineView(SleepState.ASLEEP)

        val uiState = buildNightUiState(
            nightState = state,
            engineView = view,
            now = instant("2026-09-17T07:05"),
            zone = testZone,
            showingMorningReport = false,
            morningReportEndedAt = null,
            confirmingEndNight = false,
            pendingOutOfBedNudgeAt = null,
        )

        val content = uiState?.content
        check(content is NightScreenContent.GoingToBedOrAsleep) { "expected state A, got $content" }
        assertTrue(content.alarmAlreadyRang)
        assertEquals("07:00", content.alarmTimeLabel)
    }

    @Test fun `forwards the night's latched morning-alarm time into the H8 sliding-nap case`() {
        // Should-fix 3 of the 09/21 review round 2: the previous version of this test passed wakeAt = null,
        // which short-circuits alarmModeLabel before morningAlarmAt is ever read - hardcoding null in place of
        // state.morningAlarmAt at the call site would still have passed it. Rule 7's sliding nap (H8) is the
        // case that actually exercises the forward: a NAP plan whose wakeAt equals the still-pending morning
        // alarm must be labelled MORNING, not NAP - otherwise the screen would say "Nap alarm" for the exact
        // same instant the phone is about to ring labelled "Morning alarm".
        val plan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = "2026-09-17T06:58")
        val state = testNightState(lastPlan = plan, morningAlarmAt = "2026-09-17T06:58")
        val view = testEngineView(SleepState.AWAKE)

        val uiState = buildNightUiState(
            nightState = state,
            engineView = view,
            now = instant("2026-09-17T06:55"),
            zone = testZone,
            showingMorningReport = false,
            morningReportEndedAt = null,
            confirmingEndNight = false,
            pendingOutOfBedNudgeAt = null,
        )

        val content = uiState?.content
        check(content is NightScreenContent.WokeUp) { "expected state C (WokeUp/napOnly), got $content" }
        assertEquals(AlarmLabel.MORNING, content.modeLabel)
    }

    @Test fun `state C's no-alarm-armed case shows the pending out-of-bed nudge instead of a false negative`() {
        // Round 2 of the 09/21 review, must-fix 1: F5's "no alarm armed" reading co-occurs, every time, with
        // the out-of-bed nudge PhoneAlarmReceiver just armed for the real firing that produced this AWAKE
        // reading in the first place. Reproduces the review's traced sequence: morning alarm rings 07:00, nudge
        // armed for 07:15, band reports AWAKE at 07:05 with nothing left for rule 7's sliding nap to arm.
        val plan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = null)
        val state = testNightState(lastPlan = plan, morningAlarmAt = "2026-09-17T07:00")
        val view = testEngineView(SleepState.AWAKE)

        val uiState = buildNightUiState(
            nightState = state,
            engineView = view,
            now = instant("2026-09-17T07:05"),
            zone = testZone,
            showingMorningReport = false,
            morningReportEndedAt = null,
            confirmingEndNight = false,
            pendingOutOfBedNudgeAt = instant("2026-09-17T07:15"),
        )

        val content = uiState?.content
        check(content is NightScreenContent.WokeUp) { "expected state C (WokeUp/napOnly), got $content" }
        assertEquals(AlarmLabel.OUT_OF_BED, content.modeLabel)
        assertEquals("07:15", content.modeLabelTimeLabel)
    }

    @Test fun `state A's already-rang case also shows the pending out-of-bed nudge`() {
        // The review's own note: "the same applies to state A's already-rang case between the ring and the
        // pre-check" - the H8 already-rang branch of GoingToBedOrAsleep reaches a null modeLabel exactly the
        // same way state C does.
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = null, referenceOnset = "2026-09-17T00:00", cycles = 5)
        val state = testNightState(lastPlan = plan, morningAlarmAt = "2026-09-17T07:00")
        val view = testEngineView(SleepState.ASLEEP)

        val uiState = buildNightUiState(
            nightState = state,
            engineView = view,
            now = instant("2026-09-17T07:05"),
            zone = testZone,
            showingMorningReport = false,
            morningReportEndedAt = null,
            confirmingEndNight = false,
            pendingOutOfBedNudgeAt = instant("2026-09-17T07:15"),
        )

        val content = uiState?.content
        check(content is NightScreenContent.GoingToBedOrAsleep) { "expected state A, got $content" }
        assertEquals(AlarmLabel.OUT_OF_BED, content.modeLabel)
        assertEquals("07:15", content.modeLabelTimeLabel)
    }

    @Test fun `a nudge that has already fired does not resurrect the no-alarm-armed reading`() {
        // A stale/past pendingOutOfBedNudgeAt (the nudge already fired, or the read raced a cancel) must not
        // show an alarm that is no longer armed - the whole point of must-fix 1 is not asserting a lie.
        val plan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = null)
        val state = testNightState(lastPlan = plan, morningAlarmAt = "2026-09-17T07:00")
        val view = testEngineView(SleepState.AWAKE)

        val uiState = buildNightUiState(
            nightState = state,
            engineView = view,
            now = instant("2026-09-17T07:20"),
            zone = testZone,
            showingMorningReport = false,
            morningReportEndedAt = null,
            confirmingEndNight = false,
            pendingOutOfBedNudgeAt = instant("2026-09-17T07:15"),
        )

        val content = uiState?.content
        check(content is NightScreenContent.WokeUp) { "expected state C (WokeUp/napOnly), got $content" }
        assertNull(content.modeLabel)
    }

    @Test fun `forwards a real deadline all the way to the already-rang caption (should-fix 4)`() {
        // Should-fix 4 of the 09/21 review round 2: testNightState defaults to a null deadline, so every other
        // test in this file runs without one - a broken forward of NightSettings.deadline into
        // buildGoingToBedContent would silently kill must-fix 2's caption with every other test still green.
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = null, referenceOnset = "2026-09-17T00:00", cycles = 5)
        val state = testNightState(settings = NightSettings(instant("2026-09-17T07:30"), 5), lastPlan = plan, morningAlarmAt = "2026-09-17T07:00")
        val view = testEngineView(SleepState.ASLEEP)

        val uiState = buildNightUiState(
            nightState = state,
            engineView = view,
            now = instant("2026-09-17T07:05"),
            zone = testZone,
            showingMorningReport = false,
            morningReportEndedAt = null,
            confirmingEndNight = false,
            pendingOutOfBedNudgeAt = null,
        )

        val content = uiState?.content
        check(content is NightScreenContent.GoingToBedOrAsleep) { "expected state A, got $content" }
        assertEquals(NightSubtitle.ALREADY_RANG, content.subtitle)
        assertEquals("07:30", content.deadlineTimeLabel)
    }

    @Test fun `NAP-and-ASLEEP with an armed wakeAt always yields the NAP label (should-fix 5)`() {
        // Should-fix 5 of the 09/21 review round 2: NapAsleep.modeLabel's KDoc says it "should never be null"
        // here - this pins that invariant with an assertion that can actually fail, instead of leaving it as
        // prose. The type stays nullable on purpose (see NapAsleep's own KDoc): a crash on an unexpected null
        // would be worse than falling back to "no alarm armed" wording.
        val plan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = "2026-09-17T03:20", referenceOnset = "2026-09-17T03:00")
        val state = testNightState(lastPlan = plan, morningAlarmAt = "2026-09-17T07:00")
        val view = testEngineView(SleepState.ASLEEP)

        val uiState = buildNightUiState(
            nightState = state,
            engineView = view,
            now = instant("2026-09-17T03:05"),
            zone = testZone,
            showingMorningReport = false,
            morningReportEndedAt = null,
            confirmingEndNight = false,
            pendingOutOfBedNudgeAt = null,
        )

        val content = uiState?.content
        check(content is NightScreenContent.NapAsleep) { "expected NapAsleep, got $content" }
        assertEquals(AlarmLabel.NAP, content.modeLabel)
    }

    @Test fun `the pending nudge overrides an armed plan alarm when the nudge rings first (should-fix 1)`() {
        // Round 3 of the 09/21 review, should-fix 1: the traced sequence - morning alarm rings at 03:30, owner
        // stays in bed, plan becomes DEADLINE_ONLY at 07:30 while the nudge armed by that same ring is due at
        // 03:45. Before this fix, a non-null modeLabel (MORNING) was never reconsidered, so the header kept
        // showing "Morning alarm / 07:30" for those 15 minutes even though the nudge rings first - wrong per
        // AlarmModeHeader's own contract ("which alarm is coming next").
        val plan = testAlarmPlan(mode = AlarmMode.DEADLINE_ONLY, wakeAt = "2026-09-17T07:30", referenceOnset = "2026-09-17T23:00")
        val state = testNightState(lastPlan = plan)
        val view = testEngineView(SleepState.ASLEEP)

        val uiState = buildNightUiState(
            nightState = state,
            engineView = view,
            now = instant("2026-09-17T03:35"),
            zone = testZone,
            showingMorningReport = false,
            morningReportEndedAt = null,
            confirmingEndNight = false,
            pendingOutOfBedNudgeAt = instant("2026-09-17T03:45"),
        )

        val content = uiState?.content
        check(content is NightScreenContent.GoingToBedOrAsleep) { "expected state A, got $content" }
        assertEquals(AlarmLabel.OUT_OF_BED, content.modeLabel)
        assertEquals("03:45", content.modeLabelTimeLabel)
    }

    @Test fun `an armed plan alarm wins over the pending nudge when the plan alarm rings first (should-fix 1)`() {
        // The other direction of the same fix: a nudge that rings AFTER the plan's own alarm must not steal the
        // header - "which alarm is coming next" still means the plan alarm here.
        val plan = testAlarmPlan(mode = AlarmMode.DEADLINE_ONLY, wakeAt = "2026-09-17T03:30", referenceOnset = "2026-09-16T23:00")
        val state = testNightState(lastPlan = plan)
        val view = testEngineView(SleepState.ASLEEP)

        val uiState = buildNightUiState(
            nightState = state,
            engineView = view,
            now = instant("2026-09-17T03:00"),
            zone = testZone,
            showingMorningReport = false,
            morningReportEndedAt = null,
            confirmingEndNight = false,
            pendingOutOfBedNudgeAt = instant("2026-09-17T07:45"),
        )

        val content = uiState?.content
        check(content is NightScreenContent.GoingToBedOrAsleep) { "expected state A, got $content" }
        assertEquals(AlarmLabel.MORNING, content.modeLabel)
        assertNull(content.modeLabelTimeLabel)
    }
}
