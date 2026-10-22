package com.nikita.sleepcycle.ui.state

// File purpose: owner spec, 2026-10-22 (PER-133, option C "quiet text line") - Before bed and every live Night
// screen state show the current media output and volume ("Speaker 52%") in one quiet line above the main
// button(s), as a reference for picking the fade's starting volume. Covers the two pure rules behind it: which
// output name a playing device gets, and when the line is shown at all. Goes through buildBeforeBedUiState and
// buildNightUiState, the screens' own seams.

import android.media.AudioDeviceInfo
import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.SleepState
import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.PendingFollowUp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MediaOutputForTest {
    // Owner spec, 2026-10-22: built-in speaker -> Speaker; wired headset/headphones/USB headset -> Headphones;
    // Bluetooth A2DP / BLE headset or speaker -> Bluetooth; anything else -> Speaker.

    @Test fun `the built-in speaker is Speaker`() {
        assertEquals(MediaOutput.SPEAKER, mediaOutputFor(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
    }

    @Test fun `wired headset, wired headphones and a USB headset are all Headphones`() {
        assertEquals(MediaOutput.HEADPHONES, mediaOutputFor(listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET)))
        assertEquals(MediaOutput.HEADPHONES, mediaOutputFor(listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)))
        assertEquals(MediaOutput.HEADPHONES, mediaOutputFor(listOf(AudioDeviceInfo.TYPE_USB_HEADSET)))
    }

    @Test fun `Bluetooth A2DP and a BLE headset or speaker are all Bluetooth`() {
        assertEquals(MediaOutput.BLUETOOTH, mediaOutputFor(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
        assertEquals(MediaOutput.BLUETOOTH, mediaOutputFor(listOf(AudioDeviceInfo.TYPE_BLE_HEADSET)))
        assertEquals(MediaOutput.BLUETOOTH, mediaOutputFor(listOf(AudioDeviceInfo.TYPE_BLE_SPEAKER)))
    }

    @Test fun `any other device, or none at all, is Speaker`() {
        assertEquals(MediaOutput.SPEAKER, mediaOutputFor(listOf(AudioDeviceInfo.TYPE_HDMI)))
        assertEquals(MediaOutput.SPEAKER, mediaOutputFor(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)))
        assertEquals(MediaOutput.SPEAKER, mediaOutputFor(emptyList()))
    }

    @Test fun `with several outputs listed, a headset outranks the built-in speaker and Bluetooth outranks a wired one`() {
        // The pre-Android-13 fallback lists every connected output, not just the one media plays on.
        assertEquals(
            MediaOutput.HEADPHONES,
            mediaOutputFor(listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)),
        )
        assertEquals(
            MediaOutput.BLUETOOTH,
            mediaOutputFor(listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)),
        )
    }
}

class LiveVolumeLineShownTest {
    private val simulated = DebugOptions(simulatedBandData = true)

    @Test fun `the line shows whenever no simulation is on`() {
        assertTrue(isLiveVolumeLineShown(DebugOptions()))
    }

    @Test fun `a simulated night hides it, since the amber SIMULATED block already shows the volume`() {
        // Owner spec, 2026-10-22: the volume never shows twice.
        assertFalse(isLiveVolumeLineShown(simulated))
    }

    @Test fun `Before bed shows the line, and hides it while the simulation block is up`() {
        assertTrue(beforeBed(DebugOptions()).showLiveVolumeLine)
        assertFalse(beforeBed(simulated).showLiveVolumeLine)
    }

    @Test fun `the Night screen shows it while the morning alarm is ahead, with I'm up`() {
        val screen = nightAt("2026-09-17T03:00", followUp = null)

        assertTrue(screen.showImUpButton)
        assertTrue(screen.showLiveVolumeLine)
    }

    @Test fun `the Night screen shows it on the out-of-bed nudge, with Nap and End night`() {
        val screen = nightAt("2026-09-17T07:05", pendingNudge("2026-09-17T07:13"), morningAlarmRang = true)

        assertEquals(20, screen.napButtonMinutes)
        assertTrue(screen.showLiveVolumeLine)
    }

    @Test fun `the Night screen shows it during his own nap, with I'm up`() {
        val screen = nightAt("2026-09-17T07:10", pendingNap("2026-09-17T07:25"), morningAlarmRang = true)

        assertTrue(screen.showImUpButton)
        assertTrue(screen.showLiveVolumeLine)
    }

    @Test fun `the morning report never shows it`() {
        assertFalse(nightAt("2026-09-17T07:30", followUp = null, showingMorningReport = true).showLiveVolumeLine)
    }

    @Test fun `a simulated night hides it on the Night screen too`() {
        assertFalse(nightAt("2026-09-17T03:00", followUp = null, debugOptions = simulated).showLiveVolumeLine)
    }

    private fun beforeBed(debugOptions: DebugOptions): BeforeBedUiState = buildBeforeBedUiState(
        appSettings = testAppSettings(),
        now = instant("2026-09-16T23:00"),
        zone = testZone,
        gadgetbridgeInstalled = true,
        permissionStatus = testPermissionStatus(),
        nightActive = false,
        debugOptions = debugOptions,
        connectionTest = ConnectionTestState.Idle,
    )

    private fun nightAt(
        now: String,
        followUp: PendingFollowUp?,
        morningAlarmRang: Boolean = false,
        showingMorningReport: Boolean = false,
        debugOptions: DebugOptions = DebugOptions(),
    ): NightUiState {
        val night = testNightState(
            startedAt = "2026-09-16T23:00",
            lastPlan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T07:00", cycles = 5),
            morningAlarmAt = "2026-09-17T07:00",
        ).let { if (morningAlarmRang) it.copy(phoneAlarmFiredFor = instant("2026-09-17T07:00")) else it }
        return checkNotNull(
            buildNightUiState(
                nightState = night,
                engineView = testEngineView(SleepState.AWAKE),
                now = instant(now),
                zone = testZone,
                showingMorningReport = showingMorningReport,
                morningReportEndedAt = null,
                confirmingEndNight = false,
                pendingFollowUp = followUp,
                debugOptions = debugOptions,
            )
        )
    }
}
