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

/**
 * Persists [at] as the out-of-bed nudge's own pending fire instant, the moment it is armed
 * (PhoneAlarmReceiver.armOutOfBedNudge). Returns whether the write succeeded.
 *
 * Round 3 of the 09/21 review's own nit: writes to a sibling temp file first, then [File.renameTo] over the
 * real one - both operations on the same app-private directory, so the rename is atomic. The UI now reads this
 * same file on a ticker ([com.nikita.sleepcycle.ui.NightViewModel]'s [readOutOfBedNudgePendingAt] call); a
 * plain truncate-then-write let a read land mid-write and see a truncated, unparsable file, logging a
 * spurious error - harmless (the next tick's re-read self-heals) but noisy. The rename makes that window
 * disappear instead of merely tolerating it.
 */
fun saveOutOfBedNudgePendingAt(context: Context, at: Instant): Boolean =
    try {
        val target = outOfBedNudgePendingFile(context)
        val temp = File(target.parentFile, "$OUT_OF_BED_NUDGE_PENDING_FILE_NAME.tmp")
        temp.writeText(at.toString())
        if (!temp.renameTo(target)) throw java.io.IOException("renameTo failed for $temp -> $target")
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

/**
 * Clears the pending nudge instant: called when the nudge itself fires (self-consuming) and when the owner
 * ends the night - see this file's own header for why FINISHED-bookkeeping (G1) must NOT call this. Returns
 * whether the file is now gone (true if it was deleted, or was already absent - see below).
 *
 * Round 3 of the 09/21 review, should-fix 4: [File.delete]'s return value used to be silently ignored, unlike
 * [saveOutOfBedNudgePendingAt]'s own check-and-log. That silence is now load-bearing for a user-visible claim:
 * every OTHER stale-nudge path self-heals through [applyPendingOutOfBedNudge][com.nikita.sleepcycle.ui.state.applyPendingOutOfBedNudge]'s
 * `isAfter(now)` guard, but a failed delete on the nap-supersede or pre-check-cancel call sites leaves a
 * FUTURE instant on disk with no alarm behind it - `isAfter(now)` stays true, so the header keeps asserting
 * "Out-of-bed nudge at HH:MM" for a nudge that will never ring, until the night ends. All three call sites
 * (`NightController.kt`, `PhoneAlarmReceiver.kt`, `OutOfBedPreNudgeCheck.kt`) are outside this agent's
 * ownership for this round, so the check-and-log moved into the store itself rather than each call site -
 * flagging this here per the task's own instruction, since the ideal fix (each caller deciding how to react to
 * a failed cancel, e.g. retrying or surfacing it) still belongs to whoever owns those files.
 */
fun clearOutOfBedNudgePendingAt(context: Context): Boolean {
    val file = outOfBedNudgePendingFile(context)
    if (!file.exists()) return true
    val deleted = file.delete()
    if (!deleted) Log.e(LOG_TAG, "failed to delete the pending out-of-bed nudge file - a stale future instant may linger on screen until the night ends")
    return deleted
}

private fun outOfBedNudgePendingFile(context: Context): File = File(context.filesDir, OUT_OF_BED_NUDGE_PENDING_FILE_NAME)
