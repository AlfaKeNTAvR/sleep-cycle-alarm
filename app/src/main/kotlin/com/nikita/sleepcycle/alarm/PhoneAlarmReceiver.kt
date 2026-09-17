package com.nikita.sleepcycle.alarm

// File purpose: fires when the phone alarm rings; holds the ringing wake lock and hands off to
// AlarmRingService which owns sound, vibration and the full-screen intent. If the foreground service cannot
// be started at all (background-start restrictions, OS refusal), falls back to posting the alarm
// notification directly and launching AlarmActivity, so the alarm is never completely silent.
//
// D1: a firing intent carries EXTRA_ALARM_IS_TEST and EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI (see
// PhoneAlarmScheduler.kt). A test alarm (the Debug screen's "Ring phone alarm in 1 min") never loads, logs to,
// or writes night state - it only rings. A real alarm marks fired the instant ITS OWN intent was scheduled
// for, never `state.lastPlan?.phoneAlarm`, which could already point at a different, newer plan by the time
// this receiver runs.

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nikita.sleepcycle.night.NightLogEvent
import com.nikita.sleepcycle.night.NightState
import com.nikita.sleepcycle.night.appendNightLog
import com.nikita.sleepcycle.night.appendSetupLog
import com.nikita.sleepcycle.night.loadNightState
import com.nikita.sleepcycle.night.savePhoneAlarmFiredFor
import java.time.Instant

class PhoneAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val now = Instant.now()
        val isTest = intent.getBooleanExtra(EXTRA_ALARM_IS_TEST, false)
        acquireAlarmWakeLock(context, AUTO_STOP_AFTER)
        val state = if (isTest) null else recordRealAlarmFired(context, intent, now)
        if (isTest) appendSetupLog(context, NightLogEvent(now, "debug_test_alarm_fired", emptyMap()))
        try {
            context.startForegroundService(Intent(context, AlarmRingService::class.java))
        } catch (error: Exception) {
            handleServiceStartFailure(context, state, error)
        }
    }

    /** Logs the firing and marks fired the instant THIS intent was scheduled for (D1), never a night-state snapshot. Returns the loaded state (if any) for the caller's own use. */
    private fun recordRealAlarmFired(context: Context, intent: Intent, now: Instant): NightState? {
        val state = loadNightState(context) ?: return null
        appendNightLog(context, state.startedAt, NightLogEvent(now, "phone_alarm_fired", emptyMap()), state.debugOptions.isAnyEnabled)
        val scheduledForMillis = intent.getLongExtra(EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI, -1L)
        if (scheduledForMillis < 0) return state
        markPhoneAlarmFired(context, state, Instant.ofEpochMilli(scheduledForMillis))
        return state
    }

    /**
     * D3: records which instant just fired, so armPhoneAlarmIfNeeded never re-arms it (Android fires a past
     * exact alarm immediately) - through [savePhoneAlarmFiredFor]'s own tiny file, never a read-modify-write
     * of the whole [NightState], which used to race a concurrent tick's own save under the night transaction
     * lock (whichever wrote last would silently discard the other's changes).
     */
    private fun markPhoneAlarmFired(context: Context, state: NightState, firedFor: Instant) {
        if (!savePhoneAlarmFiredFor(context, firedFor)) {
            appendNightLog(
                context, state.startedAt,
                NightLogEvent(Instant.now(), "error", mapOf("step" to "save_night_state", "cause" to "failed to persist phoneAlarmFiredFor after the alarm fired")),
                state.debugOptions.isAnyEnabled
            )
        }
    }

    /** startForegroundService can be refused outright (background-start restrictions); the alarm must still be reachable. */
    private fun handleServiceStartFailure(context: Context, state: NightState?, error: Exception) {
        if (state != null) {
            appendNightLog(
                context, state.startedAt,
                NightLogEvent(Instant.now(), "error", mapOf("step" to "alarm_service_start", "cause" to (error.message ?: error.toString()))),
                state.debugOptions.isAnyEnabled
            )
        }
        releaseAlarmWakeLock()
        postAlarmNotificationDirectly(context)
        context.startActivity(Intent(context, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
