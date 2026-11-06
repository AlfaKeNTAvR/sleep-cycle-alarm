package com.nikita.sleepcycle.bridge

// File purpose: Gadgetbridge Intent API constants - package name, broadcast actions, extras. No logic here.

/** Package name of the Gadgetbridge app we talk to over explicit broadcasts. */
const val GADGETBRIDGE_PACKAGE_NAME = "nodomain.freeyourgadget.gadgetbridge"

const val ACTION_ACTIVITY_SYNC = "$GADGETBRIDGE_PACKAGE_NAME.command.ACTIVITY_SYNC"
const val ACTION_TRIGGER_DATABASE_EXPORT = "$GADGETBRIDGE_PACKAGE_NAME.command.TRIGGER_DATABASE_EXPORT"

const val ACTION_ACTIVITY_SYNC_FINISH = "$GADGETBRIDGE_PACKAGE_NAME.action.ACTIVITY_SYNC_FINISH"
const val ACTION_DATABASE_EXPORT_SUCCESS = "$GADGETBRIDGE_PACKAGE_NAME.action.DATABASE_EXPORT_SUCCESS"
const val ACTION_DATABASE_EXPORT_FAIL = "$GADGETBRIDGE_PACKAGE_NAME.action.DATABASE_EXPORT_FAIL"

// P5 (PER-130 part 2): the Intent API's Bluetooth commands. Gadgetbridge handles them with a receiver it registers
// at runtime, and only while its Settings > Developer options > Intent API > Bluetooth toggle is on (checked in
// the 0.95.1 source, and live on the owner's phone over adb on 2026-11-03).
const val ACTION_BLUETOOTH_CONNECT = "$GADGETBRIDGE_PACKAGE_NAME.BLUETOOTH_CONNECT"
const val ACTION_BLUETOOTH_DISCONNECT = "$GADGETBRIDGE_PACKAGE_NAME.BLUETOOTH_DISCONNECT"

/**
 * P5: Gadgetbridge's reply to [ACTION_BLUETOOTH_CONNECT] while the band is already connected, and its broadcast
 * once a connect has finished initializing the band. Sent only while the same toggle is on; there is no
 * "disconnected" counterpart and no way to ask for the connection state.
 */
const val ACTION_BLUETOOTH_CONNECTED = "$GADGETBRIDGE_PACKAGE_NAME.BLUETOOTH_CONNECTED"

/** P5: the band's MAC address (upper case) on the three Bluetooth actions above. Never logged or shown. */
const val EXTRA_DEVICE_ADDRESS = "EXTRA_DEVICE_ADDRESS"

// P7: the Intent API's device setting command, as Gadgetbridge 0.95.1 handles it (tried on the owner's phone,
// 2026-11-06). Ignored unless the band's own "Allow 3rd party apps to change settings" toggle is on (band
// settings > Developer settings > Intent API). There is no reply and no way to read a setting back.
const val ACTION_SET_DEVICE_SETTING = "$GADGETBRIDGE_PACKAGE_NAME.action.SET_DEVICE_SETTING"

/** P7: the band's MAC address (upper case, a String) on [ACTION_SET_DEVICE_SETTING]. Never logged or shown. */
const val EXTRA_SETTING_DEVICE = "device"

/** P7: the setting's preference key (a String) on [ACTION_SET_DEVICE_SETTING]. */
const val EXTRA_SETTING_KEY = "key"

/** P7: the setting's new value on [ACTION_SET_DEVICE_SETTING], typed like the preference: a String breaks a boolean one. */
const val EXTRA_SETTING_VALUE = "value"

/** P7: Gadgetbridge's preference key for "Enable automatic heart rate measuring", a boolean. */
const val SETTING_HEARTRATE_AUTOMATIC_ENABLE = "heartrate_automatic_enable"
