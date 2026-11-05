package com.nikita.sleepcycle.night

// File purpose: Q2 - changing a running night's wake-by time and sleep length from the Night screen. The pure
// decision (decideNightSettingsChange), the editor's chip availability (nightSleepLengthFits), and how the
// change meets the tick that follows (decideTickPlan), edge case by edge case.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.SegmentKind
import com.nikita.sleepcycle.engine.SleepSegment
import com.nikita.sleepcycle.engine.computeAlarmPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private val ZONE: ZoneId = ZoneId.of("UTC")
private val CONFIG = EngineConfig()

class NightSettingsChangeTest {
    private val startedAt = Instant.parse("2026-11-04T23:00:00Z")
    private val onset = Instant.parse("2026-11-04T23:15:00Z")
    private val seven = Instant.parse("2026-11-05T07:00:00Z")
    private val six = Instant.parse("2026-11-05T06:00:00Z")
    private val threeAm = Instant.parse("2026-11-05T03:00:00Z")

    private fun at(time: String): Instant = Instant.parse("2026-11-05T${time}:00Z")
    private fun cycles(count: Long): Duration = CONFIG.cycleLength.multipliedBy(count)

    /** A night as the last tick left it at [now]: [segments] planned under [settings], the plan latched. */
    private fun night(settings: NightSettings, segments: List<SleepSegment>, now: Instant): NightState {
        val plan = computeAlarmPlan(segments, settings, now, null, ZONE, CONFIG, null, 0, null, null)
        return NightState(
            startedAt = startedAt, settings = settings, lastPlan = plan, lastSyncAt = now, lastSyncOk = true,
            lastSegments = segments, lastExportFileModifiedAt = null, lastSyncFailureCause = null,
            morningAlarmAt = latchMorningAlarmAt(null, plan),
        )
    }

    private fun asleepSince(from: Instant, until: Instant) = listOf(SleepSegment(from, until, SegmentKind.LIGHT))

    private fun accepted(decision: NightSettingsDecision): NightSettingsDecision.Accepted {
        assertTrue(decision is NightSettingsDecision.Accepted, "expected the change to be applied, was $decision")
        return decision as NightSettingsDecision.Accepted
    }

    @Test
    fun `asleep - an earlier wake-by time re-plans from the real onset and the night keeps its history`() {
        val state = night(NightSettings(seven, 5), asleepSince(onset, threeAm), threeAm)
            .copy(napAlarmsUsed = 1, lastNapAlarmFiredAt = at("01:00"), phoneAlarmFiredFor = at("01:00"))
        assertEquals(onset.plus(cycles(5)), state.lastPlan?.wakeAt, "before: 23:15 + 5 cycles = 06:45")

        val result = accepted(decideNightSettingsChange(state, NightSettings(six, 5), threeAm, ZONE))

        // Only 4 cycles fit before 06:00 from the 23:15 onset: 05:15.
        assertEquals(onset.plus(cycles(4)), result.plan.wakeAt)
        assertEquals(AlarmMode.FULL_CYCLES, result.plan.mode)
        assertEquals(NightSettings(six, 5), result.updatedState.settings)
        assertEquals(result.plan, result.updatedState.lastPlan)
        assertEquals(onset.plus(cycles(4)), result.updatedState.morningAlarmAt, "the new morning alarm is latched")
        assertEquals(state.lastSegments, result.updatedState.lastSegments)
        assertEquals(1, result.updatedState.napAlarmsUsed)
        assertEquals(at("01:00"), result.updatedState.lastNapAlarmFiredAt)
        assertEquals(state.startedAt, result.updatedState.startedAt)
    }

    @Test
    fun `asleep - a later wake-by time and a longer night move the alarm later`() {
        val state = night(NightSettings(seven, 5), asleepSince(onset, threeAm), threeAm)

        val result = accepted(decideNightSettingsChange(state, NightSettings(at("09:00"), 6), threeAm, ZONE))

        assertEquals(onset.plus(cycles(6)), result.plan.wakeAt, "23:15 + 6 cycles = 08:15")
    }

    @Test
    fun `not asleep yet - the new length counts from now plus the fall-asleep estimate`() {
        val now = Instant.parse("2026-11-04T23:40:00Z")
        val state = night(NightSettings(null, 5), emptyList(), now)

        val result = accepted(decideNightSettingsChange(state, NightSettings(null, 4), now, ZONE))

        assertEquals(now.plus(CONFIG.fallAsleepEstimate).plus(cycles(4)), result.plan.wakeAt)
        assertTrue(result.plan.onsetIsProjected)
    }

    @Test
    fun `switching to No alarm plans no alarm and drops the morning alarm time`() {
        val state = night(NightSettings(seven, 5), asleepSince(onset, threeAm), threeAm)

        val result = accepted(decideNightSettingsChange(state, NightSettings(null, 5, noAlarm = true), threeAm, ZONE))

        assertEquals(AlarmMode.NO_ALARM, result.plan.mode)
        assertNull(result.plan.wakeAt)
        assertNull(result.updatedState.morningAlarmAt)
    }

    @Test
    fun `switching from No alarm to an alarm plans one and closes an open Are you up ask`() {
        val noAlarm = NightSettings(null, 5, noAlarm = true)
        val state = night(noAlarm, asleepSince(onset, threeAm), threeAm)
            .copy(areYouUp = AreYouUpAsk(awakeningStartedAt = at("02:30"), askedAt = at("02:45")))
        assertNull(state.morningAlarmAt)

        val result = accepted(decideNightSettingsChange(state, NightSettings(seven, 5), threeAm, ZONE))

        assertEquals(onset.plus(cycles(5)), result.plan.wakeAt)
        assertEquals(onset.plus(cycles(5)), result.updatedState.morningAlarmAt)
        assertNull(result.updatedState.areYouUp, "an alarm night asks no Are you up?")
    }

    @Test
    fun `an answered Are you up ask stays in the history`() {
        val answered = AreYouUpAsk(at("02:30"), at("02:45"), AreYouUpAnswer.STILL_SLEEPING, at("02:50"))
        val state = night(NightSettings(null, 5, noAlarm = true), asleepSince(onset, threeAm), threeAm).copy(areYouUp = answered)

        val result = accepted(decideNightSettingsChange(state, NightSettings(seven, 5), threeAm, ZONE))

        assertEquals(answered, result.updatedState.areYouUp)
    }

    @Test
    fun `the same settings change nothing`() {
        val state = night(NightSettings(seven, 5), asleepSince(onset, threeAm), threeAm)

        assertEquals(NightSettingsDecision.Unchanged, decideNightSettingsChange(state, NightSettings(seven, 5), threeAm, ZONE))
    }

    @Test
    fun `a wake-by time at or before now is refused`() {
        val state = night(NightSettings(seven, 5), asleepSince(onset, threeAm), threeAm)

        assertEquals(
            NightSettingsDecision.Refused(NightSettingsRefusal.DEADLINE_PASSED),
            decideNightSettingsChange(state, NightSettings(threeAm, 5), threeAm, ZONE),
        )
        assertEquals(
            NightSettingsDecision.Refused(NightSettingsRefusal.DEADLINE_PASSED),
            decideNightSettingsChange(state, NightSettings(at("02:00"), 5), threeAm, ZONE),
        )
    }

    @Test
    fun `after the morning alarm rang nothing can be changed`() {
        val rangAt = onset.plus(cycles(5))
        val state = night(NightSettings(seven, 5), asleepSince(onset, rangAt), rangAt)
            .copy(wakeAlarmFiredAt = rangAt, phoneAlarmFiredFor = rangAt)

        assertFalse(canChangeNightSettings(state))
        assertEquals(
            NightSettingsDecision.Refused(NightSettingsRefusal.MORNING_ALARM_RANG),
            decideNightSettingsChange(state, NightSettings(at("09:00"), 6), rangAt.plusSeconds(60), ZONE),
        )
    }

    @Test
    fun `after I'm up on a No-alarm night nothing can be changed`() {
        val pressedAt = at("06:00")
        val state = night(NightSettings(null, 5, noAlarm = true), asleepSince(onset, pressedAt), pressedAt)
            .copy(wakeAlarmFiredAt = pressedAt)

        assertFalse(canChangeNightSettings(state))
        assertEquals(
            NightSettingsDecision.Refused(NightSettingsRefusal.MORNING_ALARM_RANG),
            decideNightSettingsChange(state, NightSettings(seven, 5), pressedAt, ZONE),
        )
    }

    @Test
    fun `a finished night cannot be changed`() {
        val state = night(NightSettings(seven, 5), asleepSince(onset, threeAm), threeAm)
            .copy(lastPlan = com.nikita.sleepcycle.engine.AlarmPlan(AlarmMode.FINISHED, null, 0, null, false, "done"))

        assertFalse(canChangeNightSettings(state))
        assertEquals(
            NightSettingsDecision.Refused(NightSettingsRefusal.NIGHT_FINISHED),
            decideNightSettingsChange(state, NightSettings(at("09:00"), 5), threeAm, ZONE),
        )
    }

    @Test
    fun `a pre-morning nap that already rang does not stop a change`() {
        // Woke at 02:00, back asleep 02:10, the engine's nap rang at 02:30: the morning alarm is still ahead.
        val segments = listOf(
            SleepSegment(onset, at("02:00"), SegmentKind.LIGHT),
            SleepSegment(at("02:00"), at("02:10"), SegmentKind.AWAKE),
            SleepSegment(at("02:10"), threeAm, SegmentKind.LIGHT),
        )
        val state = night(NightSettings(seven, 3), segments, threeAm)
            .copy(napAlarmsUsed = 1, lastNapAlarmFiredAt = at("02:30"), phoneAlarmFiredFor = at("02:30"))
        assertTrue(canChangeNightSettings(state))

        val result = accepted(decideNightSettingsChange(state, NightSettings(seven, 5), threeAm, ZONE))

        // 7.5 h total, 2 h 45 min slept before the awakening: 4 cycles still owed from 02:10, capped at 07:00 to 3.
        assertEquals(at("02:10").plus(cycles(3)), result.plan.wakeAt)
        assertEquals(1, result.updatedState.napAlarmsUsed)
    }

    @Test
    fun `awake in nap mode - a wake-by time before the latched morning alarm becomes the new morning alarm`() {
        // 3 cycles picked and already slept, awake since 04:00: the engine holds the latched morning alarm (05:45).
        val segments = listOf(SleepSegment(onset, at("04:00"), SegmentKind.LIGHT), SleepSegment(at("04:00"), at("04:20"), SegmentKind.AWAKE))
        val now = at("04:20")
        val state = night(NightSettings(seven, 3), segments, now).copy(morningAlarmAt = at("05:45"))
        val held = decideTickPlan(SyncOutcome(segments, now, true, null, null), state, now, ZONE, CONFIG).plan
        assertEquals(AlarmMode.NAP, held.mode)
        assertEquals(at("05:45"), held.wakeAt, "before the change the latched morning alarm is the one held")

        val result = accepted(decideNightSettingsChange(state, NightSettings(at("05:00"), 3), now, ZONE))

        assertEquals(at("05:00"), result.plan.wakeAt, "capped at the new wake-by time")
        assertEquals(at("05:00"), result.updatedState.morningAlarmAt, "and latched, so 05:45 can never ring after the wake-by time")
    }

    @Test
    fun `a wake-by time whose last whole cycle has passed rings soon, through the engine's own pull-forward`() {
        // Asleep since 23:15, at 05:30 the wake-by moves to 06:00: 4 cycles fit, but they ended at 05:15.
        val now = at("05:30")
        val state = night(NightSettings(seven, 5), asleepSince(onset, now), now)

        val result = accepted(decideNightSettingsChange(state, NightSettings(six, 5), now, ZONE))

        assertEquals(now.plus(CONFIG.minAlarmLead), result.plan.wakeAt, "now + 2 min, never after the wake-by time")
        assertTrue(result.plan.wakeAt!!.isBefore(six))
    }

    @Test
    fun `no whole cycle fits before the new wake-by time - the alarm rings at it, as Start night would plan it`() {
        val now = at("05:30")
        val state = night(NightSettings(seven, 5), emptyList(), now)

        val result = accepted(decideNightSettingsChange(state, NightSettings(at("06:30"), 5), now, ZONE))

        assertEquals(AlarmMode.DEADLINE_ONLY, result.plan.mode)
        assertEquals(at("06:30"), result.plan.wakeAt)
    }

    @Test
    fun `stale data - the change re-plans from the last known data, and the stale tick after it keeps the new plan`() {
        val state = night(NightSettings(seven, 5), asleepSince(onset, threeAm), threeAm)
            .copy(lastSyncOk = false, lastSyncFailureCause = "timed out")

        val changed = accepted(decideNightSettingsChange(state, NightSettings(six, 5), threeAm, ZONE)).updatedState
        assertEquals(onset.plus(cycles(4)), changed.lastPlan?.wakeAt)
        assertEquals(false, changed.lastSyncOk, "the sync status is history too")

        val later = at("03:15")
        val staleTick = decideTickPlan(SyncOutcome(changed.lastSegments, null, false, null, "timed out"), changed, later, ZONE, CONFIG)

        assertTrue(staleTick.keptPrevious)
        assertEquals(onset.plus(cycles(4)), staleTick.plan.wakeAt, "the stale tick keeps the changed alarm, not the old one")
    }

    @Test
    fun `a fresh tick after the change plans with the new settings`() {
        val state = night(NightSettings(seven, 5), asleepSince(onset, threeAm), threeAm)
        val changed = accepted(decideNightSettingsChange(state, NightSettings(six, 5), threeAm, ZONE)).updatedState

        val later = at("03:15")
        val tick = decideTickPlan(SyncOutcome(asleepSince(onset, later), later, true, null, null), changed, later, ZONE, CONFIG)

        assertEquals(onset.plus(cycles(4)), tick.plan.wakeAt)
    }

    @Test
    fun `a sleep length fits when its whole remaining total ends before the wake-by time`() {
        val state = night(NightSettings(seven, 5), asleepSince(onset, threeAm), threeAm)

        // From the 23:15 onset: 4 cycles 05:15, 5 cycles 06:45, 6 cycles 08:15.
        assertTrue(nightSleepLengthFits(state, 4, seven, threeAm, ZONE))
        assertTrue(nightSleepLengthFits(state, 5, seven, threeAm, ZONE))
        assertFalse(nightSleepLengthFits(state, 6, seven, threeAm, ZONE))
        assertTrue(nightSleepLengthFits(state, 6, null, threeAm, ZONE), "no wake-by time: everything fits")
        assertFalse(nightSleepLengthFits(state, 3, at("02:00"), threeAm, ZONE), "a wake-by time already passed fits nothing")
    }

    @Test
    fun `not asleep yet, a sleep length fits counting from now plus the fall-asleep estimate`() {
        val now = at("00:30")
        val state = night(NightSettings(seven, 5), emptyList(), now)

        // 00:45 + 4 cycles = 06:45, + 5 cycles = 08:15.
        assertTrue(nightSleepLengthFits(state, 4, seven, now, ZONE))
        assertFalse(nightSleepLengthFits(state, 5, seven, now, ZONE))
    }

    @Test
    fun `the morning alarm time after a change follows the new plan`() {
        val previous = at("06:30")
        fun plan(mode: AlarmMode, wakeAt: Instant?) = com.nikita.sleepcycle.engine.AlarmPlan(mode, wakeAt, 0, null, false, "r")

        assertEquals(at("05:00"), morningAlarmAfterSettingsChange(previous, plan(AlarmMode.FULL_CYCLES, at("05:00"))))
        assertEquals(at("05:00"), morningAlarmAfterSettingsChange(previous, plan(AlarmMode.NAP, at("05:00"))))
        assertEquals(previous, morningAlarmAfterSettingsChange(previous, plan(AlarmMode.NAP, null)))
        assertNull(morningAlarmAfterSettingsChange(previous, plan(AlarmMode.NO_ALARM, null)))
    }
}
