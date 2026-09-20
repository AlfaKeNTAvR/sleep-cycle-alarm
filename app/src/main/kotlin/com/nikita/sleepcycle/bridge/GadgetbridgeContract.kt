package com.nikita.sleepcycle.bridge

// File purpose: Gadgetbridge Intent API constants - package name, broadcast actions, extras. No logic here.

/** Package name of the Gadgetbridge app we talk to over explicit broadcasts. */
const val GADGETBRIDGE_PACKAGE_NAME = "nodomain.freeyourgadget.gadgetbridge"

const val ACTION_ACTIVITY_SYNC = "$GADGETBRIDGE_PACKAGE_NAME.command.ACTIVITY_SYNC"
const val ACTION_TRIGGER_DATABASE_EXPORT = "$GADGETBRIDGE_PACKAGE_NAME.command.TRIGGER_DATABASE_EXPORT"

const val ACTION_ACTIVITY_SYNC_FINISH = "$GADGETBRIDGE_PACKAGE_NAME.action.ACTIVITY_SYNC_FINISH"
const val ACTION_DATABASE_EXPORT_SUCCESS = "$GADGETBRIDGE_PACKAGE_NAME.action.DATABASE_EXPORT_SUCCESS"
const val ACTION_DATABASE_EXPORT_FAIL = "$GADGETBRIDGE_PACKAGE_NAME.action.DATABASE_EXPORT_FAIL"
