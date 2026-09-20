package com.nikita.sleepcycle.night

// File purpose: a simulated night, replayed through the REAL engine (computeAlarmPlan) with the fast debug
// EngineConfig (resolveEngineConfig(DebugOptions(fastNight = true))) and segments built the same way the
// Debug screen's simulator would build them (BandDataSimulator.kt). Mirrors the style of :engine's own
// WholeNightSequenceTest: feeds each plan's own wakeAt back in as morningAlarmAt and asserts mode and wakeAt
// at every step - a faithful stand-in for the app's real latch (NightOrchestrator.latchMorningAlarmAt) here,
// since none of these sequences chain two NAP plans in the AWAKE state (see WholeNightSequenceTest's own doc).
// Covers: start, fell asleep, waking early enough that a full cycle still fits (restart), falling back asleep,
// waking with the picked total all but used up so that only a nap is left, and falling back asleep for the
// nap. D8 removed the OVERDUE mode and its cap this file used to walk into afterward - the phone alarm now
// fires exactly on time instead, so there is no further escalation to exercise here. Every instant below is
// hand-derived from the engine's own formulas (WakeAlarm.kt, PlanSteps.kt).

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.computeAlarmPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

class FastNightSequenceTest {
    private val zone = ZoneOffset.UTC
    private val config = resolveEngineConfig(DebugOptions(fastNight = true))
    private val settings = NightSettings(deadline = null, pickedCycles = 3)

    /** Accepts "HH:mm" or "HH:mm:ss" and parses it as an instant on a fixed date, so the test's own instants read like clock times. */
    private fun at(clockTime: String): Instant {
        val normalized = if (clockTime.count { it == ':' } == 1) "$clockTime:00" else clockTime
        return Instant.parse("2026-09-17T${normalized}Z")
    }

    @Test
    fun `a fast simulated night replays start, restart and nap entry in order`() {
        var events = emptyList<SimulatedSleepEvent>()

        // Tick 0: before any sleep, one minute before the projected fall-asleep time. 3 picked cycles * 5 min = 15 min.
        val plan0 = computeAlarmPlan(buildSimulatedSegments(events, at("21:59")), settings, at("21:59"), null, zone, config, wakeAlarmFiredAt = null, napAlarmsUsed = 0, lastNapAlarmFiredAt = null)
        assertEquals(AlarmMode.FULL_CYCLES, plan0.mode)
        assertEquals(at("22:15"), plan0.wakeAt)

        // "I fell asleep now" at 22:00:00; next tick at 22:00:30.
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.FELL_ASLEEP, at("22:00"))
        val plan1 = computeAlarmPlan(buildSimulatedSegments(events, at("22:00:30")), settings, at("22:00:30"), plan0.wakeAt, zone, config, wakeAlarmFiredAt = null, napAlarmsUsed = 0, lastNapAlarmFiredAt = null)
        assertEquals(AlarmMode.FULL_CYCLES, plan1.mode)
        assertEquals(at("22:15"), plan1.wakeAt)

        // "I woke up now" at 22:01:00, well before the 22:15 alarm - next tick (still awake) restarts a full count.
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.WOKE_UP, at("22:01"))
        val plan2 = computeAlarmPlan(buildSimulatedSegments(events, at("22:01:30")), settings, at("22:01:30"), plan1.wakeAt, zone, config, wakeAlarmFiredAt = null, napAlarmsUsed = 0, lastNapAlarmFiredAt = null)
        assertEquals(AlarmMode.FULL_CYCLES, plan2.mode)
        assertEquals(at("22:17:30"), plan2.wakeAt)

        // "Fell back asleep now" at 22:02:00 - a short cycle still clearly fits, so this is still FULL_CYCLES.
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.FELL_BACK_ASLEEP, at("22:02"))
        val plan3 = computeAlarmPlan(buildSimulatedSegments(events, at("22:03")), settings, at("22:03"), plan2.wakeAt, zone, config, wakeAlarmFiredAt = null, napAlarmsUsed = 0, lastNapAlarmFiredAt = null)
        assertEquals(AlarmMode.FULL_CYCLES, plan3.mode)
        assertEquals(at("22:17"), plan3.wakeAt)

        // "I woke up now" at 22:14:00, with 13 of the picked 15 min already slept: less than half a short
        // cycle is left, so nothing whole is still owed and only a nap fits. (There is no deadline here, so
        // the 22:17 alarm of the previous plan has no say in this - only the picked total does.)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.WOKE_UP, at("22:14"))
        val plan4 = computeAlarmPlan(buildSimulatedSegments(events, at("22:14:30")), settings, at("22:14:30"), plan3.wakeAt, zone, config, wakeAlarmFiredAt = null, napAlarmsUsed = 0, lastNapAlarmFiredAt = null)
        assertEquals(AlarmMode.NAP, plan4.mode)
        assertEquals(at("22:17:30"), plan4.wakeAt)

        // "Fell back asleep now" at 22:15:00 for the nap. Unlike the old (1 min nap / 1 min lead) config, the
        // nap alarm is still comfortably ahead of now + minAlarmLead here, so NAP holds on this tick too -
        // the tester actually sees the nap card, not an immediate pull-forward.
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.FELL_BACK_ASLEEP, at("22:15"))
        val plan5 = computeAlarmPlan(buildSimulatedSegments(events, at("22:15:15")), settings, at("22:15:15"), plan4.wakeAt, zone, config, wakeAlarmFiredAt = null, napAlarmsUsed = 0, lastNapAlarmFiredAt = null)
        assertEquals(AlarmMode.NAP, plan5.mode)
        assertEquals(at("22:18"), plan5.wakeAt)
    }

    @Test
    fun `a nap detected fell-back-asleep holds NAP rather than any other mode`() {
        var events = emptyList<SimulatedSleepEvent>()
        var previous = computeAlarmPlan(buildSimulatedSegments(events, at("21:59")), settings, at("21:59"), null, zone, config, wakeAlarmFiredAt = null, napAlarmsUsed = 0, lastNapAlarmFiredAt = null)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.FELL_ASLEEP, at("22:00"))
        previous = computeAlarmPlan(buildSimulatedSegments(events, at("22:00:30")), settings, at("22:00:30"), previous.wakeAt, zone, config, wakeAlarmFiredAt = null, napAlarmsUsed = 0, lastNapAlarmFiredAt = null)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.WOKE_UP, at("22:01"))
        previous = computeAlarmPlan(buildSimulatedSegments(events, at("22:01:30")), settings, at("22:01:30"), previous.wakeAt, zone, config, wakeAlarmFiredAt = null, napAlarmsUsed = 0, lastNapAlarmFiredAt = null)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.FELL_BACK_ASLEEP, at("22:02"))
        previous = computeAlarmPlan(buildSimulatedSegments(events, at("22:03")), settings, at("22:03"), previous.wakeAt, zone, config, wakeAlarmFiredAt = null, napAlarmsUsed = 0, lastNapAlarmFiredAt = null)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.WOKE_UP, at("22:14"))
        previous = computeAlarmPlan(buildSimulatedSegments(events, at("22:14:30")), settings, at("22:14:30"), previous.wakeAt, zone, config, wakeAlarmFiredAt = null, napAlarmsUsed = 0, lastNapAlarmFiredAt = null)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.FELL_BACK_ASLEEP, at("22:15"))

        val backAsleepTick = computeAlarmPlan(buildSimulatedSegments(events, at("22:15:15")), settings, at("22:15:15"), previous.wakeAt, zone, config, wakeAlarmFiredAt = null, napAlarmsUsed = 0, lastNapAlarmFiredAt = null)

        assertEquals(AlarmMode.NAP, backAsleepTick.mode)
    }
}
