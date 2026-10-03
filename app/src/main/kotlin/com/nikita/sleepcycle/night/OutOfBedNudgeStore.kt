package com.nikita.sleepcycle.night

// File purpose: G4 - the out-of-bed nudge's own pending fire instant (D4), persisted separately from
// night_state.json and from PhoneAlarmFiredStore.kt's three "already fired" facts, because this one is
// forward-looking (an alarm not yet fired) rather than a record of one that already has. Without it, a
// reboot or app update between the nudge being armed and it actually firing drops it silently - AlarmManager
// alarms do not survive either event (F13), and nothing else remembers when it was due.
//
// Deliberately NOT part of clearAlarmFiredStores/clearNightState's own bundle: it is cleared when the nudge
// itself fires (self-consuming, PhoneAlarmReceiver.onReceive) or when the owner ends the night
// (NightController.endNight). G1's FINISHED-bookkeeping path leaves an already-armed nudge alone on purpose
// (it must still fire), so it must not erase the one record that could restore that same nudge across a
// reboot before it does.
//
// L1 (owner decision, 2026-09-21): the nudge now REPEATS until the night ends, so that self-consuming clear
// is immediately followed, in the same receiver call, by a save of the NEXT nudge's instant - one record,
// overwritten once per repeat, never a list and never a count. Nothing here changed for L1; this note exists
// so a reader does not conclude from "cleared when the nudge fires" that a fired nudge leaves the file empty.
// It does, for the moment between the clear and the re-arm, and an arming that FAILS leaves it empty for
// good, which is correct: there is then no nudge left to restore.
//
// P3 (owner spec, 2026-09-30): the same record now also holds the owner's own "Nap for 20 min" alarm, which
// shares the nudge's slot - one follow-up at a time, with its kind ([PendingFollowUp]). A nap is written as
// `NAP <instant>`; a nudge stays the bare instant it always was, so a record from an older build still reads
// back as the nudge it is.

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
    savePendingFollowUp(context, PendingFollowUp(FollowUpKind.NUDGE, at))

/**
 * P3: persists [followUp] - the nudge or the owner's own nap, whichever is pending in the out-of-bed slot - in
 * this file's one record. Same temp-file-and-rename write as before; see [saveOutOfBedNudgePendingAt]'s own doc.
 */
fun savePendingFollowUp(context: Context, followUp: PendingFollowUp): Boolean =
    try {
        val target = outOfBedNudgePendingFile(context)
        val temp = File(target.parentFile, "$OUT_OF_BED_NUDGE_PENDING_FILE_NAME.tmp")
        temp.writeText(formatPendingFollowUp(followUp))
        replaceFileAtomically(temp, target)
        true
    } catch (error: Exception) {
        Log.e(LOG_TAG, "failed to save the pending out-of-bed nudge instant", error)
        false
    }

/**
 * The pending follow-up's own fire instant - the nudge's, or (P3) the owner's own nap's - or null if none is
 * armed, or the file is absent/unparsable (never throws). Every reader that only needs "is something still
 * coming, and when" (L2.1's deferral, the stale-nudge bounds) uses this; one that must tell the two apart reads
 * [readPendingFollowUp].
 */
fun readOutOfBedNudgePendingAt(context: Context): Instant? = readPendingFollowUp(context)?.at

/** P3: the pending follow-up with its kind, or null if none is armed, or the file is absent/unparsable (never throws). */
fun readPendingFollowUp(context: Context): PendingFollowUp? {
    val file = outOfBedNudgePendingFile(context)
    if (!file.exists()) return null
    val text = try {
        file.readText()
    } catch (error: Exception) {
        Log.e(LOG_TAG, "failed to read the pending out-of-bed nudge instant, treating as absent", error)
        return null
    }
    return parsePendingFollowUp(text) ?: run {
        Log.e(LOG_TAG, "unparsable pending out-of-bed record '$text', treating as absent")
        null
    }
}

/** P3: the record's marker for the owner's own nap. A nudge is written as the bare instant, exactly as before P3. */
private const val NAP_RECORD_PREFIX = "NAP "

/** P3: the record's text - `NAP <instant>` for a nap, the bare instant for a nudge (the pre-P3 format, unchanged). */
internal fun formatPendingFollowUp(followUp: PendingFollowUp): String = when (followUp.kind) {
    FollowUpKind.NAP -> NAP_RECORD_PREFIX + followUp.at
    FollowUpKind.NUDGE -> followUp.at.toString()
}

/** P3: reads [formatPendingFollowUp]'s text back; a bare instant (every record before P3) is a nudge. Null when unparsable. */
internal fun parsePendingFollowUp(text: String): PendingFollowUp? {
    val trimmed = text.trim()
    val isNap = trimmed.startsWith(NAP_RECORD_PREFIX)
    val instantText = if (isNap) trimmed.removePrefix(NAP_RECORD_PREFIX) else trimmed
    val at = runCatching { Instant.parse(instantText) }.getOrNull() ?: return null
    return PendingFollowUp(if (isNap) FollowUpKind.NAP else FollowUpKind.NUDGE, at)
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
