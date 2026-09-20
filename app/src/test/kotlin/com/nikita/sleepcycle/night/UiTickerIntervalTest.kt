package com.nikita.sleepcycle.night

// File purpose: W1 - the UI's clock re-sampling cadence. A flat 30 s made the simulated clock look broken:
// the readout stood still for 30 real seconds and then jumped by whatever simulated time had passed, so at
// 60x it moved in half-hour steps and "Reset to real time" looked like it had done nothing. See
// SimulatedClock.uiTickerIntervalMillis and NightViewModel.watchScreenVisibilityTicker.

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiTickerIntervalTest {
    @Test
    fun `an unwarped clock keeps the app's own half-minute cadence`() {
        assertEquals(UI_TICKER_INTERVAL_MS, uiTickerIntervalMillis(1))
    }

    @Test
    fun `every speed above 1x samples far more often than the unwarped cadence`() {
        SIMULATION_SPEEDS.filter { it > 1 }.forEach { speed ->
            assertTrue(
                uiTickerIntervalMillis(speed) < UI_TICKER_INTERVAL_MS,
                "speed ${speed}x should sample faster than the unwarped cadence"
            )
        }
    }

    @Test
    fun `a sample advances about a simulated minute, except where the floor binds`() {
        // The floor exists so 600x does not spin twice as fast as the eye can read; at that speed a sample
        // covers more than a simulated minute on purpose. Every slower speed hits the one-minute target.
        SIMULATION_SPEEDS.filter { it > 1 }.forEach { speed ->
            val interval = uiTickerIntervalMillis(speed)
            val simulatedMillisPerSample = interval * speed
            assertTrue(
                simulatedMillisPerSample <= 60_000L || interval == 500L,
                "speed ${speed}x advances ${simulatedMillisPerSample}ms of simulated time per sample without being floored"
            )
        }
    }

    @Test
    fun `no speed makes the readout jump by more than five simulated minutes`() {
        SIMULATION_SPEEDS.filter { it > 1 }.forEach { speed ->
            assertTrue(
                uiTickerIntervalMillis(speed) * speed <= 300_000L,
                "speed ${speed}x jumps too far per sample to read"
            )
        }
    }

    @Test
    fun `the fastest speed is floored rather than spinning`() {
        assertTrue(uiTickerIntervalMillis(600) >= 500L)
    }

    @Test
    fun `the interval never increases as the speed rises`() {
        val intervals = SIMULATION_SPEEDS.sorted().map { uiTickerIntervalMillis(it) }
        assertEquals(intervals.sortedDescending(), intervals)
    }
}
