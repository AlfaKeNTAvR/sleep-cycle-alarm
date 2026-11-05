package com.nikita.sleepcycle.ui.state

// File purpose: Q2 - the Night screen's alarm editor: prefilled from the running night, Before bed's picker
// rules with the running night's fit, what it previews, when it refuses, and when the Night screen offers it.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.SegmentKind
import com.nikita.sleepcycle.engine.SleepSegment
import com.nikita.sleepcycle.engine.computeAlarmPlan
import com.nikita.sleepcycle.night.NightState
import com.nikita.sleepcycle.night.buildNightEngineView
import com.nikita.sleepcycle.night.latchMorningAlarmAt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalTime

class AlarmEditorTest {
    private val startedAt = instant("2026-11-04T23:00")
    private val onset = instant("2026-11-04T23:15")
    private val threeAm = instant("2026-11-05T03:00")
    private val sevenAm = instant("2026-11-05T07:00")

    /** A night asleep since 23:15, last planned at [now] under [settings]. */
    private fun asleepNight(settings: NightSettings, now: Instant = threeAm): NightState {
        val segments = listOf(SleepSegment(onset, now, SegmentKind.LIGHT))
        val plan = computeAlarmPlan(segments, settings, now, null, testZone, EngineConfig(), null, 0, null, null)
        return NightState(
            startedAt = startedAt, settings = settings, lastPlan = plan, lastSyncAt = now, lastSyncOk = true,
            lastSegments = segments, lastExportFileModifiedAt = null, lastSyncFailureCause = null,
            morningAlarmAt = latchMorningAlarmAt(null, plan),
        )
    }

    private fun editorFor(state: NightState) =
        openAlarmEditorDraft(testAppSettings(lastDeadline = LocalTime.of(8, 0)), state.settings, testZone)

    @Test
    fun `opens on the running night's own settings, and saving them unchanged changes nothing`() {
        val state = asleepNight(NightSettings(sevenAm, 5))

        val draft = editorFor(state)

        assertTrue(draft.choice.deadlineEnabled)
        assertEquals(LocalTime.of(7, 0), draft.choice.lastDeadline)
        assertEquals(5, draft.choice.pickedCycles)
        assertFalse(draft.choice.noAlarm)
        assertEquals(state.settings, nightSettingsForChoice(draft.choice, startedAt, testZone), "the same instant Start night gave the deadline")
    }

    @Test
    fun `opens on None for a No-alarm night, keeping the remembered wake-by time for the switch`() {
        val state = asleepNight(NightSettings(null, 5, noAlarm = true))

        val draft = editorFor(state)

        assertTrue(draft.choice.noAlarm)
        assertFalse(draft.choice.deadlineEnabled)
        assertEquals(LocalTime.of(8, 0), draft.choice.lastDeadline)
        assertEquals(state.settings, nightSettingsForChoice(draft.choice, startedAt, testZone))
    }

    @Test
    fun `chips are hatched by tonight's own fit, counted from the real onset`() {
        val state = asleepNight(NightSettings(sevenAm, 5))

        val ui = buildAlarmEditorUiState(editorFor(state), state, threeAm, testZone)

        // From 23:15: 4.5 h ends 03:45, 6 h 05:15, 7.5 h 06:45 - all before 07:00; 9 h ends 08:15.
        assertEquals(listOf(true, true, true, false), ui.sleepLengthOptions.map { it.available })
        assertEquals(listOf(false, false, true, false), ui.sleepLengthOptions.map { it.selected })
    }

    @Test
    fun `an earlier wake-by time moves a length that no longer fits to the longest one that does`() {
        val state = asleepNight(NightSettings(sevenAm, 5))

        val choice = withEditorDeadlineTime(editorFor(state).choice, LocalTime.of(6, 0), state, threeAm, testZone)

        assertEquals(4, choice.pickedCycles, "7.5 h ends 06:45, after 06:00; 6 h ends 05:15")
        assertEquals(LocalTime.of(6, 0), choice.lastDeadline)
    }

    @Test
    fun `opening the editor never moves the night's own length, even when it does not fit`() {
        // Night picked 9 h with a 07:00 wake-by time: only 7.5 h fits, the engine caps it.
        val state = asleepNight(NightSettings(sevenAm, 6))

        val ui = buildAlarmEditorUiState(editorFor(state), state, threeAm, testZone)

        assertEquals(listOf(false, false, false, true), ui.sleepLengthOptions.map { it.selected })
        assertFalse(ui.sleepLengthOptions.last().available)
    }

    @Test
    fun `switching the wake-by time on from None picks the longest length that fits tonight`() {
        val state = asleepNight(NightSettings(null, 3, noAlarm = true))
        val none = editorFor(state).choice.copy(lastDeadline = LocalTime.of(7, 0))

        val choice = withEditorDeadlineSwitched(none, enabled = true, state, threeAm, testZone)

        assertFalse(choice.noAlarm)
        assertTrue(choice.deadlineEnabled)
        assertEquals(5, choice.pickedCycles, "7.5 h from 23:15 ends 06:45, 9 h would end 08:15")
    }

    @Test
    fun `a wake-by time that already came round tonight is refused, not taken as tomorrow`() {
        val state = asleepNight(NightSettings(sevenAm, 5))
        val choice = editorFor(state).choice.copy(lastDeadline = LocalTime.of(2, 0))

        val ui = buildAlarmEditorUiState(AlarmEditorDraft(choice), state, threeAm, testZone)

        assertEquals(AlarmEditorProblem.DEADLINE_PASSED, ui.problem)
        assertFalse(ui.saveEnabled)
        assertNull(ui.newAlarmTimeLabel)
        assertEquals(instant("2026-11-05T02:00"), editedDeadline(choice, startedAt, testZone))
    }

    @Test
    fun `previews the alarm the choice would set`() {
        val state = asleepNight(NightSettings(sevenAm, 5))
        val shorter = withSleepLengthPicked(editorFor(state).choice, 4)

        val ui = buildAlarmEditorUiState(AlarmEditorDraft(shorter), state, threeAm, testZone)

        assertEquals("05:15", ui.newAlarmTimeLabel)
        assertFalse(ui.newAlarmAtWakeBy)
        assertTrue(ui.saveEnabled)
    }

    @Test
    fun `previews No alarm for None`() {
        val state = asleepNight(NightSettings(sevenAm, 5))

        val ui = buildAlarmEditorUiState(AlarmEditorDraft(withNoAlarmPicked(editorFor(state).choice)), state, threeAm, testZone)

        assertTrue(ui.noAlarmSelected)
        assertFalse(ui.deadlineEnabled, "None turns the wake-by time off, as on Before bed")
        assertNull(ui.newAlarmTimeLabel)
    }

    @Test
    fun `previews the wake-by time itself when no whole cycle fits before it`() {
        val now = instant("2026-11-05T05:30")
        val segments = emptyList<SleepSegment>()
        val settings = NightSettings(sevenAm, 5)
        val plan = computeAlarmPlan(segments, settings, now, null, testZone, EngineConfig(), null, 0, null, null)
        val state = NightState(startedAt, settings, plan, now, true, segments, null, null)
        val choice = editorFor(state).choice.copy(lastDeadline = LocalTime.of(6, 30))

        val ui = buildAlarmEditorUiState(AlarmEditorDraft(choice), state, now, testZone)

        assertEquals("06:30", ui.newAlarmTimeLabel)
        assertTrue(ui.newAlarmAtWakeBy)
        assertTrue(ui.saveEnabled, "the same plan Start night would make at 05:30 with a 06:30 wake-by time")
    }

    @Test
    fun `the Night screen offers the editor on a live night and shows it while open`() {
        val state = asleepNight(NightSettings(sevenAm, 5))

        val closed = nightUiState(state, draft = null)
        val open = nightUiState(state, draft = editorFor(state))

        assertTrue(closed.alarmEditable)
        assertNull(closed.alarmEditor)
        assertEquals(LocalTime.of(7, 0), open.alarmEditor?.deadlineTime)
    }

    @Test
    fun `the Night screen stops offering the editor once the morning alarm rang`() {
        val rangAt = onset.plus(EngineConfig().cycleLength.multipliedBy(5))
        val state = asleepNight(NightSettings(sevenAm, 5), now = rangAt).copy(wakeAlarmFiredAt = rangAt, phoneAlarmFiredFor = rangAt)

        val ui = nightUiState(state, draft = editorFor(state), now = rangAt.plusSeconds(60))

        assertFalse(ui.alarmEditable)
        assertNull(ui.alarmEditor, "an editor left open closes")
    }

    @Test
    fun `the Night screen does not offer the editor while the night is ending`() {
        val state = asleepNight(NightSettings(sevenAm, 5))

        assertFalse(nightUiState(state, draft = null, endingNight = true).alarmEditable)
    }

    private fun nightUiState(state: NightState, draft: AlarmEditorDraft?, now: Instant = threeAm, endingNight: Boolean = false): NightUiState =
        checkNotNull(
            buildNightUiState(
                state, buildNightEngineView(state, now), now, testZone, showingMorningReport = false, morningReportEndedAt = null,
                confirmingEndNight = false, endingNight = endingNight, pendingFollowUp = null, alarmEditorDraft = draft,
            )
        )
}
