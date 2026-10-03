package com.nikita.sleepcycle.ui.state

// File purpose: the speed chips the Night screen's Simulation card shows (owner spec, 2026-10-02) - 1x, 60x
// and Auto, which one is selected, and the live speed Auto is running at.

import com.nikita.sleepcycle.night.ClockWarp
import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.SpeedChoice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class BuildDebugUiStateTest {
    private val warpAnchor = Instant.parse("2026-10-02T14:00:00Z")

    @Test
    fun `the chips are 1x, 60x and Auto`() {
        val state = buildDebugUiState(DebugOptions(simulatedBandData = true), emptyList(), nightActive = true)
        assertEquals(listOf(SpeedChoice.REAL, SpeedChoice.FAST, SpeedChoice.AUTO), state.speedChoices)
        assertEquals(SpeedChoice.REAL, state.selectedSpeed)
    }

    @Test
    fun `under Auto the Auto chip is selected and shows the speed it is running at`() {
        val options = DebugOptions(simulatedBandData = true, warp = ClockWarp(60, warpAnchor, warpAnchor), autoSpeed = true)
        val state = buildDebugUiState(options, emptyList(), nightActive = true)
        assertEquals(SpeedChoice.AUTO, state.selectedSpeed)
        assertEquals(60, state.autoRunningSpeed)
    }

    @Test
    fun `outside Auto there is no running speed to show on the Auto chip`() {
        val options = DebugOptions(simulatedBandData = true, warp = ClockWarp(60, warpAnchor, warpAnchor))
        val state = buildDebugUiState(options, emptyList(), nightActive = true)
        assertEquals(SpeedChoice.FAST, state.selectedSpeed)
        assertEquals(null, state.autoRunningSpeed)
    }
}
