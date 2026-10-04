package com.nikita.sleepcycle.scenario

// File purpose: what the alarm scenario tests share - starting a night the way the Night screen does, reading
// back which phone alarm AlarmManager holds, firing it the way Android does, and delivering a reboot.

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import com.nikita.sleepcycle.alarm.EXTRA_ALARM_IS_OUT_OF_BED_NUDGE
import com.nikita.sleepcycle.alarm.EXTRA_ALARM_IS_TEST
import com.nikita.sleepcycle.alarm.EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI
import com.nikita.sleepcycle.alarm.PhoneAlarmReceiver
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.night.AfterAlarmSettings
import com.nikita.sleepcycle.night.BedtimeAudioSettings
import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.NightService
import com.nikita.sleepcycle.night.listNightLogs
import com.nikita.sleepcycle.night.loadNightState
import com.nikita.sleepcycle.night.startNight
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.robolectric.Shadows.shadowOf
import java.time.Instant

/** Starts a night through the same entry point as the Start night button and waits for its state to land. */
internal fun startNightAndWait(context: Context, settings: NightSettings, startedAt: Instant, debugOptions: DebugOptions = DebugOptions()) = runBlocking {
    startNight(context, settings, startedAt, AfterAlarmSettings(), BedtimeAudioSettings(), debugOptions)
    withTimeout(10_000) { while (loadNightState(context) == null) delay(20) }
}

/** The intent AlarmManager will deliver for a phone alarm slot (the plan's alarm, or the out-of-bed slot), or null when none is armed. */
internal fun armedAlarmIntent(context: Context, outOfBed: Boolean = false): Intent? =
    shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        .mapNotNull { alarm -> alarm.operation?.let { shadowOf(it).savedIntent } }
        .firstOrNull { intent ->
            intent.component?.className == PhoneAlarmReceiver::class.java.name &&
                !intent.getBooleanExtra(EXTRA_ALARM_IS_TEST, false) &&
                intent.getBooleanExtra(EXTRA_ALARM_IS_OUT_OF_BED_NUDGE, false) == outOfBed
        }

/** The (virtual) instant the plan's phone alarm is armed for, or null when none is armed. */
internal fun armedPhoneAlarmAt(context: Context): Instant? =
    armedAlarmIntent(context)?.let { Instant.ofEpochMilli(it.getLongExtra(EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI, -1L)) }

/** Fires the plan's armed phone alarm the way AlarmManager does: its own intent, to PhoneAlarmReceiver. */
internal fun firePhoneAlarm(context: Context, intent: Intent = checkNotNull(armedAlarmIntent(context)) { "no phone alarm armed" }) =
    PhoneAlarmReceiver().onReceive(context, intent)

/** Delivers BOOT_COMPLETED to the manifest's BootReceiver and waits until it has restarted night tracking. */
internal fun rebootPhone(context: Context) = runBlocking {
    val application = context.applicationContext as Application
    shadowOf(application).clearStartedServices()
    context.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED))
    shadowOf(Looper.getMainLooper()).idle()
    withTimeout(10_000) {
        while (shadowOf(application).peekNextStartedService()?.component?.className != NightService::class.java.name) delay(20)
    }
}

/** The current night's log, all of it. */
internal fun nightLogText(context: Context): String = listNightLogs(context).first().readText()
