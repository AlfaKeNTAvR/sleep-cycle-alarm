package com.nikita.sleepcycle.night

// File purpose: T1 - virtualNow/realInstantFor's own round trip at every offered speed, warp == null's
// identity behaviour, and saturation instead of overflow at the extremes.

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class SimulatedClockTest {
    private val anchorReal = Instant.parse("2026-09-17T20:00:00Z")
    private val anchorVirtual = Instant.parse("2026-09-18T03:00:00Z")

    @Test
    fun `warp null is the identity transform for virtualNow`() {
        val realNow = Instant.parse("2026-09-17T21:15:00Z")
        assertEquals(realNow, virtualNow(warp = null, realNow = realNow))
    }

    @Test
    fun `warp null is the identity transform for realInstantFor`() {
        val virtualAt = Instant.parse("2026-09-17T21:15:00Z")
        assertEquals(virtualAt, realInstantFor(warp = null, virtualAt = virtualAt))
    }

    @Test
    fun `every offered speed round trips virtualNow through realInstantFor back to the original real instant`() {
        for (speed in SIMULATION_SPEEDS) {
            val warp = ClockWarp(speed, anchorReal, anchorVirtual)
            val realNow = anchorReal.plus(Duration.ofMinutes(37))
            val virtualAt = virtualNow(warp, realNow)
            assertEquals(realNow, realInstantFor(warp, virtualAt), "round trip failed at speed $speed")
        }
    }

    @Test
    fun `every offered speed round trips realInstantFor through virtualNow back to the original virtual instant`() {
        for (speed in SIMULATION_SPEEDS) {
            val warp = ClockWarp(speed, anchorReal, anchorVirtual)
            val virtualAt = anchorVirtual.plus(Duration.ofHours(2).plusMinutes(30))
            val realAt = realInstantFor(warp, virtualAt)
            assertEquals(virtualAt, virtualNow(warp, realAt), "round trip failed at speed $speed")
        }
    }

    @Test
    fun `virtualNow at the anchor real instant is exactly the anchor virtual instant`() {
        val warp = ClockWarp(60, anchorReal, anchorVirtual)
        assertEquals(anchorVirtual, virtualNow(warp, anchorReal))
    }

    @Test
    fun `virtualNow moves speed times faster than real time`() {
        val warp = ClockWarp(60, anchorReal, anchorVirtual)
        val realNow = anchorReal.plus(Duration.ofSeconds(10))
        assertEquals(anchorVirtual.plus(Duration.ofMinutes(10)), virtualNow(warp, realNow))
    }

    @Test
    fun `realInstantFor moves speed times slower than virtual time`() {
        val warp = ClockWarp(600, anchorReal, anchorVirtual)
        val virtualAt = anchorVirtual.plus(Duration.ofMinutes(10))
        assertEquals(anchorReal.plus(Duration.ofSeconds(1)), realInstantFor(warp, virtualAt))
    }

    @Test
    fun `virtualNow before the anchor real instant moves virtual time backwards too`() {
        val warp = ClockWarp(10, anchorReal, anchorVirtual)
        val realNow = anchorReal.minus(Duration.ofMinutes(5))
        assertEquals(anchorVirtual.minus(Duration.ofMinutes(50)), virtualNow(warp, realNow))
    }

    // ---- Overflow saturation ----------------------------------------------------------------------------
    //
    // A Long of milliseconds (~292 million years either side of the epoch) is actually narrower than the
    // range Instant itself can represent (~1 billion years either side), so pushing elapsed time to the very
    // edge of what a Long can hold is not, on its own, enough to walk an arbitrary anchor off the edge of
    // Instant's own range - the tests below anchor AT Instant.MIN/MAX specifically, where even a small step
    // the wrong way is guaranteed to overflow, to reliably exercise the saturating branches themselves.

    @Test
    fun `virtualNow saturates to Instant MAX rather than throwing when it would overflow past it`() {
        val warp = ClockWarp(600, anchorReal, anchorVirtual = Instant.MAX)
        assertEquals(Instant.MAX, virtualNow(warp, realNow = anchorReal.plus(Duration.ofDays(1))))
    }

    @Test
    fun `virtualNow saturates to Instant MIN rather than throwing when it would overflow past it`() {
        val warp = ClockWarp(600, anchorReal, anchorVirtual = Instant.MIN)
        assertEquals(Instant.MIN, virtualNow(warp, realNow = anchorReal.minus(Duration.ofDays(1))))
    }

    @Test
    fun `realInstantFor saturates to Instant MAX rather than throwing when it would overflow past it`() {
        val warp = ClockWarp(600, anchorReal = Instant.MAX, anchorVirtual)
        assertEquals(Instant.MAX, realInstantFor(warp, virtualAt = anchorVirtual.plus(Duration.ofDays(1))))
    }

    @Test
    fun `realInstantFor saturates to Instant MIN rather than throwing when it would overflow past it`() {
        val warp = ClockWarp(600, anchorReal = Instant.MIN, anchorVirtual)
        assertEquals(Instant.MIN, realInstantFor(warp, virtualAt = anchorVirtual.minus(Duration.ofDays(1))))
    }

    @Test
    fun `virtualNow never throws for the most extreme possible warp and realNow combinations`() {
        for (speed in SIMULATION_SPEEDS) {
            for (anchorReal in listOf(Instant.MIN, Instant.MAX)) {
                for (anchorVirtual in listOf(Instant.MIN, Instant.MAX)) {
                    for (realNow in listOf(Instant.MIN, Instant.MAX)) {
                        virtualNow(ClockWarp(speed, anchorReal, anchorVirtual), realNow)
                    }
                }
            }
        }
    }

    @Test
    fun `realInstantFor never throws for the most extreme possible warp and virtualAt combinations`() {
        for (speed in SIMULATION_SPEEDS) {
            for (anchorReal in listOf(Instant.MIN, Instant.MAX)) {
                for (anchorVirtual in listOf(Instant.MIN, Instant.MAX)) {
                    for (virtualAt in listOf(Instant.MIN, Instant.MAX)) {
                        realInstantFor(ClockWarp(speed, anchorReal, anchorVirtual), virtualAt)
                    }
                }
            }
        }
    }

    @Test
    fun `virtualNow at exactly the anchor real instant needs no saturation even at the extreme ends of Instant`() {
        val warp = ClockWarp(600, anchorReal = Instant.MIN, anchorVirtual = Instant.MAX)
        assertEquals(Instant.MAX, virtualNow(warp, realNow = Instant.MIN))
    }

    // ---- U2: normalizedWarp - the one rule for whether a ClockWarp exists at all --------------------------

    @Test
    fun `normalizedWarp is null at speed 1 with no drift between the anchors - the identity case`() {
        assertNull(normalizedWarp(1, anchorReal = anchorReal, anchorVirtual = anchorReal))
    }

    @Test
    fun `normalizedWarp is a real ClockWarp at speed 1 when the anchors differ - a jump kept at real pace`() {
        val warp = normalizedWarp(1, anchorReal = anchorReal, anchorVirtual = anchorVirtual)
        assertEquals(ClockWarp(1, anchorReal, anchorVirtual), warp)
    }

    @Test
    fun `normalizedWarp is a real ClockWarp at any non-1 speed even with no drift yet`() {
        val warp = normalizedWarp(60, anchorReal = anchorReal, anchorVirtual = anchorReal)
        assertEquals(ClockWarp(60, anchorReal, anchorReal), warp)
    }

    @Test
    fun `normalizedWarp is a real ClockWarp at a non-1 speed with drift too`() {
        val warp = normalizedWarp(600, anchorReal = anchorReal, anchorVirtual = anchorVirtual)
        assertEquals(ClockWarp(600, anchorReal, anchorVirtual), warp)
    }

    // ---- T12 (amended): formatSimulatedTimeValue - never prints "1x" ---------------------------------------

    @Test
    fun `formatSimulatedTimeValue at speed 1 prints only the time, never a multiplier`() {
        val warp = ClockWarp(1, anchorReal, anchorVirtual)
        assertEquals("03:00", formatSimulatedTimeValue(warp, virtualNow = anchorVirtual, zone = ZoneOffset.UTC))
    }

    @Test
    fun `formatSimulatedTimeValue at a non-1 speed appends the multiplier`() {
        val warp = ClockWarp(60, anchorReal, anchorVirtual)
        assertEquals("03:00, 60x", formatSimulatedTimeValue(warp, virtualNow = anchorVirtual, zone = ZoneOffset.UTC))
    }

    @Test
    fun `formatSimulatedTimeValue formats the given virtualNow, not the warp's own anchor`() {
        val warp = ClockWarp(600, anchorReal, anchorVirtual)
        val laterVirtual = anchorVirtual.plus(Duration.ofMinutes(15))
        assertEquals("03:15, 600x", formatSimulatedTimeValue(warp, virtualNow = laterVirtual, zone = ZoneOffset.UTC))
    }
}
