package com.nikita.sleepcycle.ui.state

// File purpose: P3 (owner spec, 2026-09-30) - what the Night screen shows around the post-alarm cycle: the
// "Nap 20 min" button while the out-of-bed nudge is pending, and the owner's own nap, once chosen, in the same
// N1 three-line layout every other alarm uses. Goes through buildNightUiState itself, the screen's own seam.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.SleepState
import com.nikita.sleepcycle.night.PendingFollowUp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NightScreenNapTest {
    /** The morning alarm rang at 07:00; the engine arms nothing after it (P3), so the plan has no alarm of its own. */
    private val afterMorningAlarm = testNightState(
        lastPlan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = null),
        morningAlarmAt = "2026-09-17T07:00",
    )

    private fun screenAt(now: String, followUp: PendingFollowUp?, state: com.nikita.sleepcycle.night.NightState = afterMorningAlarm): NightUiState =
        checkNotNull(
            buildNightUiState(
                nightState = state,
                engineView = testEngineView(SleepState.AWAKE),
                now = instant(now),
                zone = testZone,
                showingMorningReport = false,
                morningReportEndedAt = null,
                confirmingEndNight = false,
                pendingFollowUp = followUp,
            )
        )

    @Test fun `with the nudge pending at 07_13 the screen offers Nap 20 min`() {
        val screen = screenAt("2026-09-17T07:05", pendingNudge("2026-09-17T07:13"))

        assertEquals(20, screen.napButtonMinutes)
    }

    @Test fun `once Nap is pressed at 07_05 the screen names the nap alarm, its 07_25 time and the 20 min countdown`() {
        val content = screenAt("2026-09-17T07:05", pendingNap("2026-09-17T07:25")).content

        assertEquals(
            NightScreenContent.NextAlarm(
                modeLabel = com.nikita.sleepcycle.alarm.AlarmLabel.NAP,
                alarmTimeLabel = "07:25",
                countdownLabel = "20 min",
                rangAtTimeLabel = null,
            ),
            content,
        )
    }

    @Test fun `while the nap is armed the Nap button is not offered again`() {
        assertEquals(null, screenAt("2026-09-17T07:10", pendingNap("2026-09-17T07:25")).napButtonMinutes)
    }

    @Test fun `with nothing pending there is no Nap button`() {
        assertEquals(null, screenAt("2026-09-17T07:10", followUp = null).napButtonMinutes)
    }

    @Test fun `a nudge whose time has already passed does not offer the Nap button`() {
        // The record is read on a ticker; between the nudge ringing and the receiver rewriting it, a stale past
        // instant must not offer a trade for an alarm that has already rung.
        assertEquals(null, screenAt("2026-09-17T07:14", pendingNudge("2026-09-17T07:13")).napButtonMinutes)
    }

    @Test fun `the Nap button is offered on a deadline night past FINISHED while the nudge chain keeps going`() {
        // L2 keeps the nudge repeating past the deadline; the owner can still choose a nap there.
        val finished = testNightState(lastPlan = testAlarmPlan(mode = AlarmMode.FINISHED, reason = "Night finished, deadline was 07:30."))

        val screen = screenAt("2026-09-17T07:35", pendingNudge("2026-09-17T07:45"), state = finished)

        assertEquals(20, screen.napButtonMinutes)
        assertTrue(screen.showEndNightButton)
    }

    @Test fun `a nap pending past FINISHED is drawn as the live nap, with I'm up and no End night`() {
        val finished = testNightState(lastPlan = testAlarmPlan(mode = AlarmMode.FINISHED, reason = "Night finished, deadline was 07:30."))

        val screen = screenAt("2026-09-17T07:35", pendingNap("2026-09-17T07:55"), state = finished)

        val content = screen.content
        check(content is NightScreenContent.NextAlarm) { "expected the live layout, got $content" }
        assertEquals(com.nikita.sleepcycle.alarm.AlarmLabel.NAP, content.modeLabel)
        assertEquals("07:55", content.alarmTimeLabel)
        assertTrue(screen.showImUpButton)
        assertFalse(screen.showEndNightButton)
    }

    @Test fun `the morning report never offers the Nap button`() {
        val screen = checkNotNull(
            buildNightUiState(
                nightState = null,
                engineView = testEngineView(SleepState.AWAKE),
                now = instant("2026-09-17T07:35"),
                zone = testZone,
                showingMorningReport = true,
                morningReportEndedAt = instant("2026-09-17T07:35"),
                confirmingEndNight = false,
                pendingFollowUp = pendingNudge("2026-09-17T07:45"),
            )
        )

        assertEquals(null, screen.napButtonMinutes)
    }
}
