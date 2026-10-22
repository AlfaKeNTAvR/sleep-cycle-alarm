package com.nikita.sleepcycle.ui.components

// File purpose: owner spec, 2026-10-22 (PER-133) - the live volume line reads the media volume as a percent of
// its range (the same number the simulation block shows) and names the device media is playing on. Robolectric,
// so the real AudioManager calls are exercised without a phone.

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.collect.ImmutableList
import com.nikita.sleepcycle.ui.state.MediaOutput
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaVolumeReadingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val audio = shadowOf(audioManager)

    @Before
    fun setUp() {
        // The Pixel's 25-step media range: step 13 is 52%.
        audio.setStreamMaxVolume(25)
        audio.setStreamVolume(13)
    }

    private fun playOn(type: Int) {
        audio.setAudioDevicesForAttributes(MEDIA_PLAYBACK_ATTRIBUTES, ImmutableList.of(AudioDeviceInfoBuilder.newBuilder().setType(type).build()))
    }

    @Test fun `media on the built-in speaker at step 13 of 25 reads Speaker 52 percent`() {
        playOn(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)

        assertEquals(MediaVolumeReading(MediaOutput.SPEAKER, 52, 25), readMediaVolume(audioManager))
    }

    @Test fun `switching media to Bluetooth and turning it down shows on the next read`() {
        playOn(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        readMediaVolume(audioManager)

        playOn(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        audio.setStreamVolume(10)

        assertEquals(MediaVolumeReading(MediaOutput.BLUETOOTH, 40, 25), readMediaVolume(audioManager))
    }

    @Test fun `wired headphones read Headphones`() {
        playOn(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        audio.setStreamVolume(4)

        assertEquals(MediaVolumeReading(MediaOutput.HEADPHONES, 16, 25), readMediaVolume(audioManager))
    }

    @Test fun `no audio service reads Speaker at 0 percent rather than failing`() {
        assertEquals(MediaVolumeReading(MediaOutput.SPEAKER, 0, 0), readMediaVolume(null))
    }
}
