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
