package com.nikita.sleepcycle.night

// File purpose: PER-130 - a replay of the night of 2026-11-02. The band synced fine all night, but every
// heart-rate row (SOURCE 11) carried HEART_RATE -1 and no sleep rows arrived. The reader counted those rows as
// fresh, so each tick read "not asleep yet", projected the onset at its own now + 15 min, and the alarm slid
// later every tick until the plan ended DEADLINE_ONLY at the deadline. The designed no-data path (P1) never ran.
//
// Replayed through the same pure steps a real tick takes: the export's raw rows (buildBandDataResult, what
// BandDataReader builds from the database), the freshness verdict (decideSyncOutcome, resolveSyncOutcome's
// pure half) and the plan decision (decideTickPlan), starting from the plan startNight makes at Start night.

import com.nikita.sleepcycle.bridge.RawActivitySample
import com.nikita.sleepcycle.bridge.SOURCE_HEART_RATE
import com.nikita.sleepcycle.bridge.StaleReason
import com.nikita.sleepcycle.bridge.buildBandDataResult
import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.computeAlarmPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private const val DEVICE_ID = 1
private const val NO_HEART_RATE = -1
private const val REAL_HEART_RATE = 62
private val ZONE: ZoneId = ZoneId.of("America/New_York")
private val CONFIG = EngineConfig()
private val TICK_INTERVAL: Duration = Duration.ofMinutes(15)

class NotMeasuringNightReplayTest {
    private val startedAt = Instant.parse("2026-11-02T04:30:00Z") // 23:30 local
    private val deadline = Instant.parse("2026-11-02T12:00:00Z") // 07:00 local

    /** Start night + the estimated onset + the picked cycles: the P1 no-data estimate. */
    private fun anchoredEstimate(cycles: Int): Instant =
        startedAt.plus(CONFIG.fallAsleepEstimate).plus(CONFIG.cycleLength.multipliedBy(cycles.toLong()))

    @Test
    fun `a band that syncs but sends only -1 heart rate keeps the alarm anchored at Start night, not sliding to the deadline`() {
        val settings = NightSettings(deadline = deadline, pickedCycles = 4)

        val night = replayNight(settings, heartRate = NO_HEART_RATE, until = deadline)

        // 23:30 + 15 min + 4 x 90 min = 05:45 local, well before the 07:00 deadline.
        val expected = anchoredEstimate(cycles = 4)
        assertTrue(expected.isBefore(deadline))
        night.ticksBeforeAlarm.forEach { tick ->
            assertEquals(StaleReason.NO_HEART_RATE_VALUES, tick.outcome.staleReason, "every sync is stale for no heart-rate values")
            assertFalse(tick.outcome.syncOk)
            assertEquals(expected, tick.plan.wakeAt, "tick at ${tick.at} must keep the anchored alarm")
            assertEquals(AlarmMode.FULL_CYCLES, tick.plan.mode)
        }
        assertTrue(night.ticksBeforeAlarm.size > 10, "the replay covers the whole night up to the alarm")
        assertEquals(StaleReason.NO_HEART_RATE_VALUES, night.finalState.lastStaleReason)
    }

    @Test
    fun `with too little room before the deadline the anchored estimate is capped by it, still from Start night`() {
        val lateStart = Instant.parse("2026-11-02T07:00:00Z") // 02:00 local: 4 cycles would end 08:15, past 07:00
        val settings = NightSettings(deadline = deadline, pickedCycles = 4)

        val night = replayNight(settings, heartRate = NO_HEART_RATE, until = deadline, startedAt = lateStart)

        // Three cycles fit: 02:00 + 15 min + 3 x 90 min = 06:45 local.
        val expected = lateStart.plus(CONFIG.fallAsleepEstimate).plus(CONFIG.cycleLength.multipliedBy(3))
        night.ticksBeforeAlarm.forEach { tick -> assertEquals(expected, tick.plan.wakeAt, "tick at ${tick.at}") }
    }

    @Test
    fun `control - the same rows with real heart-rate values are fresh, and an awake sleeper's estimate slides as before`() {
        val settings = NightSettings(deadline = deadline, pickedCycles = 4)

        val night = replayNight(settings, heartRate = REAL_HEART_RATE, until = deadline)

        night.ticksBeforeAlarm.forEach { tick -> assertTrue(tick.outcome.syncOk, "real values are fresh at ${tick.at}") }
        val wakeTimes = night.ticksBeforeAlarm.mapNotNull { it.plan.wakeAt }
        assertTrue(wakeTimes.last().isAfter(anchoredEstimate(cycles = 4)), "with no sleep seen, the estimate follows now, as designed for a band that does report")
    }

    private data class ReplayedTick(val at: Instant, val outcome: SyncOutcome, val plan: com.nikita.sleepcycle.engine.AlarmPlan)
    private data class ReplayedNight(val ticksBeforeAlarm: List<ReplayedTick>, val finalState: NightState)

    /**
     * Starts the night the way startNight does (its plan made at [startedAt]), then ticks every 15 min. Each sync
     * succeeds with a fresh export holding one heart-rate row per minute up to the tick, all at [heartRate], and
     * no sleep rows. Stops at the first tick at or after the armed alarm, or at [until].
     */
    private fun replayNight(settings: NightSettings, heartRate: Int, until: Instant, startedAt: Instant = this.startedAt): ReplayedNight {
        val initialPlan = computeAlarmPlan(
            emptyList(), settings, startedAt, morningAlarmAt = null, ZONE, CONFIG,
            wakeAlarmFiredAt = null, napAlarmsUsed = 0, lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        var state = NightState(
            startedAt = startedAt, settings = settings, lastPlan = initialPlan, lastSyncAt = null, lastSyncOk = null,
            lastSegments = emptyList(), lastExportFileModifiedAt = null, lastSyncFailureCause = null,
            morningAlarmAt = latchMorningAlarmAt(previous = null, initialPlan)
        )
        val ticks = mutableListOf<ReplayedTick>()
        // The first tick runs right after Start night, as NightService's first tick does.
        var now = startedAt.plusSeconds(20)
        while (now.isBefore(until)) {
            val alarmAt = state.lastPlan?.wakeAt
            if (alarmAt != null && !now.isBefore(alarmAt)) break
            val export = buildBandDataResult(heartRateRows(startedAt.minus(Duration.ofHours(2)), now, heartRate), DEVICE_ID, exportFileModifiedAt = now)
            val outcome = decideSyncOutcome(state, export, now).outcome
            val plan = decideTickPlan(outcome, state, now, ZONE, CONFIG).plan
            ticks += ReplayedTick(now, outcome, plan)
            state = state.copy(
                lastPlan = plan, lastSyncAt = now, lastSyncOk = outcome.syncOk, lastSegments = outcome.segments,
                lastExportFileModifiedAt = outcome.exportFileModifiedAt, lastSyncFailureCause = outcome.failureCause,
                lastStaleReason = outcome.staleReason, morningAlarmAt = latchMorningAlarmAt(state.morningAlarmAt, plan)
            )
            now = now.plus(TICK_INTERVAL)
        }
        return ReplayedNight(ticks, state)
    }

    /** One SOURCE 11 row per minute from [from] to [to], every one carrying [heartRate]. */
    private fun heartRateRows(from: Instant, to: Instant, heartRate: Int): List<RawActivitySample> =
        generateSequence(from) { it.plusSeconds(60) }
            .takeWhile { !it.isAfter(to) }
            .map { minute ->
                RawActivitySample(
                    timestampSeconds = minute.epochSecond, otherTimestampSeconds = minute.epochSecond, rawKind = 1,
                    source = SOURCE_HEART_RATE, deviceId = DEVICE_ID, heartRate = heartRate
                )
            }
            .toList()
}
