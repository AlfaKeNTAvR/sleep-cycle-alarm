package com.nikita.sleepcycle.night

// File purpose: re-arms tracking after a reboot, and reacts to the clock or timezone changing mid-night -
// the band alarm's hour/minute is local, so a zone change needs a fresh tick to re-derive it correctly.

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nikita.sleepcycle.alarm.schedulePhoneAlarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Runs on BOOT_COMPLETED, TIME_SET and TIMEZONE_CHANGED. State is loaded off the main thread via [goAsync]. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                when (action) {
                    Intent.ACTION_BOOT_COMPLETED -> handleBoot(context)
                    Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> handleClockChange(context, action)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleBoot(context: Context) {
        val state = withContext(Dispatchers.IO) { loadNightState(context) } ?: return
        val plan = state.lastPlan
        if (plan == null) {
            // Reboot happened before the first tick ever ran: nothing to re-arm yet, just resume ticking.
            startNightServiceForTick(context)
            return
        }
        val now = Instant.now()
        val stillRelevant = (plan.bandAlarm?.isAfter(now) == true) || (plan.phoneAlarm?.isAfter(now) == true)
        if (!stillRelevant) return
        // D2: the same guard armPhoneAlarmIfNeeded uses every tick - a reboot must never re-arm an already-
        // fired or now-past phone alarm just because the persisted plan still names one.
        if (shouldArmPhoneAlarm(plan.phoneAlarm, now, state.phoneAlarmFiredFor)) {
            schedulePhoneAlarm(context, requireNotNull(plan.phoneAlarm))
        }
        startNightServiceForTick(context)
    }

    private suspend fun handleClockChange(context: Context, action: String?) {
        val state = withContext(Dispatchers.IO) { loadNightState(context) } ?: return
        appendNightLog(context, state.startedAt, NightLogEvent(Instant.now(), "clock_changed", mapOf("action" to (action ?: "unknown"))), state.debugOptions.isAnyEnabled)
        requestImmediateTick(context)
    }
}
