package com.nikita.sleepcycle.bridge

// File purpose: Gadgetbridge Intent API constants - package name, broadcast actions, extras. No logic here.

/** Package name of the Gadgetbridge app we talk to over explicit broadcasts. */
const val GADGETBRIDGE_PACKAGE_NAME = "nodomain.freeyourgadget.gadgetbridge"

const val ACTION_ACTIVITY_SYNC = "$GADGETBRIDGE_PACKAGE_NAME.command.ACTIVITY_SYNC"
const val ACTION_SET_ALARM = "$GADGETBRIDGE_PACKAGE_NAME.command.SET_ALARM"
const val ACTION_DISMISS_ALARM = "$GADGETBRIDGE_PACKAGE_NAME.command.DISMISS_ALARM"
const val ACTION_TRIGGER_DATABASE_EXPORT = "$GADGETBRIDGE_PACKAGE_NAME.command.TRIGGER_DATABASE_EXPORT"

const val ACTION_ACTIVITY_SYNC_FINISH = "$GADGETBRIDGE_PACKAGE_NAME.action.ACTIVITY_SYNC_FINISH"
const val ACTION_DATABASE_EXPORT_SUCCESS = "$GADGETBRIDGE_PACKAGE_NAME.action.DATABASE_EXPORT_SUCCESS"
const val ACTION_DATABASE_EXPORT_FAIL = "$GADGETBRIDGE_PACKAGE_NAME.action.DATABASE_EXPORT_FAIL"

const val EXTRA_DEVICE = "device"
const val EXTRA_HOUR = "hour"
const val EXTRA_MINUTES = "minutes"
const val EXTRA_TITLE = "title"
const val EXTRA_MODE = "mode"

/** Value for [EXTRA_MODE] that dismisses a band alarm by its title rather than by time or "all". */
const val DISMISS_MODE_TITLE = "title"
