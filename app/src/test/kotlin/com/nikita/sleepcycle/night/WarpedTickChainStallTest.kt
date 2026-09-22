package com.nikita.sleepcycle.night

// File purpose: N5 (owner-reported, 2026-09-21) - "a 60x night's countdown sat at 'in 0 min' and the alarm
// never rang; toggling the speed to 1x and back made it update and ring". The cause was FIX2's skip-arming
// debounce in TickScheduling.kt suppressing a fast tick's OWN terminal re-arm, which ended the warped night's
// tick chain outright. These tests pin the two properties that must hold once that guard is gone, and record
// the two things the investigation ruled OUT, so neither is re-proposed as the cause.
//
// Three groups:
//  1. The CHAIN: every arm request is honoured, so the chain never dies; and successive ticks still cannot
//     start closer together than IN_PROCESS_TICK_MIN_DELAY, which is the busy-loop bound FIX2 was reaching
//     for and V3's floor already provides on its own.
//  2. The KILLS: shouldArmPhoneAlarm refusing an overdue target is NOT the bug - the re-plan on the same tick
//     pulls the target forward and arming proceeds - and a speed change never jumps virtual time.
//  3. The RING AUTO-STOP: a separate site of the same defect class, characterized so the size of the error
//     matches the owner's own logs. Deliberately not fixed - see docs/decisions.md.

import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.computeAlarmPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class WarpedTickChainStallTest {

    private val config = EngineConfig()
    private val zone = ZoneOffset.UTC
    private val bedtime: Instant = Instant.parse("2026-09-17T22:00:00Z")

    // ---------------------------------------------------------------------------------------------------
    // 1. THE CHAIN - it must not arm itself out of existence, and it must not spin.
    // ---------------------------------------------------------------------------------------------------

    /**
     * Replays TickScheduling.kt's own in-process tick loop on a warped night: an armed job's delay elapses,
     * the tick runs for [tickWorkMillis] of REAL time, and then books its successor [bookedRealDelayMillis]
     * out (negative meaning the freshly-computed target is already in the past, the case that used to drive
     * the tight loop). The one decision in the loop - how long the armed job actually waits - is delegated to
     * the production [inProcessTickDelay]; nothing here re-implements it. Returns each tick's own real start
     * instant, so both the chain's length and its spacing can be asserted.
     */
    private fun warpedTickStarts(wantedTicks: Int, tickWorkMillis: Long, bookedRealDelayMillis: Long): List<Instant> {
        var realNow = Instant.parse("2026-09-18T00:00:00Z")
        val starts = mutableListOf<Instant>()
        repeat(wantedTicks) {
            realNow = realNow.plus(inProcessTickDelay(Duration.ofMillis(bookedRealDelayMillis)))
            starts += realNow
            realNow = realNow.plusMillis(tickWorkMillis)
        }
        return starts
    }

    @Test
    fun `a warped tick chain whose ticks finish fast keeps running - it must not arm itself out of existence`() {
        // 100 ms of disk work per tick, each booking the next one 15 real seconds out (normalSyncDelay, 15
        // virtual minutes at 60x). Every tick finishes well inside IN_PROCESS_TICK_MIN_DELAY of its own start,
        // which is exactly the shape FIX2's debounce refused to arm.
        val starts = warpedTickStarts(wantedTicks = 10, tickWorkMillis = 100, bookedRealDelayMillis = 15_000)
        assertEquals(10, starts.size, "every arm request must be honoured, however fast the tick asking for it was")
    }

    @Test
    fun `an unbroken run of overdue targets still cannot make ticks start closer than the floor`() {
        // Every tick computes a target already 50 ms in the real past - the run FIX2 was trying to break up.
        val starts = warpedTickStarts(wantedTicks = 10, tickWorkMillis = 400, bookedRealDelayMillis = -50)
        assertEquals(10, starts.size, "an overdue target delays the next tick, it never cancels the chain")
        val gaps = starts.zipWithNext { earlier, later -> Duration.between(earlier, later) }
        assertTrue(
            gaps.all { it >= IN_PROCESS_TICK_MIN_DELAY },
            "V3's floor alone bounds the start rate at one tick per IN_PROCESS_TICK_MIN_DELAY; smallest gap was ${gaps.min()}"
        )
    }

    // ---------------------------------------------------------------------------------------------------
    // 2. KILLS - neither of these is the root cause.
    // ---------------------------------------------------------------------------------------------------

    @Test
    fun `a speed change never jumps virtual time, so there is no forward jump for shouldArmPhoneAlarm to refuse`() {
        val fast = ClockWarp(speed = 60, anchorReal = Instant.parse("2026-09-17T20:00:00Z"), anchorVirtual = bedtime)
        val switchRealAt = Instant.parse("2026-09-17T20:05:00Z")
        val virtualBefore = virtualNow(fast, switchRealAt)
        val slow = computeSpeedChangeWarp(fast, speed = 1, realNow = switchRealAt)
        assertEquals(virtualBefore, virtualNow(slow, switchRealAt), "computeSpeedChangeWarp re-anchors at the current virtual instant")
    }

    @Test
    fun `an overdue target is pulled forward by the same tick's re-plan, so arming proceeds`() {
        val events = appendSimulatedSleepEvent(emptyList(), SimulatedSleepEventKind.ASLEEP, bedtime)
        // 10 virtual minutes past the raw 3-cycle target (onset + 4:30) - the "target already in the past"
        // state the first-guess root cause assumes leaves nothing armed.
        val now = bedtime.plus(Duration.ofHours(4)).plus(Duration.ofMinutes(40))
        val plan = computeAlarmPlan(
            buildSimulatedSegments(events, now), NightSettings(deadline = null, pickedCycles = 3), now,
            null, zone, config, null, 0, null, null
        )
        val wakeAt = plan.wakeAt
        assertNotEquals(null, wakeAt, "an overdue target must not collapse to no alarm at all")
        assertTrue(wakeAt!!.isAfter(now), "D8's pull-forward moves an overdue target to now + minAlarmLead, so it is future again")
        assertTrue(
            shouldArmPhoneAlarm(wakeAt, now, phoneAlarmFiredFor = null),
            "shouldArmPhoneAlarm therefore ARMS on the same tick - its isAfter(now) check is not what leaves a night silent"
        )
    }

    // ---------------------------------------------------------------------------------------------------
    // 3. RING AUTO-STOP - a separate site, same defect class.
    // ---------------------------------------------------------------------------------------------------

    @Test
    fun `the ring auto-stop deadline is frozen in real time, so a mid-ring speed change rescales it`() {
        // AlarmRingService.realAutoStopDelayMillis converts ONCE, when the ring starts, and posts a Handler.
        val ringStartsRealAt = Instant.parse("2026-09-18T02:00:00Z")
        val fast = ClockWarp(speed = 60, anchorReal = ringStartsRealAt, anchorVirtual = bedtime)
        val virtualStopAt = bedtime.plus(config.ringAutoStopAfter)
        val realStopAt = realInstantFor(fast, virtualStopAt)
        assertEquals(9_000L, Duration.between(ringStartsRealAt, realStopAt).toMillis(), "9 virtual min at 60x is 9 real s")

        // One real second into the ring (60 virtual s), the owner drops to 1x. Nothing re-posts the Handler.
        val switchRealAt = ringStartsRealAt.plusMillis(1_000)
        val slow = computeSpeedChangeWarp(fast, speed = 1, realNow = switchRealAt)
        val actualVirtualGap = Duration.between(bedtime, virtualNow(slow, realStopAt))

        assertNotEquals(config.ringAutoStopAfter, actualVirtualGap, "the ring no longer auto-stops after 9 virtual minutes")
        assertEquals(
            Duration.ofSeconds(68), actualVirtualGap,
            "60 virtual s at 60x plus the remaining 8 real s now worth 8 virtual s - the owner's own 1 min 16 s log line, same shape"
        )
    }
}
