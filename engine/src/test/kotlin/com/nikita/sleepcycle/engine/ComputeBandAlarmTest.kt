package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComputeBandAlarmTest {
    private val config = EngineConfig()

    private fun scheduled(result: BandAlarmResult): BandAlarmResult.Scheduled =
        result as? BandAlarmResult.Scheduled ?: error("expected Scheduled but was $result")

    @Test fun `rule 1 FINISHED produces no alarm`() {
        val result = computeBandAlarm(
            PlanRule.FINISHED, SleepState.AWAKE, instant("2026-09-17T00:30"), null, null, 0,
            instant("2026-09-17T08:00"), null, config
        )
        assertTrue(result is BandAlarmResult.Finished)
    }

    @Test fun `rule 5 DEADLINE_ONLY sets the alarm exactly at the deadline`() {
        val result = scheduled(
            computeBandAlarm(
                PlanRule.DEADLINE_ONLY, SleepState.NOT_YET_ASLEEP, instant("2026-09-17T00:40"), null,
                instant("2026-09-17T00:50"), 0, instant("2026-09-17T00:40"), null, config
            )
        )
        assertEquals(AlarmMode.DEADLINE_ONLY, result.mode)
        assertEquals(instant("2026-09-17T00:50"), result.alarm)
    }

    @Test fun `rule 4 FULL_CYCLES adds the picked cycles to the onset`() {
        val result = scheduled(
            computeBandAlarm(
                PlanRule.FULL_CYCLES, SleepState.ASLEEP, instant("2026-09-17T00:30"), null, null, 5,
                instant("2026-09-17T01:00"), null, config
            )
        )
        assertEquals(instant("2026-09-17T08:00"), result.alarm)
    }

    @Test fun `rule 7 NAP while awake slides to now plus napLength, capped by the boundary`() {
        val result = scheduled(
            computeBandAlarm(
                PlanRule.NAP, SleepState.AWAKE, instant("2026-09-17T00:30"), instant("2026-09-17T01:00"), null, 0,
                instant("2026-09-17T00:40"), null, config
            )
        )
        assertEquals(instant("2026-09-17T01:00"), result.alarm)
    }

    @Test fun `rule 7 NAP once asleep is 20 min after onset, never later even if previous plan said later`() {
        val previous = AlarmPlan(
            AlarmMode.NAP, instant("2026-09-17T01:20"), null, 0, instant("2026-09-17T00:00"), false, null, "r"
        )
        val result = scheduled(
            computeBandAlarm(
                PlanRule.NAP, SleepState.ASLEEP, instant("2026-09-17T01:10"), instant("2026-09-17T01:40"), null, 0,
                instant("2026-09-17T01:12"), previous, config
            )
        )
        assertEquals(instant("2026-09-17T01:30"), result.alarm)
    }

    @Test fun `an alarm too close to now escalates to OVERDUE at now plus minAlarmLead`() {
        val result = scheduled(
            computeBandAlarm(
                PlanRule.FULL_CYCLES, SleepState.ASLEEP, instant("2026-09-17T00:30"), null, null, 5,
                instant("2026-09-17T08:05"), null, config
            )
        )
        assertEquals(AlarmMode.OVERDUE, result.mode)
        assertEquals(instant("2026-09-17T08:07"), result.alarm)
    }

    @Test fun `OVERDUE keeps the previous overdue alarm while it is still far enough ahead`() {
        val previous = AlarmPlan(
            AlarmMode.OVERDUE, instant("2026-09-17T08:07"), null, 5, instant("2026-09-17T00:30"), false, null, "r"
        )
        val result = scheduled(
            computeBandAlarm(
                PlanRule.FULL_CYCLES, SleepState.ASLEEP, instant("2026-09-17T00:30"), null, null, 5,
                instant("2026-09-17T08:06"), previous, config
            )
        )
        assertEquals(instant("2026-09-17T08:07"), result.alarm)
    }

    @Test fun `OVERDUE replaces a previous overdue alarm once it is no longer far enough ahead`() {
        val previous = AlarmPlan(
            AlarmMode.OVERDUE, instant("2026-09-17T08:07"), null, 5, instant("2026-09-17T00:30"), false, null, "r"
        )
        val result = scheduled(
            computeBandAlarm(
                PlanRule.FULL_CYCLES, SleepState.ASLEEP, instant("2026-09-17T00:30"), null, null, 5,
                instant("2026-09-17T08:10"), previous, config
            )
        )
        assertEquals(instant("2026-09-17T08:12"), result.alarm)
    }

    @Test fun `an overdue alarm that would land past the deadline becomes FINISHED instead`() {
        val result = computeBandAlarm(
            PlanRule.FULL_CYCLES, SleepState.ASLEEP, instant("2026-09-17T00:30"), null,
            instant("2026-09-17T08:31"), 5, instant("2026-09-17T08:30"), null, config
        )
        assertTrue(result is BandAlarmResult.Finished)
    }
}
