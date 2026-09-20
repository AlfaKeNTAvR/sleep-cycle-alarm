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
