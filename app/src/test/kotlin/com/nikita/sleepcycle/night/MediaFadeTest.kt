package com.nikita.sleepcycle.night

// File purpose: the pure decisions behind the bedtime media fade (see MediaFade.kt) - where it starts, the
// smooth target line it follows in dB, how that line is split into a hardware volume step plus a small digital
// gain, and how the owner's own volume changes, the screen and a switch of output move the line. Worked
// examples use the Pixel 10's 25-step media volume.

import com.nikita.sleepcycle.engine.SleepState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class MediaFadeTest {
    private val pixelMaxStep = 25

    // A made-up curve that is easy to reason about: step N is 2N dB below the top, so every step is 2 dB.
    private val evenCurve = VolumeCurve(listOf(Double.NEGATIVE_INFINITY) + (1..pixelMaxStep).map { 2.0 * (it - pixelMaxStep) })

    // Owner decision, 2026-10-02 (phone log 22:03: a fade after waking restarted at the parked 6, not the
    // starting 8): every fade starts at exactly the starting volume, whatever was playing - a louder volume is
    // lowered to it and a quieter one raised to it.
    @Test fun `the fade starts at the default quarter of the range`() {
        assertEquals(6, fadeStartStep(maxStep = pixelMaxStep, startPercent = 25))
    }

    @Test fun `the fade starts at the starting volume chosen in Settings`() {
        assertEquals(3, fadeStartStep(maxStep = pixelMaxStep, startPercent = 12))
        assertEquals(13, fadeStartStep(maxStep = pixelMaxStep, startPercent = 52))
    }

    @Test fun `the fade's floor is the ending volume's step, never below one step`() {
        assertEquals(1, fadeFloorStep(maxStep = pixelMaxStep, endPercent = 4))
        assertEquals(5, fadeFloorStep(maxStep = pixelMaxStep, endPercent = 20))
        assertEquals(1, fadeFloorStep(maxStep = pixelMaxStep, endPercent = 0))
    }

    // Owner spec, 2026-10-22 (PER-134): "you specify starting sound, ending percentage, and the duration...
    // automatically calculates the speed". The speed is in dB per minute on the playing output's own curve.

    @Test fun `the speed is the starting volume's dB down to the ending volume's dB over the fade duration`() {
        // Step 6 is -38 dB and step 1 is -48 dB on the even curve: 10 dB over 15 minutes.
        assertEquals(10.0 / 15, fadeRateDbPerMinute(evenCurve.db(6), evenCurve.db(1), Duration.ofMinutes(15)), 1e-9)
    }

    @Test fun `an ending volume at or above the starting volume gives no speed at all`() {
        assertEquals(0.0, fadeRateDbPerMinute(evenCurve.db(3), evenCurve.db(5), Duration.ofMinutes(15)))
    }

    private val fadeStart = Instant.parse("2026-10-22T23:00:00Z")
    private fun minutes(value: Double): Instant = fadeStart.plusMillis((value * 60_000).toLong())

    @Test fun `the target line falls at the speed and holds at the ending volume`() {
        val line = FadeLine(anchorDb = -38.0, anchorAt = fadeStart)
        assertEquals(-38.0, fadeTargetDb(line, rateDbPerMinute = 1.0, endDb = -48.0, now = fadeStart), 1e-9)
        assertEquals(-40.5, fadeTargetDb(line, rateDbPerMinute = 1.0, endDb = -48.0, now = minutes(2.5)), 1e-9)
        assertEquals(-48.0, fadeTargetDb(line, rateDbPerMinute = 1.0, endDb = -48.0, now = minutes(30.0)), 1e-9)
    }

    @Test fun `a line already below the ending volume stays where it is`() {
        val line = FadeLine(anchorDb = -50.0, anchorAt = fadeStart)
        assertEquals(-50.0, fadeTargetDb(line, rateDbPerMinute = 1.0, endDb = -48.0, now = minutes(10.0)), 1e-9)
    }

    // Owner, 2026-10-21: "I don't want it to start dropping the audio while the screen is still active." The line
    // freezes while the screen is on (or nothing plays) and carries on from there once it goes off - no reset.

    @Test fun `while the screen is on the line holds and moves its anchor to now`() {
        val line = FadeLine(anchorDb = -40.0, anchorAt = fadeStart)
        assertEquals(FadeLine(-40.0, minutes(5.0)), advanceFadeLine(line, 1.0, -48.0, minutes(5.0), descendingSinceAnchor = false))
    }

    @Test fun `once the screen goes off the line continues from where it froze, with no jump`() {
        // Frozen at -40 dB at minute 5; the screen went off at minute 20. The first update only re-anchors.
        val frozen = FadeLine(anchorDb = -40.0, anchorAt = minutes(5.0))
        val resumed = advanceFadeLine(frozen, 1.0, -48.0, minutes(20.0), descendingSinceAnchor = false)
        assertEquals(-40.0, resumed.anchorDb, 1e-9)
        assertEquals(-41.0, advanceFadeLine(resumed, 1.0, -48.0, minutes(21.0), descendingSinceAnchor = true).anchorDb, 1e-9)
    }

    // The hybrid (spike, 2026-10-21, "this worked amazingly"): the hardware sits on the lowest step still at or
    // above the target line, and a digital gain fills the rest, so the loudness follows the line smoothly.

    @Test fun `the hardware sits on the lowest step at or above the target and the gain fills the gap`() {
        // -40.5 dB on the even curve: step 5 is -40 dB, step 4 is -42 dB. Step 5 plus -0.5 dB.
        assertEquals(FadeSplit(step = 5, gainDb = -0.5), splitFadeTarget(-40.5, evenCurve, currentStep = 5, floorStep = 1))
    }

    @Test fun `on a step exactly the gain is zero`() {
        assertEquals(FadeSplit(step = 4, gainDb = 0.0), splitFadeTarget(-42.0, evenCurve, currentStep = 5, floorStep = 1))
    }

    @Test fun `the hardware never drops more than one step in one update, so drops never bunch`() {
        // Target -45 dB wants step 3 (-44 dB), three steps down from 6: this update only goes to 5.
        val split = splitFadeTarget(-45.0, evenCurve, currentStep = 6, floorStep = 1)
        assertEquals(5, split.step)
        assertEquals(-2.0, split.gainDb, 1e-9, "the gain never reaches past the next step down")
    }

    @Test fun `the hardware never goes below the ending volume's step`() {
        assertEquals(FadeSplit(step = 3, gainDb = 0.0), splitFadeTarget(-44.0, evenCurve, currentStep = 3, floorStep = 3))
    }

    @Test fun `a volume the owner took below the ending volume is left alone`() {
        assertEquals(FadeSplit(step = 2, gainDb = 0.0), splitFadeTarget(-44.0, evenCurve, currentStep = 2, floorStep = 3))
        assertEquals(FadeSplit(step = 0, gainDb = 0.0), splitFadeTarget(-44.0, evenCurve, currentStep = 0, floorStep = 1))
    }

    @Test fun `the fade never raises the hardware above where it is`() {
        assertEquals(FadeSplit(step = 4, gainDb = 0.0), splitFadeTarget(-30.0, evenCurve, currentStep = 4, floorStep = 1))
    }

    // Owner spec, 2026-10-22 (PER-134): "in case you increase the volume myself, it's going to still be fading
    // away with the same rate." A volume the owner changed himself becomes the line's new start - raised or
    // lowered - and a switch of output (Bluetooth connects or drops) starts the line again from that output's
    // own volume (PER-135). Today's pull back to the schedule is gone.

    @Test fun `a volume the owner changed himself re-anchors the line`() {
        assertEquals(FadeReanchor.OWNER_VOLUME, fadeReanchorCause(recordedStep = 5, actualStep = 9, recordedDeviceType = 2, actualDeviceType = 2))
        assertEquals(FadeReanchor.OWNER_VOLUME, fadeReanchorCause(recordedStep = 5, actualStep = 3, recordedDeviceType = 2, actualDeviceType = 2))
    }

    @Test fun `a switch of output re-anchors the line, whatever its volume`() {
        assertEquals(FadeReanchor.OUTPUT, fadeReanchorCause(recordedStep = 5, actualStep = 5, recordedDeviceType = 2, actualDeviceType = 8))
        assertEquals(FadeReanchor.OUTPUT, fadeReanchorCause(recordedStep = 5, actualStep = 12, recordedDeviceType = 2, actualDeviceType = 8))
    }

    @Test fun `nothing changed, nothing re-anchors`() {
        assertEquals(FadeReanchor.NONE, fadeReanchorCause(recordedStep = 5, actualStep = 5, recordedDeviceType = 2, actualDeviceType = 2))
    }

    @Test fun `a raised volume keeps falling at the same speed, so it takes longer`() {
        // Raised to step 9 (-32 dB) at minute 6: at 1 dB a minute it is at -37 dB five minutes later.
        val raised = FadeLine(evenCurve.db(9), minutes(6.0))
        assertEquals(-37.0, fadeTargetDb(raised, 1.0, -48.0, minutes(11.0)), 1e-9)
        assertEquals(minutes(22.0), fadeProjectedEndAt(raised, 1.0, -48.0, startedAt = fadeStart, duration = Duration.ofMinutes(15)))
    }

    // Emulator test, 2026-10-03 (owner decision), kept: a fade that has run its course while the band still says
    // asleep pauses its media. It has run its course once the line reached the ending volume, and never before
    // the fade duration, so media pressed while asleep is never paused the moment it starts.

    @Test fun `a fade has run its course at the ending volume once the fade duration has passed`() {
        val duration = Duration.ofMinutes(15)
        assertTrue(fadeHasRunItsCourse(-48.0, -48.0, fadeStart, minutes(15.0), duration))
        assertFalse(fadeHasRunItsCourse(-47.0, -48.0, fadeStart, minutes(20.0), duration))
        assertFalse(fadeHasRunItsCourse(-48.0, -48.0, fadeStart, minutes(3.0), duration), "lowered to the floor by hand at minute 3")
    }

    @Test fun `the projected end is the line reaching the ending volume, never before the fade duration`() {
        val line = FadeLine(-38.0, fadeStart)
        assertEquals(minutes(10.0), fadeProjectedEndAt(line, 1.0, -48.0, fadeStart, Duration.ofMinutes(5)))
        assertEquals(minutes(15.0), fadeProjectedEndAt(line, 1.0, -48.0, fadeStart, Duration.ofMinutes(15)))
        assertEquals(minutes(15.0), fadeProjectedEndAt(line, 0.0, -48.0, fadeStart, Duration.ofMinutes(15)), "no speed: it holds for the duration")
    }

    private fun pause(finished: Boolean, sleepState: SleepState = SleepState.ASLEEP, mediaPlaying: Boolean = true, morningAlarmRang: Boolean = false) =
        shouldPauseFinishedFade(sleepState, mediaPlaying, fadeFinished = finished, morningAlarmRang = morningAlarmRang)

    @Test fun `media still playing when the fade has run its course while the band says asleep is paused`() {
        assertTrue(pause(finished = true))
        assertFalse(pause(finished = false))
    }

    @Test fun `no pause once the band says awake, with nothing playing, or after the morning alarm`() {
        assertFalse(pause(true, sleepState = SleepState.AWAKE))
        assertFalse(pause(true, sleepState = SleepState.NOT_YET_ASLEEP))
        assertFalse(pause(true, mediaPlaying = false))
        assertFalse(pause(true, morningAlarmRang = true))
    }

    // The phone's own dB curve (AudioManager.getStreamVolumeDb) is used when it makes sense; otherwise AOSP's
    // default media curve, so the line is always in real loudness steps.

    @Test fun `a curve that rises step by step is usable`() {
        assertTrue(isUsableVolumeCurve(evenCurve.stepDbs))
    }

    @Test fun `a flat or broken curve is not, and the default curve is`() {
        assertFalse(isUsableVolumeCurve(List(pixelMaxStep + 1) { 0.0 }))
        assertFalse(isUsableVolumeCurve(listOf(Double.NEGATIVE_INFINITY, -50.0, Double.NaN, -10.0)))
        assertTrue(isUsableVolumeCurve(defaultVolumeCurve(pixelMaxStep).stepDbs))
        assertTrue(isUsableVolumeCurve(defaultVolumeCurve(15).stepDbs))
    }

    @Test fun `the default curve runs from about -58 dB at the bottom to 0 dB at the top`() {
        val curve = defaultVolumeCurve(100)
        assertEquals(-58.0, curve.db(1), 1e-9)
        assertEquals(-40.0, curve.db(20), 1e-9)
        assertEquals(-17.0, curve.db(60), 1e-9)
        assertEquals(0.0, curve.db(100), 1e-9)
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

    // Owner spec, 2026-10-02: once the morning alarm has rung the fade is over and his own volume comes back; a
    // parked volume (asleep) never goes above the starting volume and never steps down.

    private fun tick(currentStep: Int, parked: Boolean, morningAlarmRang: Boolean = false) =
        fadeTickAction(startStep = 13, parked = parked, currentStep = currentStep, morningAlarmRang = morningAlarmRang)

    @Test fun `a parked volume does not step down while the owner sleeps`() {
        assertEquals(FadeTickAction.None, tick(currentStep = 13, parked = true))
    }

    // Phone test, 2026-10-02: a volume key held in the night took the parked 3 up to 15 before any media played.
    @Test fun `a parked volume turned up above the starting volume is pulled back to it`() {
        assertEquals(FadeTickAction.SetVolume(13), tick(currentStep = 22, parked = true))
    }

    @Test fun `a parked volume turned down stays where it is`() {
        assertEquals(FadeTickAction.None, tick(currentStep = 4, parked = true))
    }

    @Test fun `a running fade is moved along its line`() {
        assertEquals(FadeTickAction.Advance, tick(currentStep = 22, parked = false))
    }

    @Test fun `once the morning alarm has rung the owner's own volume comes back, parked or not`() {
        assertEquals(FadeTickAction.RestoreOriginal, tick(currentStep = 10, parked = false, morningAlarmRang = true))
        assertEquals(FadeTickAction.RestoreOriginal, tick(currentStep = 13, parked = true, morningAlarmRang = true))
    }

    // Owner request, 2026-10-02: falling asleep parks the volume at the starting volume until End night, so a
    // fade after waking must remember the volume the night began with, not the parked one.

    @Test fun `a fade after a sleep keeps the night's own original volume for End night`() {
        assertEquals(25, fadeOriginalStep(currentStep = 13, parkedOriginalStep = 25))
    }

    @Test fun `the night's first fade takes the volume playing at the time as the original`() {
        assertEquals(20, fadeOriginalStep(currentStep = 20, parkedOriginalStep = null))
    }

    // Owner request, 2026-10-02: waking in the night and playing the audiobook again fades it again. Owner,
    // 2026-10-21: a fade begins only once the screen is off too.

    private fun shouldStart(rearmed: Boolean = true, playing: Boolean = true, screenOff: Boolean = true, enabled: Boolean = true, alarmRang: Boolean = false) =
        shouldStartWakeFade(fadeRearmed = rearmed, mediaPlaying = playing, screenOff = screenOff, fadeEnabled = enabled, morningAlarmRang = alarmRang)

    @Test fun `armed, with media playing and the screen off, a fade starts`() {
        assertTrue(shouldStart())
    }

    @Test fun `no fade starts while the screen is on`() {
        assertFalse(shouldStart(screenOff = false))
    }

    @Test fun `waking with nothing playing waits until media plays`() {
        assertFalse(shouldStart(playing = false))
    }

    @Test fun `no new fade once one has started, until the next sleep re-arms it`() {
        assertFalse(shouldStart(rearmed = false))
    }

    @Test fun `no fresh fade with the fade switched off, or once the morning alarm has rung`() {
        assertFalse(shouldStart(enabled = false))
        assertFalse(shouldStart(alarmRang = true))
    }
}
