package com.nikita.sleepcycle.night

// File purpose: the one seam that decides whether a band alarm command reaches the real band, so the tick's
// own control flow (NightTickLogging.kt) never branches on debug options itself.

import android.content.Context
import com.nikita.sleepcycle.bridge.sendDismissBandAlarm
import com.nikita.sleepcycle.bridge.sendSetBandAlarm
import java.time.LocalTime

/**
 * Sends [command] to the real band via Gadgetbridge, unless [debugOptions] has band commands set to dry run,
 * in which case nothing is sent - the caller is responsible for logging it as not really sent (see
 * `applyBandAlarmDecision` in NightTickLogging.kt, which always logs every command regardless).
 */
fun sendBandAlarmCommand(context: Context, debugOptions: DebugOptions, deviceMac: String, command: BandAlarmCommand) {
    if (debugOptions.bandCommandMode == BandCommandMode.DRY_RUN) return
    when (command) {
        is BandAlarmCommand.Set -> sendSetBandAlarm(context, deviceMac, LocalTime.of(command.hour, command.minute), command.title)
        is BandAlarmCommand.Dismiss -> sendDismissBandAlarm(context, deviceMac, command.title)
    }
}
