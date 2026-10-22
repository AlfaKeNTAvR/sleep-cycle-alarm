package com.nikita.sleepcycle.night

// File purpose: the per-output starting and ending volumes of the bedtime fade (owner spec, 2026-10-22,
// PER-135) - the choices are the phone's own media volume steps shown as their percent, a stored value from an
// older build snaps to the nearest step, and each output keeps its own pair. Worked examples use the Pixel 10's
// 25-step media volume (4% a step) and the emulator's 15 steps.

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FadeVolumeSettingsTest {
    private val pixelMaxStep = 25

    // Owner, 2026-10-22: "there is no point in setting up in divisions of 5%, because everything works in 4%...
    // do the same levels as the phone."

    @Test fun `the starting volume offers every phone step from about 10 to about 50 percent`() {
        assertEquals(listOf(12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52), fadeVolumeChoices(FadeVolumeKind.START, pixelMaxStep))
    }

    @Test fun `the ending volume offers every phone step from about 5 to about 45 percent, never 0`() {
        assertEquals(listOf(4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44), fadeVolumeChoices(FadeVolumeKind.END, pixelMaxStep))
    }

    @Test fun `the choices follow the phone's own step count`() {
        // 15 steps: 6.67% a step, shown rounded the way the live volume line shows them.
        assertEquals(listOf(13, 20, 27, 33, 40, 47, 53), fadeVolumeChoices(FadeVolumeKind.START, 15))
    }

    @Test fun `every choice is the percent the live volume line shows for that step`() {
        for (percent in fadeVolumeChoices(FadeVolumeKind.START, pixelMaxStep)) {
            assertEquals(percent, mediaVolumePercent(fadeVolumeStep(percent, pixelMaxStep), pixelMaxStep))
        }
    }

    @Test fun `the old defaults snap to the nearest phone step`() {
        // Migration: 25% was step 6.25, now step 6 (24%); 5% was step 1.25, now step 1 (4%).
        assertEquals(24, snapFadeVolumePercent(FadeVolumeKind.START, 25, pixelMaxStep))
        assertEquals(4, snapFadeVolumePercent(FadeVolumeKind.END, 5, pixelMaxStep))
    }

    @Test fun `an old value exactly between two steps snaps up`() {
        // 10% is step 2.5: ties go up, to step 3 (12%).
        assertEquals(12, snapFadeVolumePercent(FadeVolumeKind.START, 10, pixelMaxStep))
        assertEquals(20, snapFadeVolumePercent(FadeVolumeKind.END, 20, pixelMaxStep))
    }

    @Test fun `a value outside the offered range snaps to its nearest end`() {
        assertEquals(52, snapFadeVolumePercent(FadeVolumeKind.START, 100, pixelMaxStep))
        assertEquals(4, snapFadeVolumePercent(FadeVolumeKind.END, 0, pixelMaxStep))
    }

    @Test fun `one tap moves one phone step, and stops at the ends`() {
        assertEquals(28, stepFadeVolume(FadeVolumeKind.START, 24, +1, pixelMaxStep))
        assertEquals(20, stepFadeVolume(FadeVolumeKind.START, 24, -1, pixelMaxStep))
        // An old 25% steps from its snapped 24%.
        assertEquals(28, stepFadeVolume(FadeVolumeKind.START, 25, +1, pixelMaxStep))
        assertEquals(52, stepFadeVolume(FadeVolumeKind.START, 52, +1, pixelMaxStep))
        assertEquals(4, stepFadeVolume(FadeVolumeKind.END, 4, -1, pixelMaxStep))
        assertFalse(canStepFadeVolume(FadeVolumeKind.END, 4, -1, pixelMaxStep))
        assertTrue(canStepFadeVolume(FadeVolumeKind.END, 4, +1, pixelMaxStep))
    }

    @Test fun `a fade starts at the step the shown percent names`() {
        assertEquals(6, fadeStartStep(maxStep = pixelMaxStep, startPercent = 24))
        assertEquals(6, fadeStartStep(maxStep = pixelMaxStep, startPercent = 25))
    }

    // Owner, 2026-10-22: "those settings get remembered per device and automatically switched depending on
    // whether I'm listening from the speaker or from the phone." Wired and anything else count as Phone speaker.

    @Test fun `each output has its own pair, both at the old defaults to begin with`() {
        val audio = BedtimeAudioSettings()
        assertEquals(FadeVolumes(startPercent = 25, endPercent = 5), audio.volumesFor(FadeOutput.PHONE_SPEAKER))
        assertEquals(FadeVolumes(startPercent = 25, endPercent = 5), audio.volumesFor(FadeOutput.BLUETOOTH))
    }

    @Test fun `changing one output's pair leaves the other's alone`() {
        val audio = BedtimeAudioSettings().withVolumesFor(FadeOutput.BLUETOOTH, FadeVolumes(startPercent = 40, endPercent = 12))
        assertEquals(FadeVolumes(startPercent = 40, endPercent = 12), audio.volumesFor(FadeOutput.BLUETOOTH))
        assertEquals(FadeVolumes(startPercent = 25, endPercent = 5), audio.volumesFor(FadeOutput.PHONE_SPEAKER))
    }

    // Migration: the single pair saved before PER-135 becomes both outputs' pair until each is changed.

    @Test fun `the single pair saved by an older build becomes both outputs' pair`() {
        val stored = mutablePreferencesOf(intPreferencesKey("fade_start_percent") to 30, intPreferencesKey("fade_end_percent") to 10)
        val audio = readBedtimeAudioSettings(stored)
        assertEquals(FadeVolumes(startPercent = 30, endPercent = 10), audio.phoneSpeakerVolumes)
        assertEquals(FadeVolumes(startPercent = 30, endPercent = 10), audio.bluetoothVolumes)
        // Shown and used as the nearest phone steps: 30% is step 7.5, up to 32%; 10% is step 2.5, up to 12%.
        assertEquals(32, snapFadeVolumePercent(FadeVolumeKind.START, audio.phoneSpeakerVolumes.startPercent, pixelMaxStep))
        assertEquals(12, snapFadeVolumePercent(FadeVolumeKind.END, audio.phoneSpeakerVolumes.endPercent, pixelMaxStep))
    }

    @Test fun `an output's own saved pair wins over the old single pair`() {
        val stored = mutablePreferencesOf(
            intPreferencesKey("fade_start_percent") to 30,
            intPreferencesKey("fade_bluetooth_start_percent") to 44,
        )
        val audio = readBedtimeAudioSettings(stored)
        assertEquals(44, audio.bluetoothVolumes.startPercent)
        assertEquals(30, audio.phoneSpeakerVolumes.startPercent)
    }

    @Test fun `nothing saved yet reads as the defaults`() {
        assertEquals(BedtimeAudioSettings(), readBedtimeAudioSettings(mutablePreferencesOf()))
    }

    // Owner spec, 2026-10-22 (PER-134): one shared fade duration, 5 to 60 minutes in 5 minute steps, default 15.

    @Test fun `the fade duration steps five minutes at a time between 5 and 60, default 15`() {
        assertEquals(15, BedtimeAudioSettings().fadeDurationMinutes)
        assertEquals(20, stepSetting(SettingStepper.FADE_DURATION_MINUTES, 15, +1))
        assertEquals(60, stepSetting(SettingStepper.FADE_DURATION_MINUTES, 60, +1))
        assertEquals(5, stepSetting(SettingStepper.FADE_DURATION_MINUTES, 5, -1))
    }
}
