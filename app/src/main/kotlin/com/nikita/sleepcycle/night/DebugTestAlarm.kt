package com.nikita.sleepcycle.night

// File purpose: the Debug screen's "Ring phone alarm" button - arms a phone alarm, entirely separate from the
// real night alarm (D1: see scheduleTestPhoneAlarm/EXTRA_ALARM_IS_TEST in PhoneAlarmScheduler.kt), so the
// alarm screen, sound and Stop button can be checked in daylight without ever touching a real night's alarm or
// state. Logged to setup.jsonl, not a night log: this is not a night. Refuses to schedule while a night is
// active - the Debug screen also disables the button for that case (DebugScreenController.kt) - so even a
// stale UI state can never let this reach schedulePhoneAlarm's request code.
//
// T8: [scheduleDebugTestAlarm]'s own [now] must be `Instant.now()` on the real clock, never `nowInstant()` -
// this is a daylight check of the ring path, not part of a simulated night, so it must ring at the real now
// regardless of any active clock warp. Owner request, 2026-10-02: it rings straight away (it used to be 5 real
// seconds out). It is still armed through AlarmManager, which fires an alarm set for now at once, so the press
// still exercises the whole real path - AlarmManager, the receiver, the full-screen ring.

import android.content.Context
import com.nikita.sleepcycle.alarm.scheduleTestPhoneAlarm
import java.time.Instant

/** True when the test alarm may be scheduled: never while a night is active (D1). Pure so it is JVM-testable without Android. */
fun isDebugTestAlarmAllowed(nightActive: Boolean): Boolean = !nightActive

/**
 * Arms the Debug screen's test alarm for [now], so it rings at once, through [scheduleTestPhoneAlarm] -
 * its own request code and intent extra, never the real night alarm's (D1). Refuses (and logs why) while a
 * night is active, as a second guard behind the disabled button. Returns whether arming succeeded.
 */
fun scheduleDebugTestAlarm(context: Context, now: Instant): Boolean {
    if (!isDebugTestAlarmAllowed(nightActive = loadNightState(context) != null)) {
        appendSetupLog(context, NightLogEvent(now, "debug_test_alarm", mapOf("refused" to "true", "cause" to "a night is active")))
        return false
    }
    val armed = scheduleTestPhoneAlarm(context, now)
    appendSetupLog(context, NightLogEvent(now, "debug_test_alarm", mapOf("scheduledFor" to now.toString(), "armed" to armed.toString())))
    return armed
}
