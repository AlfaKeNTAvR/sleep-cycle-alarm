package com.nikita.sleepcycle.night

// File purpose: the pure decisions behind the bedtime media fade - where it starts and which volume step it
// should be at by a given minute (see MediaFade.kt). Worked examples use the Pixel 10's 25-step media volume.

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class MediaFadeTest {
    private val pixelMaxStep = 25

    @Test fun `a loud starting volume is lowered to about a quarter`() {
        assertEquals(6, fadeStartStep(currentStep = 16, maxStep = pixelMaxStep))
    }

    @Test fun `a volume already below a quarter is never raised`() {
        assertEquals(4, fadeStartStep(currentStep = 4, maxStep = pixelMaxStep))
    }

    private val nightStart = Instant.parse("2026-10-02T23:00:00Z")
    private fun stepAt(minutes: Long, startStep: Int = 6) =
        fadeTargetStep(startStep, pixelMaxStep, nightStart, nightStart.plusSeconds(minutes * 60))

    @Test fun `the volume holds for the first 10 minutes`() {
        assertEquals(6, stepAt(0))
        assertEquals(6, stepAt(9))
    }

    @Test fun `after the hold the volume loses one step every 5 minutes`() {
        assertEquals(5, stepAt(10))
        assertEquals(5, stepAt(14))
        assertEquals(4, stepAt(15))
        assertEquals(3, stepAt(20))
        assertEquals(2, stepAt(25))
    }

    @Test fun `the volume stops at the 1-step floor`() {
        assertEquals(1, stepAt(30))
        assertEquals(1, stepAt(90))
    }

    @Test fun `a fade starting at the floor stays there`() {
        assertEquals(1, stepAt(20, startStep = 1))
    }

    @Test fun `a muted volume stays muted`() {
        assertEquals(0, stepAt(20, startStep = 0))
    }
}
