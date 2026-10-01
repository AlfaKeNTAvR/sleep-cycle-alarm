package com.nikita.sleepcycle.engine

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * P3 (owner spec, 2026-09-30): once the morning alarm has rung, nothing the engine plans depends on the band
 * any more - a return to sleep arms no nap of its own. The owner presses "Nap 20 min" instead (app layer,
 * night/PostAlarmCycle.kt), and the out-of-bed nudge covers everything else. Replaces D5/G8's band-detected
 * post-wake naps, which PostWakeNapTest used to walk through.
 */
class PostMorningAlarmTest {
    private val config = EngineConfig()
    private val setting = settings(cycles = 3)

    private fun plan(
        segments: List<SleepSegment>, now: String, morningAlarmAt: String?, wakeAlarmFiredAt: String?,
        phoneAlarmFiredFor: String?, napAlarmsUsed: Int = 0, lastNapAlarmFiredAt: String? = null,
    ) = computeAlarmPlan(
        segments, setting, instant(now), morningAlarmAt?.let(::instant), testZone, config,
        wakeAlarmFiredAt?.let(::instant), napAlarmsUsed, lastNapAlarmFiredAt?.let(::instant), phoneAlarmFiredFor?.let(::instant)
    )

    @Test fun `falling back asleep after the morning alarm rang arms no band-detected nap`() {
        // Morning alarm rang 07:00; awake 07:00-07:10; asleep again from 07:10. The old D5 nap was 07:30.
        val afterReturnToSleep = plan(
            listOf(
                segment("2026-09-16T22:30", "2026-09-17T07:00", SegmentKind.LIGHT),
                segment("2026-09-17T07:00", "2026-09-17T07:10", SegmentKind.AWAKE),
                segment("2026-09-17T07:10", "2026-09-17T07:15", SegmentKind.LIGHT),
            ),
            now = "2026-09-17T07:15", morningAlarmAt = "2026-09-17T07:00",
            wakeAlarmFiredAt = "2026-09-17T07:00", phoneAlarmFiredFor = "2026-09-17T07:00",
        )

        assertEquals(null, afterReturnToSleep.wakeAt)
    }

    @Test fun `a nap alarm that rang in the morning alarm's place counts as the morning alarm having rung`() {
        // Morning alarm due 06:30. Woke 06:20, dozed off again 06:25, so rule 7 rang a nap at 06:45 instead of
        // the 06:30 alarm - for the owner, that 06:45 ring WAS his morning alarm. Awake 06:45-06:50, asleep
        // again from 06:50: no band-detected nap at 07:10 any more.
        val afterReturnToSleep = plan(
            listOf(
                segment("2026-09-16T22:30", "2026-09-17T06:20", SegmentKind.LIGHT),
                segment("2026-09-17T06:20", "2026-09-17T06:25", SegmentKind.AWAKE),
                segment("2026-09-17T06:25", "2026-09-17T06:45", SegmentKind.LIGHT),
                segment("2026-09-17T06:45", "2026-09-17T06:50", SegmentKind.AWAKE),
                segment("2026-09-17T06:50", "2026-09-17T06:55", SegmentKind.LIGHT),
            ),
            now = "2026-09-17T06:55", morningAlarmAt = "2026-09-17T06:30",
            wakeAlarmFiredAt = null, phoneAlarmFiredFor = "2026-09-17T06:45",
            napAlarmsUsed = 1, lastNapAlarmFiredAt = "2026-09-17T06:45",
        )

        assertEquals(null, afterReturnToSleep.wakeAt)
    }
}
