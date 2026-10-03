package com.nikita.sleepcycle.night

// File purpose: the pure decisions behind the bedtime media fade - where it starts and which volume step it
// should be at by a given minute (see MediaFade.kt). Worked examples use the Pixel 10's 25-step media volume.

import com.nikita.sleepcycle.engine.SleepState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class MediaFadeTest {
    private val pixelMaxStep = 25

    @Test fun `a loud starting volume is lowered to the default quarter`() {
        assertEquals(6, fadeStartStep(currentStep = 16, maxStep = pixelMaxStep, startPercent = 25))
    }

    @Test fun `a volume already below the starting volume is never raised`() {
        assertEquals(4, fadeStartStep(currentStep = 4, maxStep = pixelMaxStep, startPercent = 25))
    }

    @Test fun `the fade starts at the starting volume chosen in Settings`() {
        assertEquals(3, fadeStartStep(currentStep = 20, maxStep = pixelMaxStep, startPercent = 10))
        assertEquals(13, fadeStartStep(currentStep = 20, maxStep = pixelMaxStep, startPercent = 50))
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

    // Owner request, 2026-10-02: waking in the night and playing the audiobook again fades it again.

    @Test fun `after falling asleep, waking with media playing starts a fresh fade`() {
        assertTrue(shouldStartWakeFade(fadeRearmed = true, sleepState = SleepState.AWAKE, mediaPlaying = true, fadeEnabled = true, morningAlarmRang = false))
    }

    @Test fun `waking with nothing playing waits until media plays`() {
        assertFalse(shouldStartWakeFade(fadeRearmed = true, sleepState = SleepState.AWAKE, mediaPlaying = false, fadeEnabled = true, morningAlarmRang = false))
    }

    @Test fun `no fresh fade before the first sleep, or once a fade has already started since`() {
        // Not rearmed: the Start night fade is still the one running, or the owner turned the volume up himself.
        assertFalse(shouldStartWakeFade(fadeRearmed = false, sleepState = SleepState.AWAKE, mediaPlaying = true, fadeEnabled = true, morningAlarmRang = false))
        assertFalse(shouldStartWakeFade(fadeRearmed = true, sleepState = SleepState.NOT_YET_ASLEEP, mediaPlaying = true, fadeEnabled = true, morningAlarmRang = false))
    }

    @Test fun `no fresh fade while asleep, with the fade switched off, or once the morning alarm has rung`() {
        assertFalse(shouldStartWakeFade(fadeRearmed = true, sleepState = SleepState.ASLEEP, mediaPlaying = true, fadeEnabled = true, morningAlarmRang = false))
        assertFalse(shouldStartWakeFade(fadeRearmed = true, sleepState = SleepState.AWAKE, mediaPlaying = true, fadeEnabled = false, morningAlarmRang = false))
        assertFalse(shouldStartWakeFade(fadeRearmed = true, sleepState = SleepState.AWAKE, mediaPlaying = true, fadeEnabled = true, morningAlarmRang = true))
    }
}
