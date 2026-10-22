package com.nikita.sleepcycle.ui.state

// File purpose: owner spec, 2026-10-22 (PER-133, option C "quiet text line") - the two pure rules behind the
// live volume line on Before bed and the Night screen: which output name the device playing media gets, and
// whether the line is shown at all. The owner asked for it on 2026-10-21 as a reference for picking the fade's
// starting volume ("so I know as a reference which sound I like"), and speaker and headphones differ, so the
// line names the output alongside the percent. The reading itself is Android-only and lives in
// ui/components/LiveVolumeLine.kt.

import android.media.AudioDeviceInfo
import com.nikita.sleepcycle.night.DebugOptions

/** The output name the live volume line shows: "Speaker", "Headphones" or "Bluetooth". */
enum class MediaOutput { SPEAKER, HEADPHONES, BLUETOOTH }

private val BLUETOOTH_DEVICE_TYPES = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
)

private val HEADPHONE_DEVICE_TYPES = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET,
)

/**
 * The output name for the media device types in [deviceTypes] (AudioDeviceInfo.TYPE_*). Owner spec,
 * 2026-10-22: Bluetooth A2DP or a BLE headset or speaker is Bluetooth, a wired or USB headset is Headphones,
 * anything else (the built-in speaker included) is Speaker. Android 13+ names the one device media plays on;
 * before that the caller can only list every connected output, so a headset outranks the speaker and Bluetooth
 * outranks a wire, the order Android itself routes media in.
 */
fun mediaOutputFor(deviceTypes: List<Int>): MediaOutput = when {
    deviceTypes.any { it in BLUETOOTH_DEVICE_TYPES } -> MediaOutput.BLUETOOTH
    deviceTypes.any { it in HEADPHONE_DEVICE_TYPES } -> MediaOutput.HEADPHONES
    else -> MediaOutput.SPEAKER
}

/**
 * Whether the live volume line is shown, given the current effective [debugOptions]. Hidden on a simulated night
 * (owner spec, 2026-10-22): the amber SIMULATED block already carries the volume, and it must never show twice.
 * Which screens and states carry the line at all is up to each screen's builder.
 */
fun isLiveVolumeLineShown(debugOptions: DebugOptions): Boolean = !debugOptions.isAnyEnabled
