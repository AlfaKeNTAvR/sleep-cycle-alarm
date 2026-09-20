package com.nikita.sleepcycle.alarm

// File purpose: arms and cancels the phone's own wake-up alarm via AlarmManager's alarm-clock API. The Debug
// screen's "Ring phone alarm in 1 min" test alarm (D1) goes through [scheduleTestPhoneAlarm], a completely
// separate request code from the real night alarm, so the two can never collide or replace one another. D4's
// out-of-bed nudge is a third, equally separate request code - see [scheduleOutOfBedAlarm]. Every broadcast
// intent carries the instant IT was scheduled for and whether it is a test or the nudge, so PhoneAlarmReceiver
// always marks the instant the firing intent actually asked for as fired - never "whatever the plan says now".

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.getSystemService
import java.time.Instant

private const val LOG_TAG = "PhoneAlarmScheduler"
private const val PHONE_ALARM_REQUEST_CODE = 2001
private const val PHONE_ALARM_TEST_REQUEST_CODE = 2002
private const val PHONE_ALARM_OUT_OF_BED_REQUEST_CODE = 2003

/** Carries the instant the firing broadcast was scheduled for, so the receiver never has to guess it from night state. */
const val EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI = "scheduledForEpochMilli"

/** True when this broadcast is the Debug screen's daylight test alarm, never the real night alarm (D1). */
const val EXTRA_ALARM_IS_TEST = "isTestAlarm"

/** D4: true when this broadcast is the out-of-bed nudge, never the wake/nap alarm it was armed from. */
const val EXTRA_ALARM_IS_OUT_OF_BED_NUDGE = "isOutOfBedNudge"

/**
 * Arms the phone's wake-up alarm for [at] using the alarm-clock API, which Android exempts from Doze and
 * shows in the status bar. Returns true on success. The `USE_EXACT_ALARM` permission this app declares is
 * meant for alarm-clock apps and is granted automatically, but the user can still revoke it from Settings,
 * so the call is guarded rather than assumed to always succeed (lint's MissingPermission check does not
 * know about `USE_EXACT_ALARM`, hence the suppression on the shared [scheduleAlarmClockAlarm] below).
 */
fun schedulePhoneAlarm(context: Context, at: Instant): Boolean =
    scheduleAlarmClockAlarm(context, at, PHONE_ALARM_REQUEST_CODE, isTest = false, isOutOfBed = false)

/**
 * D4: arms the out-of-bed nudge for [at] (`firedAt + EngineConfig.outOfBedDelay`), called by PhoneAlarmReceiver
 * the moment the real wake/nap alarm fires. Its own request code, entirely separate PendingIntent identity
 * from [schedulePhoneAlarm] and [scheduleTestPhoneAlarm], so arming it can never replace either one.
 */
fun scheduleOutOfBedAlarm(context: Context, at: Instant): Boolean =
    scheduleAlarmClockAlarm(context, at, PHONE_ALARM_OUT_OF_BED_REQUEST_CODE, isTest = false, isOutOfBed = true)

/**
 * Arms the Debug screen's daylight test alarm for [at] (D1). Uses its own request code, entirely separate
 * PendingIntent identity from [schedulePhoneAlarm], so it can never replace the real night alarm - and marks
 * its broadcast intent as a test so [PhoneAlarmReceiver] never touches night state for it.
 */
fun scheduleTestPhoneAlarm(context: Context, at: Instant): Boolean =
    scheduleAlarmClockAlarm(context, at, PHONE_ALARM_TEST_REQUEST_CODE, isTest = true, isOutOfBed = false)

@SuppressLint("MissingPermission")
private fun scheduleAlarmClockAlarm(context: Context, at: Instant, requestCode: Int, isTest: Boolean, isOutOfBed: Boolean): Boolean {
    val alarmManager = context.getSystemService<AlarmManager>() ?: return false
    val showIntent = PendingIntent.getActivity(
        context, requestCode, Intent(context, AlarmActivity::class.java).putExtra(EXTRA_ALARM_IS_OUT_OF_BED_NUDGE, isOutOfBed),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val info = AlarmManager.AlarmClockInfo(at.toEpochMilli(), showIntent)
    return try {
        alarmManager.setAlarmClock(info, phoneAlarmPendingIntent(context, requestCode, at, isTest, isOutOfBed))
        true
    } catch (error: SecurityException) {
        Log.e(LOG_TAG, "cannot set the phone alarm: exact alarm permission was likely revoked", error)
        false
    }
}

/** Cancels the phone's real wake-up alarm, if one is armed. Never touches the test alarm's or the out-of-bed nudge's separate request codes. */
fun cancelPhoneAlarm(context: Context) {
    val alarmManager = context.getSystemService<AlarmManager>() ?: return
    alarmManager.cancel(phoneAlarmPendingIntent(context, PHONE_ALARM_REQUEST_CODE, at = Instant.EPOCH, isTest = false, isOutOfBed = false))
}

/** D4: cancels the out-of-bed nudge, if one is armed - called only when the night ends (NightController.endNight), never on its own. */
fun cancelOutOfBedAlarm(context: Context) {
    val alarmManager = context.getSystemService<AlarmManager>() ?: return
    alarmManager.cancel(phoneAlarmPendingIntent(context, PHONE_ALARM_OUT_OF_BED_REQUEST_CODE, at = Instant.EPOCH, isTest = false, isOutOfBed = true))
}

/** [at], [isTest] and [isOutOfBed] only matter for what the firing intent will carry - PendingIntent identity/matching is by [requestCode] and the intent's action/component alone, never its extras. */
private fun phoneAlarmPendingIntent(context: Context, requestCode: Int, at: Instant, isTest: Boolean, isOutOfBed: Boolean): PendingIntent {
    val intent = Intent(context, PhoneAlarmReceiver::class.java)
        .putExtra(EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI, at.toEpochMilli())
        .putExtra(EXTRA_ALARM_IS_TEST, isTest)
        .putExtra(EXTRA_ALARM_IS_OUT_OF_BED_NUDGE, isOutOfBed)
    return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
