package com.nikita.sleepcycle.ui.state

// File purpose: 09/21 review's should-fix 11 - every other test in this package calls buildNextAlarmContent
// directly, so none of them exercise buildNightUiState's own job of forwarding NightState.morningAlarmAt, the
// pending out-of-bed nudge and the caller's clock into it. That wiring is what this review's must-fix 1
// depends on: if buildNightUiState silently passed null instead of state.morningAlarmAt, every
// content-builder test would still pass, and the "no alarm armed" / already-rang cases would never actually
// reach the screen. This file goes through buildNightUiState itself.

import com.nikita.sleepcycle.alarm.AlarmLabel
import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.SleepState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BuildNightUiStateTest {
    @Test fun `forwards the night's latched morning-alarm time into the already-rang case`() {
        // H8: the morning alarm has already rung (wakeAt null) while the band still reads ASLEEP.
        // NightState.morningAlarmAt is the only place that rung time is latched - if buildNightUiState failed
        // to forward it, the screen would show the bare dash with nothing under it to explain the silence.
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = null, referenceOnset = "2026-09-17T00:00", cycles = 5)
        val state = testNightState(lastPlan = plan, morningAlarmAt = "2026-09-17T07:00")

        val content = nightContent(state, SleepState.ASLEEP, now = "2026-09-17T07:05", pendingOutOfBedNudgeAt = null)

        assertEquals(MISSING_TIME_LABEL, content.alarmTimeLabel)
        assertEquals("07:00", content.rangAtTimeLabel)
    }

    @Test fun `forwards the night's latched morning-alarm time into the H8 sliding-nap case`() {
        // Should-fix 3 of the 09/21 review round 2: a test passing wakeAt = null short-circuits alarmModeLabel
        // before morningAlarmAt is ever read, so hardcoding null at the call site would still pass it. Rule 7's
        // sliding nap (H8) is the case that actually exercises the forward: a NAP plan whose wakeAt equals the
        // still-pending morning alarm must be labelled MORNING, not NAP - otherwise the screen would say "Nap
        // alarm" for the exact same instant the phone is about to ring labelled "Morning alarm".
        val plan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = "2026-09-17T06:58")
        val state = testNightState(lastPlan = plan, morningAlarmAt = "2026-09-17T06:58")

        val content = nightContent(state, SleepState.AWAKE, now = "2026-09-17T06:55", pendingOutOfBedNudgeAt = null)

        assertEquals(AlarmLabel.MORNING, content.modeLabel)
    }

    @Test fun `forwards the caller's own clock into the countdown`() {
        // N1: the countdown is the one thing on this screen derived from `now` rather than from the plan, and
        // on a simulated night `now` is the warped clock (AppClock.kt), not the wall clock. A dropped forward
        // would leave every countdown computed against the wrong clock while every other assertion here
        // stayed green.
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T07:00", referenceOnset = "2026-09-17T00:00", cycles = 5)
        val state = testNightState(lastPlan = plan)

        val content = nightContent(state, SleepState.ASLEEP, now = "2026-09-17T06:23", pendingOutOfBedNudgeAt = null)

        assertEquals("37 min", content.countdownLabel)
    }

    @Test fun `the no-alarm-armed case shows the pending out-of-bed nudge instead of a false negative`() {
        // Round 2 of the 09/21 review, must-fix 1: F5's "no alarm armed" reading co-occurs, every time, with
        // the out-of-bed nudge PhoneAlarmReceiver just armed for the real firing that produced this AWAKE
        // reading in the first place. Reproduces the review's traced sequence: morning alarm rings 07:00, nudge
        // armed for 07:15, band reports AWAKE at 07:05 with nothing left for rule 7's sliding nap to arm.
        val plan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = null)
        val state = testNightState(lastPlan = plan, morningAlarmAt = "2026-09-17T07:00")

        val content = nightContent(state, SleepState.AWAKE, now = "2026-09-17T07:05", pendingOutOfBedNudgeAt = "2026-09-17T07:15")

        assertEquals(AlarmLabel.OUT_OF_BED, content.modeLabel)
        assertEquals("07:15", content.alarmTimeLabel)
        assertEquals("10 min", content.countdownLabel)
    }

    @Test fun `the already-rang case also shows the pending out-of-bed nudge, and drops the rung time`() {
        // The review's own note: "the same applies to state A's already-rang case between the ring and the
        // pre-check" - that branch reaches a null modeLabel exactly the same way F5's does. N1 adds the second
        // half: with a live countdown to show, the line under the hero belongs to the countdown, so the rung
        // time is cleared rather than left to compete for the same line.
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = null, referenceOnset = "2026-09-17T00:00", cycles = 5)
        val state = testNightState(lastPlan = plan, morningAlarmAt = "2026-09-17T07:00")

        val content = nightContent(state, SleepState.ASLEEP, now = "2026-09-17T07:05", pendingOutOfBedNudgeAt = "2026-09-17T07:15")

        assertEquals(AlarmLabel.OUT_OF_BED, content.modeLabel)
        assertEquals("07:15", content.alarmTimeLabel)
        assertNull(content.rangAtTimeLabel)
    }

    @Test fun `a nudge that has already fired does not resurrect the no-alarm-armed reading`() {
        // A stale/past pendingOutOfBedNudgeAt (the nudge already fired, or the read raced a cancel) must not
        // show an alarm that is no longer armed - the whole point of must-fix 1 is not asserting a lie.
        val plan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = null)
        val state = testNightState(lastPlan = plan, morningAlarmAt = "2026-09-17T07:00")

        val content = nightContent(state, SleepState.AWAKE, now = "2026-09-17T07:20", pendingOutOfBedNudgeAt = "2026-09-17T07:15")

        assertNull(content.modeLabel)
        assertEquals(MISSING_TIME_LABEL, content.alarmTimeLabel)
    }

    @Test fun `NAP-and-ASLEEP with an armed wakeAt always yields the NAP label (should-fix 5)`() {
        // Should-fix 5 of the 09/21 review round 2: this is the one path on which the label should never be
        // null, and this pins that with an assertion that can actually fail instead of leaving it as prose.
        // The type stays nullable on purpose: a crash on an unexpected null would be worse than falling back
        // to the "no alarm armed" wording.
        val plan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = "2026-09-17T03:20", referenceOnset = "2026-09-17T03:00")
        val state = testNightState(lastPlan = plan, morningAlarmAt = "2026-09-17T07:00")

        val content = nightContent(state, SleepState.ASLEEP, now = "2026-09-17T03:05", pendingOutOfBedNudgeAt = null)

        assertEquals(AlarmLabel.NAP, content.modeLabel)
        assertEquals("03:20", content.alarmTimeLabel)
    }

    @Test fun `the end-night action follows the mode, not the band's sleep state`() {
        // N1 dropped the sleep state from the content dispatch entirely, leaving the mode as the only thing
        // that picks the button's wording. NAP is the one mode reached only after the owner has already been
        // woken once, whether the band currently reads AWAKE or ASLEEP.
        val napPlan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = "2026-09-17T03:20", referenceOnset = "2026-09-17T03:00")
        val fullPlan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T07:00", referenceOnset = "2026-09-17T00:00", cycles = 5)

        assertEquals(EndNightAction.IM_UP, endAction(testNightState(lastPlan = napPlan), SleepState.ASLEEP))
        assertEquals(EndNightAction.IM_UP, endAction(testNightState(lastPlan = napPlan), SleepState.AWAKE))
        assertEquals(EndNightAction.STOP, endAction(testNightState(lastPlan = fullPlan), SleepState.ASLEEP))
        assertEquals(EndNightAction.STOP, endAction(testNightState(lastPlan = fullPlan), SleepState.AWAKE))
    }

    @Test fun `the pending nudge overrides an armed plan alarm when the nudge rings first (should-fix 1)`() {
        // Round 3 of the 09/21 review, should-fix 1: the traced sequence - morning alarm rings at 03:30, owner
        // stays in bed, plan becomes DEADLINE_ONLY at 07:30 while the nudge armed by that same ring is due at
        // 03:45. Before this fix, a non-null modeLabel (MORNING) was never reconsidered, so the screen kept
        // showing the 07:30 alarm for those 15 minutes even though the nudge rings first.
        val plan = testAlarmPlan(mode = AlarmMode.DEADLINE_ONLY, wakeAt = "2026-09-17T07:30", referenceOnset = "2026-09-17T23:00")
        val state = testNightState(lastPlan = plan)

        val content = nightContent(state, SleepState.ASLEEP, now = "2026-09-17T03:35", pendingOutOfBedNudgeAt = "2026-09-17T03:45")

        assertEquals(AlarmLabel.OUT_OF_BED, content.modeLabel)
        assertEquals("03:45", content.alarmTimeLabel)
        assertEquals("10 min", content.countdownLabel)
    }

    @Test fun `an armed plan alarm wins over the pending nudge when the plan alarm rings first (should-fix 1)`() {
        // The other direction of the same fix: a nudge that rings AFTER the plan's own alarm must not steal the
        // screen - "which alarm is coming next" still means the plan alarm here.
        val plan = testAlarmPlan(mode = AlarmMode.DEADLINE_ONLY, wakeAt = "2026-09-17T03:30", referenceOnset = "2026-09-16T23:00")
        val state = testNightState(lastPlan = plan)

        val content = nightContent(state, SleepState.ASLEEP, now = "2026-09-17T03:00", pendingOutOfBedNudgeAt = "2026-09-17T07:45")

        assertEquals(AlarmLabel.MORNING, content.modeLabel)
        assertEquals("03:30", content.alarmTimeLabel)
    }
}

/**
 * N2 (owner-reported, 2026-09-21): a FINISHED plan with a nudge still armed was drawn as the plain "Night
 * finished" screen, which says the night is over while an alarm is minutes away from ringing. The owner hit
 * this on his own phone and asked, reasonably, whether the deadline had stopped the nudges - it had not
 * (`night_end_deferred`, cause `out_of_bed_nudge_pending`, then a nudge ringing 35 virtual minutes later, in
 * `night-sim-20260921-2151`), but the screen gave him no way to know that. L2 already decided a pending nudge
 * outlives FINISHED; this is that decision reaching the screen.
 */
class FinishedNightWithPendingNudgeTest {
    private val finishedPlan = testAlarmPlan(mode = AlarmMode.FINISHED, reason = "Night finished, deadline was 07:30.")

    @Test fun `a finished night with a nudge still armed shows the nudge, not the finished screen`() {
        val state = testNightState(lastPlan = finishedPlan)

        val content = nightContent(state, SleepState.AWAKE, now = "2026-09-17T07:35", pendingOutOfBedNudgeAt = "2026-09-17T07:45")

        assertEquals(AlarmLabel.OUT_OF_BED, content.modeLabel)
        assertEquals("07:45", content.alarmTimeLabel)
        assertEquals("10 min", content.countdownLabel)
    }

    @Test fun `that screen offers I'm up, end night rather than the bare End night`() {
        // The owner's own words on what he saw: "at the bottom, it didn't say that I'm awake and the night.
        // It's just like end night." With an alarm still coming, this is an ordinary live night as far as the
        // owner is concerned, so it gets the live night's own wording.
        val state = testNightState(lastPlan = finishedPlan)

        assertEquals(EndNightAction.IM_UP, endActionWith(state, pendingOutOfBedNudgeAt = "2026-09-17T07:45"))
    }

    @Test fun `a finished night with nothing armed still shows the finished screen`() {
        val state = testNightState(lastPlan = finishedPlan)

        val uiState = uiStateFor(state, now = "2026-09-17T07:35", pendingOutOfBedNudgeAt = null)

        val content = uiState.content
        check(content is NightScreenContent.NightFinished) { "expected the finished screen, got $content" }
        assertEquals("Night finished, deadline was 07:30.", content.reasonText)
        assertEquals(EndNightAction.END, uiState.endAction)
    }

    @Test fun `a finished night whose nudge has already fired still shows the finished screen`() {
        // A stale or past pending instant must not resurrect a dead alarm here any more than anywhere else.
        val state = testNightState(lastPlan = finishedPlan)

        val uiState = uiStateFor(state, now = "2026-09-17T07:50", pendingOutOfBedNudgeAt = "2026-09-17T07:45")

        check(uiState.content is NightScreenContent.NightFinished) { "expected the finished screen, got ${uiState.content}" }
        assertEquals(EndNightAction.END, uiState.endAction)
    }

    @Test fun `the morning report is never overridden by a pending nudge`() {
        // The report is shown after the night has genuinely been ended, which cancels the nudge - but the
        // caller still passes whatever it last read, so this pins that the report wins regardless.
        val uiState = checkNotNull(
            buildNightUiState(
                nightState = null,
                engineView = testEngineView(SleepState.AWAKE),
                now = instant("2026-09-17T07:35"),
                zone = testZone,
                showingMorningReport = true,
                morningReportEndedAt = instant("2026-09-17T07:35"),
                confirmingEndNight = false,
                pendingOutOfBedNudgeAt = instant("2026-09-17T07:45"),
            )
        )

        check(uiState.content is NightScreenContent.MorningReport) { "expected the morning report, got ${uiState.content}" }
    }
}

private fun uiStateFor(
    state: com.nikita.sleepcycle.night.NightState,
    now: String,
    pendingOutOfBedNudgeAt: String?,
    sleepState: SleepState = SleepState.AWAKE,
): NightUiState = checkNotNull(
    buildNightUiState(
        nightState = state,
        engineView = testEngineView(sleepState),
        now = instant(now),
        zone = testZone,
        showingMorningReport = false,
        morningReportEndedAt = null,
        confirmingEndNight = false,
        pendingOutOfBedNudgeAt = pendingOutOfBedNudgeAt?.let(::instant),
    )
)

/** Runs buildNightUiState on a live night and returns the one content variant it produces, failing loudly otherwise. */
private fun nightContent(
    state: com.nikita.sleepcycle.night.NightState,
    sleepState: SleepState,
    now: String,
    pendingOutOfBedNudgeAt: String?,
): NightScreenContent.NextAlarm {
    val content = uiStateFor(state, now, pendingOutOfBedNudgeAt, sleepState).content
    check(content is NightScreenContent.NextAlarm) { "expected a live night, got $content" }
    return content
}

private fun endAction(state: com.nikita.sleepcycle.night.NightState, sleepState: SleepState): EndNightAction =
    uiStateFor(state, now = "2026-09-17T03:05", pendingOutOfBedNudgeAt = null, sleepState = sleepState).endAction

private fun endActionWith(state: com.nikita.sleepcycle.night.NightState, pendingOutOfBedNudgeAt: String): EndNightAction =
    uiStateFor(state, now = "2026-09-17T07:35", pendingOutOfBedNudgeAt = pendingOutOfBedNudgeAt).endAction
