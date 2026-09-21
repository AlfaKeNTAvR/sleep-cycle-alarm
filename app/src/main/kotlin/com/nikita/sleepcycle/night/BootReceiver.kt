package com.nikita.sleepcycle.night

// File purpose: re-arms tracking after a reboot or an app update, and reacts to the clock or timezone
// changing mid-night - the phone alarm's hour/minute is local, so a zone change needs a fresh tick to
// re-derive it correctly.

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nikita.sleepcycle.alarm.alarmLabelFor
import com.nikita.sleepcycle.alarm.schedulePhoneAlarm
import com.nikita.sleepcycle.alarm.scheduleOutOfBedAlarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Runs on BOOT_COMPLETED, MY_PACKAGE_REPLACED (F13), TIME_SET and TIMEZONE_CHANGED. State is loaded off the
 * main thread via [goAsync]. F13: Android cancels every alarm an app has set when its package is replaced -
 * there is no exemption for an alarm-clock app - so an app update mid-night silently drops the wake alarm,
 * the nudge and the tick alarm with nothing to re-arm them unless this receiver also handles that action.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                when (action) {
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> handleBoot(context)
                    Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> handleClockChange(context, action)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * F3: whenever a night state exists at all, tracking must resume - the ONLY thing that stays conditional
     * is the phone alarm's own (re)arming. The old code returned early, orphaning the whole night, in two real
     * cases: the phone was off past the alarm time (AlarmManager alarms do not survive a reboot, so the alarm
     * never even had a chance to fire - the very first tick pulling a stale wakeAt forward to now + minAlarmLead,
     * D8, is exactly what should happen instead), and a reboot moments after the owner tapped Stop with the
     * night meant to continue (D6) - nap detection, the nudge, and ever reaching FINISHED all died with it.
     * [shouldArmPhoneAlarm] already refuses a null, past, or already-fired wakeAt (an old-build state file's
     * plan with `wakeAt = null` included), so it is safe to call unconditionally here too. [shouldResumeNightServiceOnBoot]
     * is [state] != null spelled out as its own decision (mirroring shouldArmPhoneAlarm), so a plain JVM test
     * can pin that ticking resumes regardless of what `plan.wakeAt` says, without needing this function's own
     * Context/AlarmManager plumbing.
     *
     * G4/H5: a pending out-of-bed nudge (armed but not yet fired when the reboot or app update happened) is
     * restored the same way, from its own persisted instant - see OutOfBedNudgeStore.kt. H5: restored
     * INDEPENDENTLY of whether a night state exists, unlike everything else in this function - G1's
     * FINISHED-bookkeeping deliberately clears the night state while leaving an already-armed nudge alone (it
     * must still fire), so a reboot in that window used to hit the early return below and never re-arm it.
     * The nudge's own H7.3 pre-check has no such recovery (see OutOfBedPreNudgeCheck.kt) - losing it is fine,
     * since its own fail-open rule already treats "no check happened" the same as "the check found nothing".
     */
    private suspend fun handleBoot(context: Context) {
        val state = withContext(Dispatchers.IO) { loadNightState(context) }
        restorePendingOutOfBedNudge(context)
        if (!shouldResumeNightServiceOnBoot(state)) return
        val nonNullState = requireNotNull(state)
        val plan = nonNullState.lastPlan
        // T4: virtual - compared against plan.wakeAt, which is night state.
        val now = nowInstant()
        if (plan != null && shouldArmPhoneAlarm(plan.wakeAt, now, nonNullState.phoneAlarmFiredFor)) {
            val wakeAt = requireNotNull(plan.wakeAt)
            schedulePhoneAlarm(context, wakeAt, alarmLabelFor(plan.mode, wakeAt, nonNullState.morningAlarmAt))
        }
        startServiceForTickSafely(context, nonNullState)
    }

    /** H5: re-arms a pending out-of-bed nudge that survived a reboot or app update, whether or not a night state also survived - see [handleBoot]'s own doc. */
    private fun restorePendingOutOfBedNudge(context: Context) {
        // T4: virtual - compared against pendingNudgeAt, which was persisted as a virtual instant (T5).
        val now = nowInstant()
        val pendingNudgeAt = readOutOfBedNudgePendingAt(context)
        if (pendingNudgeAt != null && pendingNudgeAt.isAfter(now)) {
            scheduleOutOfBedAlarm(context, pendingNudgeAt)
        }
    }

    /** G7: startForegroundService can throw ForegroundServiceStartNotAllowedException. BOOT_COMPLETED and MY_PACKAGE_REPLACED are both on Android's exemption list so this should not fire, but if it ever does the night must not die with nothing written anywhere the owner would look - PhoneAlarmReceiver's own equivalent guard around AlarmRingService is this function's model. An exception here would otherwise propagate out of receiverScope.launch's coroutine with no handler, reaching the thread's default handler rather than the night log. */
    private fun startServiceForTickSafely(context: Context, state: NightState) {
        try {
            startNightServiceForTick(context)
        } catch (error: Exception) {
            appendNightLog(
                context, state.startedAt,
                NightLogEvent(nowInstant(), "error", mapOf("step" to "boot_service_start", "cause" to (error.message ?: error.toString()))),
                state.debugOptions.isAnyEnabled
            )
        }
    }

    private suspend fun handleClockChange(context: Context, action: String?) {
        val state = withContext(Dispatchers.IO) { loadNightState(context) } ?: return
        appendNightLog(context, state.startedAt, NightLogEvent(nowInstant(), "clock_changed", mapOf("action" to (action ?: "unknown"))), state.debugOptions.isAnyEnabled)
        requestImmediateTick(context)
    }
}

/**
 * F3: whenever a night state exists at all, tracking must resume - true whenever [state] is non-null,
 * regardless of what its plan's own `wakeAt` says (already in the past, or null on an old-build state file
 * decoded before D1 existed). Extracted as its own decision, mirroring [shouldArmPhoneAlarm], so the two
 * reboot-recovery cases F3 fixed can be pinned by a plain JVM test without needing [handleBoot]'s own
 * Context/AlarmManager plumbing.
 */
internal fun shouldResumeNightServiceOnBoot(state: NightState?): Boolean = state != null
