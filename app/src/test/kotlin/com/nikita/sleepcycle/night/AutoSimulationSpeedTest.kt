package com.nikita.sleepcycle.night

// File purpose: the Auto simulation speed (owner spec, 2026-10-02) - which speed Auto runs at for where the
// night is, and the tick it books so the slow-down lands on time.

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class AutoSimulationSpeedTest {
    private val now = Instant.parse("2026-10-02T03:15:00Z")

    @Test
    fun `far from the next alarm Auto runs at 600x`() {
        assertEquals(600, autoClockSpeed(now, plannedAlarmAt = Instant.parse("2026-10-02T07:35:00Z"), followUpAt = null))
    }

    @Test
    fun `from 10 simulated minutes before the next alarm Auto slows to 60x`() {
        assertEquals(60, autoClockSpeed(now, plannedAlarmAt = Instant.parse("2026-10-02T03:25:00Z"), followUpAt = null))
        assertEquals(60, autoClockSpeed(now, plannedAlarmAt = Instant.parse("2026-10-02T03:17:00Z"), followUpAt = null))
        assertEquals(600, autoClockSpeed(now, plannedAlarmAt = Instant.parse("2026-10-02T03:25:01Z"), followUpAt = null))
    }

    @Test
    fun `a pending nudge or own nap closer than the planned alarm is the one Auto slows down for`() {
        assertEquals(60, autoClockSpeed(now, plannedAlarmAt = Instant.parse("2026-10-02T07:35:00Z"), followUpAt = Instant.parse("2026-10-02T03:20:00Z")))
        assertEquals(60, autoClockSpeed(now, plannedAlarmAt = null, followUpAt = Instant.parse("2026-10-02T03:20:00Z")))
    }

    @Test
    fun `the next tick is pulled in to land on the slow-down, so 60x starts on time`() {
        val ordinaryTick = Instant.parse("2026-10-02T07:30:00Z")
        assertEquals(
            Instant.parse("2026-10-02T07:25:00Z"),
            autoSpeedTickAt(ordinaryTick, Instant.parse("2026-10-02T07:20:00Z"), plannedAlarmAt = Instant.parse("2026-10-02T07:35:00Z"), followUpAt = null)
        )
    }

    @Test
    fun `the next tick is left alone when it already comes first, or the slow-down has passed`() {
        val alarm = Instant.parse("2026-10-02T07:35:00Z")
        assertEquals(Instant.parse("2026-10-02T03:20:00Z"), autoSpeedTickAt(Instant.parse("2026-10-02T03:20:00Z"), now, alarm, followUpAt = null))
        assertEquals(Instant.parse("2026-10-02T07:31:00Z"), autoSpeedTickAt(Instant.parse("2026-10-02T07:31:00Z"), Instant.parse("2026-10-02T07:26:00Z"), alarm, followUpAt = null))
        assertEquals(null, autoSpeedTickAt(null, now, alarm, followUpAt = null))
    }

    @Test
    fun `waking drops Auto to 1x without moving the simulated clock`() {
        val anchorReal = Instant.parse("2026-10-02T14:00:00Z")
        val auto = SimulationSpeed(ClockWarp(600, anchorReal, Instant.parse("2026-10-02T23:00:00Z")), auto = true)
        val realNow = anchorReal.plusSeconds(30) // 30 real s at 600x = 5 simulated h

        val dropped = dropToRealSpeed(auto, realNow)

        assertEquals(false, dropped.auto)
        assertEquals(1, dropped.warp?.speed)
        assertEquals(Instant.parse("2026-10-03T04:00:00Z"), virtualNow(dropped.warp, realNow))
        assertEquals(Instant.parse("2026-10-03T04:01:00Z"), virtualNow(dropped.warp, realNow.plusSeconds(60)))
    }

    @Test
    fun `waking drops 60x to 1x too`() {
        val anchorReal = Instant.parse("2026-10-02T14:00:00Z")
        val fast = SimulationSpeed(ClockWarp(60, anchorReal, Instant.parse("2026-10-02T23:00:00Z")), auto = false)

        val dropped = dropToRealSpeed(fast, anchorReal.plusSeconds(60))

        // 60 real s at 60x = one simulated hour.
        assertEquals(SimulationSpeed(ClockWarp(1, anchorReal.plusSeconds(60), Instant.parse("2026-10-03T00:00:00Z")), auto = false), dropped)
    }

    @Test
    fun `choosing Auto starts at the speed Auto wants right now, and the chip shows Auto`() {
        val realNow = Instant.parse("2026-10-02T14:00:00Z")
        val chosen = chooseSimulationSpeed(SimulationSpeed(warp = null, auto = false), SpeedChoice.AUTO, realNow, autoSpeedNow = 60)

        assertEquals(60, chosen.warp?.speed)
        assertEquals(true, chosen.auto)
        assertEquals(SpeedChoice.AUTO, speedChoiceOf(chosen))
    }

    @Test
    fun `choosing 60x leaves Auto`() {
        val realNow = Instant.parse("2026-10-02T14:00:00Z")
        val auto = SimulationSpeed(ClockWarp(600, realNow, Instant.parse("2026-10-02T23:00:00Z")), auto = true)

        val chosen = chooseSimulationSpeed(auto, SpeedChoice.FAST, realNow, autoSpeedNow = 600)

        assertEquals(SimulationSpeed(ClockWarp(60, realNow, Instant.parse("2026-10-02T23:00:00Z")), auto = false), chosen)
        assertEquals(SpeedChoice.FAST, speedChoiceOf(chosen))
    }

    @Test
    fun `a clock at real speed, or one left at 1x after a fast run, shows 1x`() {
        assertEquals(SpeedChoice.REAL, speedChoiceOf(SimulationSpeed(warp = null, auto = false)))
        val drifted = ClockWarp(1, Instant.parse("2026-10-02T14:00:00Z"), Instant.parse("2026-10-02T23:00:00Z"))
        assertEquals(SpeedChoice.REAL, speedChoiceOf(SimulationSpeed(drifted, auto = false)))
    }

    @Test
    fun `an alarm already behind the clock is not waited for`() {
        assertEquals(600, autoClockSpeed(now, plannedAlarmAt = Instant.parse("2026-10-02T07:35:00Z"), followUpAt = Instant.parse("2026-10-02T03:10:00Z")))
        assertEquals(600, autoClockSpeed(now, plannedAlarmAt = null, followUpAt = null))
    }
}
