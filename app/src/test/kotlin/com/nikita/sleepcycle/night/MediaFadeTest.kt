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

    // Owner decision, 2026-10-02 (phone log 22:03: a fade after waking restarted at the parked 6, not the
    // starting 8): every fade starts at exactly the starting volume, whatever was playing - a louder volume is
    // lowered to it and a quieter one raised to it.
    @Test fun `the fade starts at the default quarter of the range`() {
        assertEquals(6, fadeStartStep(maxStep = pixelMaxStep, startPercent = 25))
    }

    @Test fun `the fade starts at the starting volume chosen in Settings`() {
        assertEquals(3, fadeStartStep(maxStep = pixelMaxStep, startPercent = 10))
        assertEquals(13, fadeStartStep(maxStep = pixelMaxStep, startPercent = 50))
    }

    private val nightStart = Instant.parse("2026-10-02T23:00:00Z")
    private fun stepAt(minutes: Long, startStep: Int = 6, endPercent: Int = 5) =
        fadeTargetStep(startStep, pixelMaxStep, nightStart, nightStart.plusSeconds(minutes * 60), endPercent)

    // Owner request, 2026-10-02: the ending volume is a Settings choice (5% to 45%), not a fixed 5%.

    @Test fun `the fade stops at the ending volume chosen in Settings`() {
        // 20% of 25 steps is 5.
        assertEquals(5, stepAt(90, startStep = 13, endPercent = 20))
    }

    @Test fun `an ending volume at or above the starting volume never lowers it`() {
        assertEquals(6, stepAt(60, startStep = 6, endPercent = 45))
    }

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

    // Owner spec, 2026-10-02: from Start night to the morning alarm the fade's schedule wins over the owner's own
    // volume changes; once the morning alarm has rung the fade is over and his own volume comes back.

    private fun tickAt(minutes: Long, currentStep: Int, parked: Boolean = false, morningAlarmRang: Boolean = false) =
        fadeTickAction(startStep = 13, startedAt = nightStart, parked = parked, currentStep = currentStep, maxStep = pixelMaxStep, now = nightStart.plusSeconds(minutes * 60), endPercent = 5, morningAlarmRang = morningAlarmRang)

    @Test fun `a volume turned up mid-fade is pulled back to where the fade is`() {
        // 20 minutes in: the 10 minute hold, then 3 steps down from 13.
        assertEquals(FadeTickAction.SetVolume(10), tickAt(20, currentStep = 22))
    }

    @Test fun `a volume already where the fade wants it is left alone`() {
        assertEquals(FadeTickAction.None, tickAt(20, currentStep = 10))
    }

    // Phone test, 2026-10-02: the owner turned the volume down below the schedule (4) and the next tick raised it
    // back to the schedule's 5. The fade only ever lowers the volume.
    @Test fun `a volume turned down below the fade is left there`() {
        assertEquals(FadeTickAction.None, tickAt(20, currentStep = 4))
    }

    // Phone test, 2026-10-02: falling asleep at the faded 3 parked the volume back up at the starting 10, so
    // waking played louder than the owner fell asleep to. Parking only ever lowers too.
    @Test fun `falling asleep below the starting volume parks it where it is`() {
        assertEquals(3, fadeParkStep(currentStep = 3, startStep = 10))
    }

    @Test fun `falling asleep above the starting volume parks it at the starting volume`() {
        assertEquals(10, fadeParkStep(currentStep = 13, startStep = 10))
    }

    // Owner request, 2026-10-02: the simulation block shows the media volume as a percent of the phone's range.
    @Test fun `the media volume reads as a whole percent of the range`() {
        assertEquals(52, mediaVolumePercent(currentStep = 13, maxStep = pixelMaxStep))
        assertEquals(100, mediaVolumePercent(currentStep = 25, maxStep = pixelMaxStep))
        assertEquals(0, mediaVolumePercent(currentStep = 0, maxStep = pixelMaxStep))
    }

    @Test fun `a phone reporting no volume range reads as 0 percent`() {
        assertEquals(0, mediaVolumePercent(currentStep = 3, maxStep = 0))
    }

    @Test fun `a parked volume does not step down while the owner sleeps`() {
        assertEquals(FadeTickAction.None, tickAt(60, currentStep = 13, parked = true))
    }

    // Phone test, 2026-10-02: a volume key held in the night took the parked 3 up to 15 before any media played.
    @Test fun `a parked volume turned up above the starting volume is pulled back to it`() {
        assertEquals(FadeTickAction.SetVolume(13), tickAt(60, currentStep = 22, parked = true))
    }

    @Test fun `a parked volume turned down stays where it is`() {
        assertEquals(FadeTickAction.None, tickAt(60, currentStep = 4, parked = true))
    }

    @Test fun `once the morning alarm has rung the owner's own volume comes back, parked or not`() {
        assertEquals(FadeTickAction.RestoreOriginal, tickAt(20, currentStep = 10, morningAlarmRang = true))
        assertEquals(FadeTickAction.RestoreOriginal, tickAt(60, currentStep = 13, parked = true, morningAlarmRang = true))
    }

    // Owner request, 2026-10-02: falling asleep parks the volume at the starting volume until End night, so a
    // fade after waking must remember the volume the night began with, not the parked one.

    @Test fun `a fade after a sleep keeps the night's own original volume for End night`() {
        assertEquals(25, fadeOriginalStep(currentStep = 13, parkedOriginalStep = 25))
    }

    @Test fun `the night's first fade takes the volume playing at the time as the original`() {
        assertEquals(20, fadeOriginalStep(currentStep = 20, parkedOriginalStep = null))
    }

    // Owner request, 2026-10-02: waking in the night and playing the audiobook again fades it again.

    @Test fun `after falling asleep, waking with media playing starts a fresh fade`() {
        assertTrue(shouldStartWakeFade(fadeRearmed = true, mediaPlaying = true, fadeEnabled = true, morningAlarmRang = false))
    }

    @Test fun `waking with nothing playing waits until media plays`() {
        assertFalse(shouldStartWakeFade(fadeRearmed = true, mediaPlaying = false, fadeEnabled = true, morningAlarmRang = false))
    }

    @Test fun `the Start night fade begins once media is actually playing, before the first sleep`() {
        // Seen on the phone, 2026-10-02: setting the volume at the Start night tap, before the audiobook played,
        // changed a volume nobody heard, and the next tick read the playing device's own volume as an override.
        assertTrue(shouldStartWakeFade(fadeRearmed = true, mediaPlaying = true, fadeEnabled = true, morningAlarmRang = false))
    }

    @Test fun `no new fade once one has started, until the next sleep re-arms it`() {
        // Not armed: a fade is already running, or the owner turned the volume up himself and ended it.
        assertFalse(shouldStartWakeFade(fadeRearmed = false, mediaPlaying = true, fadeEnabled = true, morningAlarmRang = false))
        assertFalse(shouldStartWakeFade(fadeRearmed = false, mediaPlaying = true, fadeEnabled = true, morningAlarmRang = false))
    }

    @Test fun `no fresh fade with the fade switched off, or once the morning alarm has rung`() {
        assertFalse(shouldStartWakeFade(fadeRearmed = true, mediaPlaying = true, fadeEnabled = false, morningAlarmRang = false))
        assertFalse(shouldStartWakeFade(fadeRearmed = true, mediaPlaying = true, fadeEnabled = true, morningAlarmRang = true))
    }

    // Emulator test, 2026-10-03 (owner decision): media started while the band still says asleep (it confirms
    // an awakening late, or never when the owner lies still) used to play at the parked volume all night. It now
    // gets a fresh fade like any awakening, and a pause once that fade has run its course if the band still says
    // asleep ([shouldPauseFinishedFade]).

    @Test fun `media started while the band still says asleep gets a fresh fade too`() {
        // The decision no longer looks at the sleep state at all: armed, playing, switched on, before the alarm.
        assertTrue(shouldStartWakeFade(fadeRearmed = true, mediaPlaying = true, fadeEnabled = true, morningAlarmRang = false))
    }

    // Starting step 8 of 15 with the ending volume at 5% (floor 1): the 10 minute hold, then 8 to 1 in 7 steps,
    // the last at 10 + 6 * 5 = 40 minutes.
    private fun endsAt(startStep: Int, endPercent: Int = 5) = fadeEndsAt(startStep, maxStep = 15, startedAt = nightStart, endPercent = endPercent)

    @Test fun `a fade has run its course once it reaches the ending volume`() {
        assertEquals(nightStart.plusSeconds(40 * 60), endsAt(startStep = 8))
    }

    @Test fun `a fade starting at or below the ending volume runs its course after the hold`() {
        assertEquals(nightStart.plusSeconds(10 * 60), endsAt(startStep = 1))
        assertEquals(nightStart.plusSeconds(10 * 60), endsAt(startStep = 4, endPercent = 45))
    }

    private fun pauseAt(minutes: Long, sleepState: SleepState = SleepState.ASLEEP, mediaPlaying: Boolean = true, morningAlarmRang: Boolean = false) =
        shouldPauseFinishedFade(sleepState, mediaPlaying, fadeEndsAt = nightStart.plusSeconds(40 * 60), now = nightStart.plusSeconds(minutes * 60), morningAlarmRang = morningAlarmRang)

    @Test fun `media still playing when the fade has run its course while the band says asleep is paused`() {
        assertTrue(pauseAt(40))
        assertTrue(pauseAt(55))
    }

    @Test fun `no pause before the fade has run its course`() {
        assertFalse(pauseAt(39))
    }

    @Test fun `no pause once the band says awake, with nothing playing, or after the morning alarm`() {
        assertFalse(pauseAt(40, sleepState = SleepState.AWAKE))
        assertFalse(pauseAt(40, sleepState = SleepState.NOT_YET_ASLEEP))
        assertFalse(pauseAt(40, mediaPlaying = false))
        assertFalse(pauseAt(40, morningAlarmRang = true))
    }

    // Ticks come every 15 minutes while asleep, so a fade there would step late and pause late; each tick books
    // the next one no later than the fade's next step, up to the instant it has run its course.
    private fun nextStepAt(minutes: Long) = nextFadeStepAt(startStep = 8, maxStep = 15, startedAt = nightStart, now = nightStart.plusSeconds(minutes * 60), endPercent = 5)

    @Test fun `the next fade step is the hold's end, then every 5 minutes`() {
        assertEquals(nightStart.plusSeconds(10 * 60), nextStepAt(0))
        assertEquals(nightStart.plusSeconds(15 * 60), nextStepAt(10))
        assertEquals(nightStart.plusSeconds(20 * 60), nextStepAt(17))
    }

    @Test fun `the last fade step is the instant it has run its course, and none after`() {
        assertEquals(nightStart.plusSeconds(40 * 60), nextStepAt(36))
        assertEquals(null, nextStepAt(40))
    }
}
