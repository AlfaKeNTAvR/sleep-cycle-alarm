package com.nikita.sleepcycle.night

// File purpose: four small facts PhoneAlarmReceiver records the instant a real alarm fires, each in its own
// tiny file, separate from night_state.json:
//   - D3 phoneAlarmFiredFor: the last alarm instant that fired at all (wake, nap, or a rule 7 mid-night nap) -
//     armPhoneAlarmIfNeeded's own re-arm guard, so it never re-arms an instant Android would fire immediately.
//   - F6 wakeAlarmFiredAt: the instant the MAIN wake alarm fired, never a nap's own firing (NightState.
//     wakeAlarmFiredAt, the engine's wakeAlarmFiredAt input) - G8 SUPERSEDES D5: no longer has anything to do
//     with the nap cap, its one remaining job is G3's guard on rule 7's sliding AWAKE nap.
//   - F2/G8 napAlarmsUsed: how many nap alarms have actually FIRED this night, mid-night (rule 7) or post-wake
//     (D5) alike, clamped at MAX_NAP_ALARMS - this alone is what the nap cap counts against now.
//   - H2 lastNapAlarmFiredAt: the most recent nap alarm's own fired instant, mid-night or post-wake alike -
//     the engine's lastNapAlarmFiredAt input to computeAlarmPlan, letting it tell "a nap alarm already rang
//     for THIS stretch" apart from "this target is merely overdue" (see WakeAlarm.kt's own doc).
// PhoneAlarmReceiver.markPhoneAlarmFired used to do an unlocked read-modify-write of the WHOLE night state - a
// real race against a tick's own read-modify-write under withNightTransactionLock, where whichever write lands
// last silently discards the other's changes. Writing only these small, independent files removes the
// read-modify-write entirely: there is nothing to race, since nothing else ever writes them. loadNightState
// merges all four in on every load, so they are always the freshest source of truth regardless of what stale
// copy a concurrent tick's own blob still carries.

import android.content.Context
import android.util.Log
import com.nikita.sleepcycle.engine.MAX_NAP_ALARMS
import java.io.File
import java.time.Instant

private const val PHONE_ALARM_FIRED_FILE_NAME = "phone_alarm_fired.txt"
private const val WAKE_ALARM_FIRED_FILE_NAME = "wake_alarm_fired.txt"
private const val NAP_ALARMS_USED_FILE_NAME = "nap_alarms_used.txt"
private const val LAST_NAP_ALARM_FIRED_AT_FILE_NAME = "last_nap_alarm_fired_at.txt"
private const val LOG_TAG = "PhoneAlarmFiredStore"

/** Persists [firedFor] as the phone alarm's fired instant. A single fast overwrite, not a read-modify-write, so it never races a concurrent tick's own state save. Returns whether the write succeeded. */
fun savePhoneAlarmFiredFor(context: Context, firedFor: Instant): Boolean =
    writeInstantFile(phoneAlarmFiredFile(context), firedFor)

/** The persisted fired instant, or null if the file is absent, empty, or unparsable (never throws). */
fun readPhoneAlarmFiredFor(context: Context): Instant? = readInstantFile(phoneAlarmFiredFile(context))

/** F6: persists [firedFor] as the MAIN wake alarm's fired instant - called only when the firing alarm's own plan was not a nap (see NightOrchestrator.firedAlarmIsWakeAlarm). Never called for a rule 7 mid-night nap's own firing. */
fun saveWakeAlarmFiredAt(context: Context, firedFor: Instant): Boolean =
    writeInstantFile(wakeAlarmFiredFile(context), firedFor)

/** The persisted wake-alarm-fired instant, or null if the file is absent, empty, unparsable, or the wake alarm has not fired this night. */
fun readWakeAlarmFiredAt(context: Context): Instant? = readInstantFile(wakeAlarmFiredFile(context))

/** F2/G8 SUPERSEDE the original spec: persists [count] as how many nap alarms have FIRED this night, mid-night (rule 7) or post-wake (D5) alike, already clamped to [com.nikita.sleepcycle.engine.MAX_NAP_ALARMS] by the caller. */
fun saveNapAlarmsUsed(context: Context, count: Int): Boolean =
    try {
        napAlarmsUsedFile(context).writeText(count.toString())
        true
    } catch (error: Exception) {
        Log.e(LOG_TAG, "failed to save napAlarmsUsed", error)
        false
    }

/** The persisted fired-nap count, or null if the file is absent or unparsable (never throws). */
fun readNapAlarmsUsed(context: Context): Int? {
    val file = napAlarmsUsedFile(context)
    if (!file.exists()) return null
    return try {
        file.readText().trim().toInt()
    } catch (error: Exception) {
        Log.e(LOG_TAG, "failed to read napAlarmsUsed, treating as absent", error)
        null
    }
}

/** H2: persists [firedFor] as the most recent nap alarm's own fired instant, mid-night (rule 7) or post-wake (D5) alike - called whenever a firing nap increments [saveNapAlarmsUsed], never for the main wake alarm. */
fun saveLastNapAlarmFiredAt(context: Context, firedFor: Instant): Boolean =
    writeInstantFile(lastNapAlarmFiredAtFile(context), firedFor)

/** The persisted instant of the most recent nap alarm to fire, or null if the file is absent, empty, unparsable, or no nap alarm has fired this night. */
fun readLastNapAlarmFiredAt(context: Context): Instant? = readInstantFile(lastNapAlarmFiredAtFile(context))

/**
 * Deletes all three of this file's stores. Called both by [clearNightState] (this file belongs only to the
 * night that wrote it; left behind, it would wrongly merge into the very first load of the NEXT night - D3)
 * and by `startNight` (F8: a night that never reached `endNight`, e.g. both the state file and its backup
 * failing to decode, would otherwise leave a stale fired marker live from tick 1 of a night whose own alarm
 * has not fired).
 */
fun clearAlarmFiredStores(context: Context) {
    phoneAlarmFiredFile(context).delete()
    wakeAlarmFiredFile(context).delete()
    napAlarmsUsedFile(context).delete()
    lastNapAlarmFiredAtFile(context).delete()
}

/**
 * Merges the freshest store values into [state] where present, per D3/F2/F6/H2 - always current, since only
 * this file's own save functions ever write them. [napAlarmsUsed] is clamped to [MAX_NAP_ALARMS] defensively
 * (the file is only ever written already-clamped, but a merge must never let a corrupt read hand the engine a
 * count above the cap). Pure so the merge itself is JVM-testable without a File.
 */
internal fun mergeAlarmFiredStores(
    state: NightState, phoneAlarmFiredFor: Instant?, wakeAlarmFiredAt: Instant?, napAlarmsUsed: Int?, lastNapAlarmFiredAt: Instant?
): NightState =
    state.copy(
        phoneAlarmFiredFor = phoneAlarmFiredFor ?: state.phoneAlarmFiredFor,
        wakeAlarmFiredAt = wakeAlarmFiredAt ?: state.wakeAlarmFiredAt,
        napAlarmsUsed = (napAlarmsUsed ?: state.napAlarmsUsed).coerceIn(0, MAX_NAP_ALARMS),
        lastNapAlarmFiredAt = lastNapAlarmFiredAt ?: state.lastNapAlarmFiredAt
    )

private fun writeInstantFile(file: File, value: Instant): Boolean =
    try {
        file.writeText(value.toString())
        true
    } catch (error: Exception) {
        Log.e(LOG_TAG, "failed to write ${file.name}", error)
        false
    }

private fun readInstantFile(file: File): Instant? {
    if (!file.exists()) return null
    return try {
        Instant.parse(file.readText().trim())
    } catch (error: Exception) {
        Log.e(LOG_TAG, "failed to read ${file.name}, treating as absent", error)
        null
    }
}

private fun phoneAlarmFiredFile(context: Context): File = File(context.filesDir, PHONE_ALARM_FIRED_FILE_NAME)
private fun wakeAlarmFiredFile(context: Context): File = File(context.filesDir, WAKE_ALARM_FIRED_FILE_NAME)
private fun napAlarmsUsedFile(context: Context): File = File(context.filesDir, NAP_ALARMS_USED_FILE_NAME)
private fun lastNapAlarmFiredAtFile(context: Context): File = File(context.filesDir, LAST_NAP_ALARM_FIRED_AT_FILE_NAME)
