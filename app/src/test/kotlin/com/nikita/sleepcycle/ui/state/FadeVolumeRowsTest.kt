package com.nikita.sleepcycle.ui.state

// File purpose: owner spec, 2026-10-22 (PER-135) - the Settings screen's Bedtime audio section shows ONE
// starting and ending volume pair, the pair of the output media plays on now, named in the section header
// ("BEDTIME AUDIO (BLUETOOTH)"), and an edit there writes only that output's pair. Owner: "Changing them, given
// the mode, only records them for this specific mode, either for Bluetooth or for phone speaker."

import com.nikita.sleepcycle.night.BedtimeAudioSettings
import com.nikita.sleepcycle.night.FadeOutput
import com.nikita.sleepcycle.night.FadeVolumeKind
import com.nikita.sleepcycle.night.FadeVolumes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FadeVolumeRowsTest {
    private val pixelMaxStep = 25
    private val audio = BedtimeAudioSettings(
        phoneSpeakerVolumes = FadeVolumes(startPercent = 24, endPercent = 4),
        bluetoothVolumes = FadeVolumes(startPercent = 40, endPercent = 12),
    )

    @Test fun `Bluetooth is its own output, everything else uses the phone speaker's pair`() {
        assertEquals(FadeOutput.BLUETOOTH, fadeOutputFor(MediaOutput.BLUETOOTH))
        assertEquals(FadeOutput.PHONE_SPEAKER, fadeOutputFor(MediaOutput.SPEAKER))
        assertEquals(FadeOutput.PHONE_SPEAKER, fadeOutputFor(MediaOutput.HEADPHONES))
    }

    @Test fun `on the speaker the rows show the phone speaker's pair`() {
        val rows = buildFadeVolumeRows(audio, MediaOutput.SPEAKER, pixelMaxStep)
        assertEquals(FadeOutput.PHONE_SPEAKER, rows.output)
        assertEquals(24, rows.startPercent)
        assertEquals(4, rows.endPercent)
    }

    @Test fun `on Bluetooth the rows show the Bluetooth pair`() {
        val rows = buildFadeVolumeRows(audio, MediaOutput.BLUETOOTH, pixelMaxStep)
        assertEquals(FadeOutput.BLUETOOTH, rows.output)
        assertEquals(40, rows.startPercent)
        assertEquals(12, rows.endPercent)
    }

    @Test fun `on wired headphones the rows show the phone speaker's pair`() {
        assertEquals(FadeOutput.PHONE_SPEAKER, buildFadeVolumeRows(audio, MediaOutput.HEADPHONES, pixelMaxStep).output)
    }

    @Test fun `when the output changes the rows switch to that output's pair`() {
        assertEquals(24, buildFadeVolumeRows(audio, MediaOutput.SPEAKER, pixelMaxStep).startPercent)
        assertEquals(40, buildFadeVolumeRows(audio, MediaOutput.BLUETOOTH, pixelMaxStep).startPercent)
        assertEquals(24, buildFadeVolumeRows(audio, MediaOutput.SPEAKER, pixelMaxStep).startPercent)
    }

    @Test fun `a value saved before the phone steps shows as its nearest step`() {
        val rows = buildFadeVolumeRows(BedtimeAudioSettings(), MediaOutput.SPEAKER, pixelMaxStep)
        assertEquals(24, rows.startPercent)
        assertEquals(4, rows.endPercent)
        assertFalse(rows.canLowerEnd, "4% is the lowest ending volume")
        assertTrue(rows.canRaiseEnd)
    }

    @Test fun `an edit on Bluetooth writes only the Bluetooth pair`() {
        val changed = withFadeVolumeStepped(audio, MediaOutput.BLUETOOTH, FadeVolumeKind.START, +1, pixelMaxStep)
        assertEquals(FadeVolumes(startPercent = 44, endPercent = 12), changed.bluetoothVolumes)
        assertEquals(audio.phoneSpeakerVolumes, changed.phoneSpeakerVolumes)
    }

    @Test fun `an edit on the speaker writes only the phone speaker pair`() {
        val changed = withFadeVolumeStepped(audio, MediaOutput.SPEAKER, FadeVolumeKind.END, +1, pixelMaxStep)
        assertEquals(FadeVolumes(startPercent = 24, endPercent = 8), changed.phoneSpeakerVolumes)
        assertEquals(audio.bluetoothVolumes, changed.bluetoothVolumes)
    }
}
