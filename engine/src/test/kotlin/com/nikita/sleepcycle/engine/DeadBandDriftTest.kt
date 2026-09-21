package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * J1.5 (owner-reported, 2026-09-21): demonstrates, at the pure engine level, the mechanism
 * NightOrchestrator.shouldKeepPreviousPlan exists to interrupt. This is not itself a defect in
 * [computeAlarmPlan] - projecting a fresh onset at `now + fallAsleepEstimate` while AWAKE is the correct,
 * documented behaviour for a band that is genuinely still reporting - it only becomes a bug one layer up, when
 * the APP keeps re-feeding the SAME stale (dead-band) segments through this same call on every tick while
 * `now` keeps moving. Proven here directly: repeatedly calling [computeAlarmPlan] with IDENTICAL segments
 * (frozen, as a dead band would leave them) and only `now` advancing produces a `wakeAt` that keeps sliding
 * later, tick after tick, with no deadline to ever cap it - it never settles on a fixed instant, so it would
 * never actually ring. See NightOrchestrator.kt's own `shouldKeepPreviousPlan` for the app-layer fix: skip this
 * call entirely once the sync is known not to be ok and a real alarm is already armed, keeping that armed
 * alarm instead of ever reaching the drift proven below.
 */
class DeadBandDriftTest {
    @Test fun `AWAKE with frozen segments and no deadline - wakeAt keeps sliding later as now advances, never settling`() {
        val setting = settings(deadline = null, cycles = 5)
        // The band's last real report before it died: a short stretch, then AWAKE - exactly what a dead band
        // leaves behind forever once the owner is up and it stops updating.
        val deadBandSegments = listOf(
            segment("2026-09-17T00:00", "2026-09-17T00:30", SegmentKind.LIGHT),
            segment("2026-09-17T00:30", "2026-09-17T00:35", SegmentKind.AWAKE)
        )

        val tick1 = computeAlarmPlan(
            deadBandSegments, setting, instant("2026-09-17T00:40"), morningAlarmAt = null, testZone, EngineConfig(),
            wakeAlarmFiredAt = null, napAlarmsUsed = 0, lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        val tick2 = computeAlarmPlan(
            deadBandSegments, setting, instant("2026-09-17T01:40"), morningAlarmAt = null, testZone, EngineConfig(),
            wakeAlarmFiredAt = null, napAlarmsUsed = 0, lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )
        val tick3 = computeAlarmPlan(
            deadBandSegments, setting, instant("2026-09-17T05:40"), morningAlarmAt = null, testZone, EngineConfig(),
            wakeAlarmFiredAt = null, napAlarmsUsed = 0, lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
        )

        assertTrue(tick1.onsetIsProjected, "the AWAKE state must be projecting, not reading a real onset")
        requireNotNull(tick1.wakeAt); requireNotNull(tick2.wakeAt); requireNotNull(tick3.wakeAt)
        // Five real hours pass (00:40 to 05:40) with IDENTICAL segments fed in each time - a genuinely working
        // band would have reported something new in that stretch. wakeAt tracks now instead of ever settling.
        assertTrue(tick2.wakeAt.isAfter(tick1.wakeAt), "wakeAt should have slid later from tick1 to tick2")
        assertTrue(tick3.wakeAt.isAfter(tick2.wakeAt), "wakeAt should have slid later again from tick2 to tick3")
        // The slide tracks now exactly (fallAsleepEstimate is constant), so five hours of frozen data produces
        // roughly five more hours of pushed-out wakeAt - never converging on a fixed ring time.
        assertTrue(
            java.time.Duration.between(tick1.wakeAt, tick3.wakeAt) >= java.time.Duration.ofHours(4),
            "wakeAt should have slid by roughly the same span now itself advanced"
        )
    }
}
