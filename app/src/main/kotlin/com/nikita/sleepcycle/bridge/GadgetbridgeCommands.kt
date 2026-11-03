package com.nikita.sleepcycle.bridge

// File purpose: sends explicit broadcasts to Gadgetbridge's Intent API. No engine/alarm-math logic here.

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** True when Gadgetbridge is installed, so the setup checklist can show a clear pass/fail item. */
fun isGadgetbridgeInstalled(context: Context): Boolean =
    try {
        context.packageManager.getPackageInfo(GADGETBRIDGE_PACKAGE_NAME, 0)
        true
    } catch (error: PackageManager.NameNotFoundException) {
        false
    }

/**
 * Owner request, 2026-10-05: opens Gadgetbridge's own screen, to reconnect the band there. Returns false when it
 * cannot be opened (not installed), so the caller can say nothing happened.
 */
fun openGadgetbridge(context: Context): Boolean {
    val launch = context.packageManager.getLaunchIntentForPackage(GADGETBRIDGE_PACKAGE_NAME) ?: return false
    context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return true
}

private fun gadgetbridgeIntent(action: String): Intent =
    Intent(action).setPackage(GADGETBRIDGE_PACKAGE_NAME)

/** Asks Gadgetbridge to sync activity data from the band right now. */
fun sendActivitySync(context: Context) {
    context.sendBroadcast(gadgetbridgeIntent(ACTION_ACTIVITY_SYNC))
}

/** Asks Gadgetbridge to export its database to the location the user configured in its settings. */
fun sendDatabaseExport(context: Context) {
    context.sendBroadcast(gadgetbridgeIntent(ACTION_TRIGGER_DATABASE_EXPORT))
}

/** P5: asks Gadgetbridge to disconnect the band at [deviceMac]. Ignored unless its Intent API Bluetooth toggle is on. */
fun sendBluetoothDisconnect(context: Context, deviceMac: String) {
    context.sendBroadcast(gadgetbridgeIntent(ACTION_BLUETOOTH_DISCONNECT).putExtra(EXTRA_DEVICE_ADDRESS, deviceMac))
}

/** P5: asks Gadgetbridge to connect the band at [deviceMac]; while it is already connected, Gadgetbridge only replies [ACTION_BLUETOOTH_CONNECTED]. */
fun sendBluetoothConnect(context: Context, deviceMac: String) {
    context.sendBroadcast(gadgetbridgeIntent(ACTION_BLUETOOTH_CONNECT).putExtra(EXTRA_DEVICE_ADDRESS, deviceMac))
}
