package com.nikita.sleepcycle.night

// File purpose: the Debug screen's "Ring phone alarm in 1 min" button - arms a phone alarm, entirely separate
// from the real night alarm (D1: see scheduleTestPhoneAlarm/EXTRA_ALARM_IS_TEST in PhoneAlarmScheduler.kt), so
// the alarm screen, sound and Stop button can be checked in daylight without ever touching a real night's
// alarm or state. Logged to setup.jsonl, not a night log: this is not a night. Refuses to schedule while a
// night is active - the Debug screen also disables the button for that case (DebugScreenController.kt) - so
// even a stale UI state can never let this reach schedulePhoneAlarm's request code.

import android.content.Context
import com.nikita.sleepcycle.alarm.scheduleTestPhoneAlarm
import java.time.Duration
import java.time.Instant

/** How far ahead the Debug screen's phone-alarm test button schedules the alarm. */
val DEBUG_TEST_ALARM_LEAD: Duration = Duration.ofMinutes(1)

/** True when the test alarm may be scheduled: never while a night is active (D1). Pure so it is JVM-testable without Android. */
fun isDebugTestAlarmAllowed(nightActive: Boolean): Boolean = !nightActive

/**
 * Arms the Debug screen's test alarm [DEBUG_TEST_ALARM_LEAD] ahead of [now], through [scheduleTestPhoneAlarm] -
 * its own request code and intent extra, never the real night alarm's (D1). Refuses (and logs why) while a
 * night is active, as a second guard behind the disabled button. Returns whether arming succeeded.
 */
fun scheduleDebugTestAlarm(context: Context, now: Instant): Boolean {
    if (!isDebugTestAlarmAllowed(nightActive = loadNightState(context) != null)) {
        appendSetupLog(context, NightLogEvent(now, "debug_test_alarm", mapOf("refused" to "true", "cause" to "a night is active")))
        return false
    }
    val at = now.plus(DEBUG_TEST_ALARM_LEAD)
    val armed = scheduleTestPhoneAlarm(context, at)
    appendSetupLog(context, NightLogEvent(now, "debug_test_alarm", mapOf("scheduledFor" to at.toString(), "armed" to armed.toString())))
    return armed
}
