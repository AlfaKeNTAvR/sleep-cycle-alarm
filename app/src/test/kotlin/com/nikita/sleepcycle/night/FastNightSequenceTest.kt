package com.nikita.sleepcycle.night

// File purpose: a whole simulated night, replayed through the REAL engine (computeAlarmPlan) with the fast
// debug EngineConfig (resolveEngineConfig(DebugOptions(fastNight = true))) and segments built the same way
// the Debug screen's simulator would build them (BandDataSimulator.kt). Mirrors the style of :engine's own
// WholeNightSequenceTest: feeds each plan back in as previousPlan and asserts mode and bandAlarm at every
// step. Covers: start, fell asleep, waking early enough that a full cycle still fits (restart), falling back
// asleep, waking with the picked total all but used up so that only a nap is left, falling back asleep again - NAP
// still holds for at least one further tick before anything turns to OVERDUE, because napLength (3 min)
// leaves a whole sync delay's worth of lead over minAlarmLead (see EngineConfigResolutionTest's pinned
// property) - then OVERDUE once the nap alarm time is actually reached and slept through, OVERDUE continuing
// (snoozing) on a further tick, and finally FINISHED once now reaches the overdue cap. Every instant below is
// hand-derived from the engine's own formulas (BandAlarm.kt, PlanSteps.kt).

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.computeAlarmPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

class FastNightSequenceTest {
    private val zone = ZoneOffset.UTC
    private val config = resolveEngineConfig(DebugOptions(fastNight = true))
    private val settings = NightSettings(deadline = null, pickedCycles = 3, phoneBackupEnabled = false)

    /** Accepts "HH:mm" or "HH:mm:ss" and parses it as an instant on a fixed date, so the test's own instants read like clock times. */
    private fun at(clockTime: String): Instant {
        val normalized = if (clockTime.count { it == ':' } == 1) "$clockTime:00" else clockTime
        return Instant.parse("2026-09-17T${normalized}Z")
    }

    @Test
    fun `a whole fast simulated night replays start, restart, nap holding, overdue and the cap in order`() {
        var events = emptyList<SimulatedSleepEvent>()

        // Tick 0: before any sleep, one minute before the projected fall-asleep time. 3 picked cycles * 5 min = 15 min.
        val plan0 = computeAlarmPlan(buildSimulatedSegments(events, at("21:59")), settings, at("21:59"), null, zone, config)
        assertEquals(AlarmMode.FULL_CYCLES, plan0.mode)
        assertEquals(at("22:15"), plan0.bandAlarm)

        // "I fell asleep now" at 22:00:00; next tick at 22:00:30.
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.FELL_ASLEEP, at("22:00"))
        val plan1 = computeAlarmPlan(buildSimulatedSegments(events, at("22:00:30")), settings, at("22:00:30"), plan0, zone, config)
        assertEquals(AlarmMode.FULL_CYCLES, plan1.mode)
        assertEquals(at("22:15"), plan1.bandAlarm)

        // "I woke up now" at 22:01:00, well before the 22:15 alarm - next tick (still awake) restarts a full count.
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.WOKE_UP, at("22:01"))
        val plan2 = computeAlarmPlan(buildSimulatedSegments(events, at("22:01:30")), settings, at("22:01:30"), plan1, zone, config)
        assertEquals(AlarmMode.FULL_CYCLES, plan2.mode)
        assertEquals(at("22:17:30"), plan2.bandAlarm)

        // "Fell back asleep now" at 22:02:00 - a short cycle still clearly fits, so this is still FULL_CYCLES.
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.FELL_BACK_ASLEEP, at("22:02"))
        val plan3 = computeAlarmPlan(buildSimulatedSegments(events, at("22:03")), settings, at("22:03"), plan2, zone, config)
        assertEquals(AlarmMode.FULL_CYCLES, plan3.mode)
        assertEquals(at("22:17"), plan3.bandAlarm)

        // "I woke up now" at 22:14:00, with 13 of the picked 15 min already slept: less than half a short
        // cycle is left, so nothing whole is still owed and only a nap fits. (There is no deadline here, so
        // the 22:17 alarm of the previous plan has no say in this - only the picked total does.)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.WOKE_UP, at("22:14"))
        val plan4 = computeAlarmPlan(buildSimulatedSegments(events, at("22:14:30")), settings, at("22:14:30"), plan3, zone, config)
        assertEquals(AlarmMode.NAP, plan4.mode)
        assertEquals(at("22:17:30"), plan4.bandAlarm)

        // "Fell back asleep now" at 22:15:00 for the nap. Unlike the old (1 min nap / 1 min lead) config, the
        // nap alarm is still comfortably ahead of now + minAlarmLead here, so NAP holds on this tick too -
        // the tester actually sees the nap card, not an instant jump to OVERDUE.
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.FELL_BACK_ASLEEP, at("22:15"))
        val plan5 = computeAlarmPlan(buildSimulatedSegments(events, at("22:15:15")), settings, at("22:15:15"), plan4, zone, config)
        assertEquals(AlarmMode.NAP, plan5.mode)
        assertEquals(at("22:18"), plan5.bandAlarm)

        // Time passes with no new events, past the nap alarm's own time (22:18): still "asleep" per the
        // simulator, so now it becomes OVERDUE - the band/phone alarm was reached and slept through. C1: a
        // fresh overdue alarm computed from a mid-minute now (22:18:30) rounds UP to the next whole minute
        // (22:19:30 -> 22:20), never down.
        val plan6 = computeAlarmPlan(buildSimulatedSegments(events, at("22:18:30")), settings, at("22:18:30"), plan5, zone, config)
        assertEquals(AlarmMode.OVERDUE, plan6.mode)
        assertEquals(at("22:20"), plan6.bandAlarm)

        // Further time passes: still asleep, buzzing again (snoozing), not yet at the cap.
        val plan7 = computeAlarmPlan(buildSimulatedSegments(events, at("22:21")), settings, at("22:21"), plan6, zone, config)
        assertEquals(AlarmMode.OVERDUE, plan7.mode)
        assertEquals(at("22:22"), plan7.bandAlarm)

        // Now reaches the 5-minute overdue cap (from the 22:18 band alarm that was missed): FINISHED.
        val plan8 = computeAlarmPlan(buildSimulatedSegments(events, at("22:23")), settings, at("22:23"), plan7, zone, config)
        assertEquals(AlarmMode.FINISHED, plan8.mode)
        assertNull(plan8.bandAlarm)
    }

    @Test
    fun `one moment before the overdue cap the night is still OVERDUE, not FINISHED`() {
        var events = emptyList<SimulatedSleepEvent>()
        var previous = computeAlarmPlan(buildSimulatedSegments(events, at("21:59")), settings, at("21:59"), null, zone, config)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.FELL_ASLEEP, at("22:00"))
        previous = computeAlarmPlan(buildSimulatedSegments(events, at("22:00:30")), settings, at("22:00:30"), previous, zone, config)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.WOKE_UP, at("22:01"))
        previous = computeAlarmPlan(buildSimulatedSegments(events, at("22:01:30")), settings, at("22:01:30"), previous, zone, config)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.FELL_BACK_ASLEEP, at("22:02"))
        previous = computeAlarmPlan(buildSimulatedSegments(events, at("22:03")), settings, at("22:03"), previous, zone, config)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.WOKE_UP, at("22:14"))
        previous = computeAlarmPlan(buildSimulatedSegments(events, at("22:14:30")), settings, at("22:14:30"), previous, zone, config)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.FELL_BACK_ASLEEP, at("22:15"))
        previous = computeAlarmPlan(buildSimulatedSegments(events, at("22:15:15")), settings, at("22:15:15"), previous, zone, config)
        previous = computeAlarmPlan(buildSimulatedSegments(events, at("22:18:30")), settings, at("22:18:30"), previous, zone, config)

        val justBeforeCap = computeAlarmPlan(buildSimulatedSegments(events, at("22:22:59")), settings, at("22:22:59"), previous, zone, config)

        assertEquals(AlarmMode.OVERDUE, justBeforeCap.mode)
    }

    @Test
    fun `a nap detected fell-back-asleep still holds NAP rather than jumping straight to OVERDUE`() {
        var events = emptyList<SimulatedSleepEvent>()
        var previous = computeAlarmPlan(buildSimulatedSegments(events, at("21:59")), settings, at("21:59"), null, zone, config)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.FELL_ASLEEP, at("22:00"))
        previous = computeAlarmPlan(buildSimulatedSegments(events, at("22:00:30")), settings, at("22:00:30"), previous, zone, config)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.WOKE_UP, at("22:01"))
        previous = computeAlarmPlan(buildSimulatedSegments(events, at("22:01:30")), settings, at("22:01:30"), previous, zone, config)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.FELL_BACK_ASLEEP, at("22:02"))
        previous = computeAlarmPlan(buildSimulatedSegments(events, at("22:03")), settings, at("22:03"), previous, zone, config)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.WOKE_UP, at("22:14"))
        previous = computeAlarmPlan(buildSimulatedSegments(events, at("22:14:30")), settings, at("22:14:30"), previous, zone, config)
        events = appendSimulatedSleepEvent(events, SimulatedSleepEventKind.FELL_BACK_ASLEEP, at("22:15"))

        val backAsleepTick = computeAlarmPlan(buildSimulatedSegments(events, at("22:15:15")), settings, at("22:15:15"), previous, zone, config)

        assertEquals(AlarmMode.NAP, backAsleepTick.mode)
    }
}
