package com.nikita.sleepcycle.night

// File purpose: G4 - the out-of-bed nudge's own pending fire instant (D4), persisted separately from
// night_state.json and from PhoneAlarmFiredStore.kt's three "already fired" facts, because this one is
// forward-looking (an alarm not yet fired) rather than a record of one that already has. Without it, a
// reboot or app update between the nudge being armed and it actually firing drops it silently - AlarmManager
// alarms do not survive either event (F13), and nothing else remembers when it was due.
//
// Deliberately NOT part of clearAlarmFiredStores/clearNightState's own bundle: it is cleared only when the
// nudge itself fires (self-consuming, PhoneAlarmReceiver.recordRealAlarmFired) or when the owner ends the
// night (NightController.endNight). G1's FINISHED-bookkeeping path leaves an already-armed nudge alone on
// purpose (it must still fire), so it must not erase the one record that could restore that same nudge
// across a reboot before it does.

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant

private const val OUT_OF_BED_NUDGE_PENDING_FILE_NAME = "out_of_bed_nudge_pending.txt"
private const val LOG_TAG = "OutOfBedNudgeStore"

/** Persists [at] as the out-of-bed nudge's own pending fire instant, the moment it is armed (PhoneAlarmReceiver.armOutOfBedNudge). Returns whether the write succeeded. */
fun saveOutOfBedNudgePendingAt(context: Context, at: Instant): Boolean =
    try {
        outOfBedNudgePendingFile(context).writeText(at.toString())
        true
    } catch (error: Exception) {
        Log.e(LOG_TAG, "failed to save the pending out-of-bed nudge instant", error)
        false
    }

/** The nudge's own pending fire instant, or null if none is armed, or the file is absent/unparsable (never throws). */
fun readOutOfBedNudgePendingAt(context: Context): Instant? {
    val file = outOfBedNudgePendingFile(context)
    if (!file.exists()) return null
    return try {
        Instant.parse(file.readText().trim())
    } catch (error: Exception) {
        Log.e(LOG_TAG, "failed to read the pending out-of-bed nudge instant, treating as absent", error)
        null
    }
}

/** Clears the pending nudge instant: called when the nudge itself fires (self-consuming) and when the owner ends the night - see this file's own header for why FINISHED-bookkeeping (G1) must NOT call this. */
fun clearOutOfBedNudgePendingAt(context: Context) {
    outOfBedNudgePendingFile(context).delete()
}

private fun outOfBedNudgePendingFile(context: Context): File = File(context.filesDir, OUT_OF_BED_NUDGE_PENDING_FILE_NAME)
