package com.nikita.sleepcycle.night

// File purpose: D3 - the phone alarm's fired instant, persisted in its own tiny file, separate from
// night_state.json. PhoneAlarmReceiver.markPhoneAlarmFired used to do an unlocked read-modify-write of the
// WHOLE night state - a real race against a tick's own read-modify-write under withNightTransactionLock,
// where whichever write lands last silently discards the other's changes. Writing only this one small,
// independent file removes the read-modify-write entirely: there is nothing to race, since nothing else ever
// writes this file. loadNightState merges it in on every load, so it is always the freshest source of truth
// for NightState.phoneAlarmFiredFor regardless of what stale copy a concurrent tick's own blob still carries.

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant

private const val PHONE_ALARM_FIRED_FILE_NAME = "phone_alarm_fired.txt"
private const val LOG_TAG = "PhoneAlarmFiredStore"

/** Persists [firedFor] as the phone alarm's fired instant. A single fast overwrite, not a read-modify-write, so it never races a concurrent tick's own state save. Returns whether the write succeeded. */
fun savePhoneAlarmFiredFor(context: Context, firedFor: Instant): Boolean =
    try {
        phoneAlarmFiredFile(context).writeText(firedFor.toString())
        true
    } catch (error: Exception) {
        Log.e(LOG_TAG, "failed to save phoneAlarmFiredFor", error)
        false
    }

/** Deletes the persisted fired instant. Called by [clearNightState] - this file belongs only to the night that wrote it; left behind, it would wrongly merge into the very first load of the NEXT night. */
fun clearPhoneAlarmFiredFor(context: Context) {
    phoneAlarmFiredFile(context).delete()
}

/** The persisted fired instant, or null if the file is absent, empty, or unparsable (never throws). */
fun readPhoneAlarmFiredFor(context: Context): Instant? {
    val file = phoneAlarmFiredFile(context)
    if (!file.exists()) return null
    return try {
        Instant.parse(file.readText().trim())
    } catch (error: Exception) {
        Log.e(LOG_TAG, "failed to read phoneAlarmFiredFor, treating as absent", error)
        null
    }
}

/** Merges [store]'s value into [state] if present, per D3 - always the freshest, since only [savePhoneAlarmFiredFor] ever writes it. Pure so the merge itself is JVM-testable without a File. */
internal fun mergePhoneAlarmFiredFor(state: NightState, store: Instant?): NightState =
    if (store != null) state.copy(phoneAlarmFiredFor = store) else state

private fun phoneAlarmFiredFile(context: Context): File = File(context.filesDir, PHONE_ALARM_FIRED_FILE_NAME)
