package com.nikita.sleepcycle.night

// File purpose: the persisted night state - JSON round trip, atomic file writes with a backup and
// quarantine for corruption, load/save/clear. Decoding tolerates unknown enum values and missing optional
// fields: one bad piece of state degrades gracefully rather than throwing the whole night away.

import android.content.Context
import android.util.Log
import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.AlarmPlan
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.SegmentKind
import com.nikita.sleepcycle.engine.SleepSegment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

private const val NIGHT_STATE_FILE_NAME = "night_state.json"
private const val NIGHT_STATE_BACKUP_FILE_NAME = "night_state.bak"
private const val LOG_TAG = "NightState"

/** Everything needed to resume tracking a night after the app or the phone restarts. */
data class NightState(
    val startedAt: Instant,
    val settings: NightSettings,
    val lastPlan: AlarmPlan?,
    val requestedBandAlarm: BandAlarmCommitment?,
    val confirmedBandAlarm: BandAlarmCommitment?,
    val lastSyncAt: Instant?,
    val lastSyncOk: Boolean?,
    val lastSegments: List<SleepSegment>,
    val lastExportFileModifiedAt: Instant?,
    /** Set only when the last sync itself failed (a step timed out or errored); null when it succeeded, even if the data it returned was stale. Lets the UI say which of the two happened. */
    val lastSyncFailureCause: String?,
    /** Band alarm titles a DISMISS was sent for but not yet confirmed gone by a read-back (BandAlarmDecision.kt rule 2). Defaults to empty so state saved before this field existed still decodes. */
    val pendingDismissTitles: Set<String> = emptySet(),
    /** The phone alarm instant that has already fired, so armPhoneAlarmIfNeeded never re-arms a past instant Android would fire immediately. Null until the first firing. */
    val phoneAlarmFiredFor: Instant? = null,
    /** The debug options this night was started with (see DebugOptions.kt), captured once at startNight so toggling the Debug screen mid-night never changes a night already in progress. Defaults to all-off so state saved before this field existed still decodes as a normal night. */
    val debugOptions: DebugOptions = DebugOptions(),
    /** C4: how many extra FINISHED ticks have been spent verifying a queued dismissal is really confirmed gone, bounded by MAX_FINISHED_CLEANUP_TICKS. Defaults to 0 so state saved before this field existed still decodes. */
    val finishedCleanupTicksUsed: Int = 0,
    /** BandAlarmDecision.kt's smart-wakeup warning as of the last tick that had a table to check (a blind tick keeps the previous value - see NightOrchestrator.kt). Null once no our-titled slot carries the flag. Defaults to null so state saved before this field existed still decodes. */
    val smartWakeupWarning: BandAlarmSmartWakeupWarning? = null,
    /** Which band alarm protocol the last tick ran (BandAlarmSlotMode.kt), for the Night screen and the night log. Null before the first tick of the night, and for state saved before this field existed. */
    val bandAlarmSlotMode: BandAlarmSlotMode? = null,
    /** How many of the night's [MAX_SINGLE_SLOT_RESENDS_PER_NIGHT] single-slot re-sends are already spent. Defaults to 0 so state saved before this field existed still decodes. */
    val singleSlotResendsUsed: Int = 0
)

/** Turns a night state into its JSON text form, the inverse of [decodeNightState]. */
fun encodeNightState(state: NightState): String {
    val json = JSONObject()
    json.put("startedAt", state.startedAt.toString())
    json.put("settings", encodeNightSettings(state.settings))
    json.put("lastPlan", state.lastPlan?.let(::encodeAlarmPlan) ?: JSONObject.NULL)
    json.put("requestedBandAlarm", state.requestedBandAlarm?.let(::encodeBandAlarmCommitment) ?: JSONObject.NULL)
    json.put("confirmedBandAlarm", state.confirmedBandAlarm?.let(::encodeBandAlarmCommitment) ?: JSONObject.NULL)
    json.put("lastSyncAt", state.lastSyncAt?.toString() ?: JSONObject.NULL)
    json.put("lastSyncOk", state.lastSyncOk ?: JSONObject.NULL)
    json.put("lastSegments", encodeSleepSegments(state.lastSegments))
    json.put("lastExportFileModifiedAt", state.lastExportFileModifiedAt?.toString() ?: JSONObject.NULL)
    json.put("lastSyncFailureCause", state.lastSyncFailureCause ?: JSONObject.NULL)
    json.put("pendingDismissTitles", JSONArray(state.pendingDismissTitles.toList()))
    json.put("phoneAlarmFiredFor", state.phoneAlarmFiredFor?.toString() ?: JSONObject.NULL)
    json.put("debugOptions", encodeDebugOptions(state.debugOptions))
    json.put("finishedCleanupTicksUsed", state.finishedCleanupTicksUsed)
    json.put("smartWakeupWarning", state.smartWakeupWarning?.let(::encodeSmartWakeupWarning) ?: JSONObject.NULL)
    json.put("bandAlarmSlotMode", state.bandAlarmSlotMode?.name ?: JSONObject.NULL)
    json.put("singleSlotResendsUsed", state.singleSlotResendsUsed)
    return json.toString()
}

/**
 * Parses a night state from JSON text, the inverse of [encodeNightState]. `startedAt` and `settings` are
 * structurally required: anything else malformed (an unknown plan mode, a bad band alarm commitment, a
 * corrupt segment) is dropped and logged rather than failing the whole parse. Throws [org.json.JSONException]
 * only when `startedAt` or `settings` themselves are missing or malformed.
 */
fun decodeNightState(text: String): NightState {
    val json = JSONObject(text)
    return NightState(
        startedAt = Instant.parse(json.getString("startedAt")),
        settings = decodeNightSettings(json.getJSONObject("settings")),
        lastPlan = decodePlanTolerant(json.optJSONObject("lastPlan")),
        requestedBandAlarm = decodeCommitmentTolerant(json.optJSONObject("requestedBandAlarm")),
        confirmedBandAlarm = decodeCommitmentTolerant(json.optJSONObject("confirmedBandAlarm")),
        lastSyncAt = json.optStringOrNull("lastSyncAt")?.let(Instant::parse),
        lastSyncOk = if (json.isNull("lastSyncOk")) null else json.getBoolean("lastSyncOk"),
        lastSegments = decodeSleepSegmentsTolerant(json.optJSONArray("lastSegments")),
        lastExportFileModifiedAt = json.optStringOrNull("lastExportFileModifiedAt")?.let(Instant::parse),
        lastSyncFailureCause = json.optStringOrNull("lastSyncFailureCause"),
        pendingDismissTitles = decodePendingDismissTitlesTolerant(json.optJSONArray("pendingDismissTitles")),
        phoneAlarmFiredFor = json.optStringOrNull("phoneAlarmFiredFor")?.let(Instant::parse),
        debugOptions = decodeDebugOptionsTolerant(json.optJSONObject("debugOptions")),
        finishedCleanupTicksUsed = if (json.has("finishedCleanupTicksUsed")) json.getInt("finishedCleanupTicksUsed") else 0,
        smartWakeupWarning = decodeSmartWakeupWarningTolerant(json.optJSONObject("smartWakeupWarning")),
        bandAlarmSlotMode = decodeBandAlarmSlotModeTolerant(json.optStringOrNull("bandAlarmSlotMode")),
        singleSlotResendsUsed = if (json.has("singleSlotResendsUsed")) json.getInt("singleSlotResendsUsed") else 0
    )
}

/** Absent, null, or an unknown mode name (a newer app version wrote it) decodes as null: the screen simply does not name a mode until the next tick resolves one. */
private fun decodeBandAlarmSlotModeTolerant(name: String?): BandAlarmSlotMode? {
    if (name == null) return null
    return BandAlarmSlotMode.values().firstOrNull { it.name == name }
}

private fun encodeSmartWakeupWarning(warning: BandAlarmSmartWakeupWarning): JSONObject = JSONObject().apply {
    put("title", warning.title)
    put("position", warning.position)
    put("windowMinutes", warning.windowMinutes ?: JSONObject.NULL)
}

private fun decodeSmartWakeupWarning(json: JSONObject): BandAlarmSmartWakeupWarning = BandAlarmSmartWakeupWarning(
    title = json.getString("title"),
    position = json.getInt("position"),
    windowMinutes = if (json.isNull("windowMinutes")) null else json.getInt("windowMinutes")
)

/** Absent, null, or malformed (an older field shape) decodes as null - one bad piece of state degrades gracefully, same as every other tolerant decode in this file. */
private fun decodeSmartWakeupWarningTolerant(json: JSONObject?): BandAlarmSmartWakeupWarning? {
    if (json == null) return null
    return try {
        decodeSmartWakeupWarning(json)
    } catch (error: Exception) {
        null
    }
}

/** Absent (state saved before this field existed) or malformed: decodes as all-off, same as a normal night. */
private fun decodeDebugOptionsTolerant(json: JSONObject?): DebugOptions {
    if (json == null) return DebugOptions()
    return try {
        DebugOptions(
            simulatedBandData = json.getBoolean("simulatedBandData"),
            fastNight = json.getBoolean("fastNight"),
            bandCommandMode = BandCommandMode.valueOf(json.getString("bandCommandMode"))
        )
    } catch (error: Exception) {
        DebugOptions()
    }
}

private fun encodeDebugOptions(options: DebugOptions): JSONObject = JSONObject().apply {
    put("simulatedBandData", options.simulatedBandData)
    put("fastNight", options.fastNight)
    put("bandCommandMode", options.bandCommandMode.name)
}

/** Absent (state saved before this field existed) or malformed entries are dropped rather than failing the whole load. */
private fun decodePendingDismissTitlesTolerant(array: JSONArray?): Set<String> {
    if (array == null) return emptySet()
    return (0 until array.length()).mapNotNull { index ->
        try {
            array.getString(index)
        } catch (error: Exception) {
            null
        }
    }.toSet()
}

private fun encodeNightSettings(settings: NightSettings): JSONObject = JSONObject().apply {
    put("deadline", settings.deadline?.toString() ?: JSONObject.NULL)
    put("pickedCycles", settings.pickedCycles)
    put("phoneBackupEnabled", settings.phoneBackupEnabled)
}

private fun decodeNightSettings(json: JSONObject): NightSettings = NightSettings(
    deadline = json.optStringOrNull("deadline")?.let(Instant::parse),
    pickedCycles = json.getInt("pickedCycles"),
    phoneBackupEnabled = json.getBoolean("phoneBackupEnabled")
)

private fun encodeAlarmPlan(plan: AlarmPlan): JSONObject = JSONObject().apply {
    put("mode", plan.mode.name)
    put("bandAlarm", plan.bandAlarm?.toString() ?: JSONObject.NULL)
    put("phoneAlarm", plan.phoneAlarm?.toString() ?: JSONObject.NULL)
    put("cycles", plan.cycles)
    put("referenceOnset", plan.referenceOnset?.toString() ?: JSONObject.NULL)
    put("onsetIsProjected", plan.onsetIsProjected)
    put("reason", plan.reason)
    put("overdueSince", plan.overdueSince?.toString() ?: JSONObject.NULL)
}

private fun decodeAlarmPlan(json: JSONObject): AlarmPlan = AlarmPlan(
    mode = AlarmMode.valueOf(json.getString("mode")),
    bandAlarm = json.optStringOrNull("bandAlarm")?.let(Instant::parse),
    phoneAlarm = json.optStringOrNull("phoneAlarm")?.let(Instant::parse),
    cycles = json.getInt("cycles"),
    referenceOnset = json.optStringOrNull("referenceOnset")?.let(Instant::parse),
    onsetIsProjected = json.getBoolean("onsetIsProjected"),
    reason = json.getString("reason"),
    overdueSince = json.optStringOrNull("overdueSince")?.let(Instant::parse)
)

/** An unknown mode (a newer app version wrote it) or any other malformed plan is dropped: the state loads with `lastPlan = null` instead of failing entirely. */
private fun decodePlanTolerant(json: JSONObject?): AlarmPlan? {
    if (json == null) return null
    return try {
        decodeAlarmPlan(json)
    } catch (error: Exception) {
        null
    }
}

private fun encodeBandAlarmCommitment(commitment: BandAlarmCommitment): JSONObject = JSONObject().apply {
    put("title", commitment.title)
    put("hour", commitment.hour)
    put("minute", commitment.minute)
    put("at", commitment.at.toString())
    put("blindResendCount", commitment.blindResendCount)
}

/** [blindResendCount] (C2) defaults to 0 when absent, so a state saved before this field existed still decodes. */
private fun decodeBandAlarmCommitment(json: JSONObject): BandAlarmCommitment = BandAlarmCommitment(
    title = json.getString("title"),
    hour = json.getInt("hour"),
    minute = json.getInt("minute"),
    at = Instant.parse(json.getString("at")),
    blindResendCount = if (json.has("blindResendCount")) json.getInt("blindResendCount") else 0
)

private fun decodeCommitmentTolerant(json: JSONObject?): BandAlarmCommitment? {
    if (json == null) return null
    return try {
        decodeBandAlarmCommitment(json)
    } catch (error: Exception) {
        null
    }
}

private fun encodeSleepSegments(segments: List<SleepSegment>): JSONArray {
    val array = JSONArray()
    segments.forEach { segment ->
        array.put(
            JSONObject().apply {
                put("start", segment.start.toString())
                put("end", segment.end.toString())
                put("kind", segment.kind.name)
            }
        )
    }
    return array
}

private fun decodeSleepSegmentsTolerant(array: JSONArray?): List<SleepSegment> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        try {
            val item = array.getJSONObject(index)
            SleepSegment(
                start = Instant.parse(item.getString("start")),
                end = Instant.parse(item.getString("end")),
                kind = SegmentKind.valueOf(item.getString("kind"))
            )
        } catch (error: Exception) {
            null
        }
    }
}

private fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else getString(name)

/**
 * Reads the persisted night state, or null if no night is in progress. A corrupt main file falls back to
 * the last good backup: the corrupt file is quarantined (renamed aside with a timestamp) rather than
 * deleted, and the failure is written into the newest night log so it is visible without adb. D3:
 * [NightState.phoneAlarmFiredFor] is always re-merged from [readPhoneAlarmFiredFor]'s own tiny file, which is
 * always the freshest value regardless of what stale copy this blob happens to carry.
 */
fun loadNightState(context: Context): NightState? =
    loadNightStateWithoutMerge(context)?.let { mergePhoneAlarmFiredFor(it, readPhoneAlarmFiredFor(context)) }

private fun loadNightStateWithoutMerge(context: Context): NightState? {
    val file = nightStateFile(context)
    if (!file.exists()) return null
    return try {
        decodeNightState(file.readText())
    } catch (error: Exception) {
        Log.e(LOG_TAG, "failed to load night state from ${file.path}, falling back to the backup", error)
        quarantineCorruptStateFile(context, file, error)
        loadBackupNightState(context)
    }
}

private fun loadBackupNightState(context: Context): NightState? {
    val backupFile = File(context.filesDir, NIGHT_STATE_BACKUP_FILE_NAME)
    if (!backupFile.exists()) return null
    return try {
        decodeNightState(backupFile.readText())
    } catch (error: Exception) {
        Log.e(LOG_TAG, "backup night state at ${backupFile.path} is also corrupt", error)
        null
    }
}

private fun quarantineCorruptStateFile(context: Context, file: File, cause: Exception) {
    val quarantined = File(context.filesDir, "$NIGHT_STATE_FILE_NAME.corrupt-${Instant.now().toEpochMilli()}")
    if (!file.renameTo(quarantined)) {
        Log.e(LOG_TAG, "failed to quarantine corrupt state file ${file.path}")
    }
    logCorruptStateToNewestNightLog(context, cause)
}

private fun logCorruptStateToNewestNightLog(context: Context, cause: Exception) {
    val newestLog = listNightLogs(context).firstOrNull() ?: return
    appendLogLine(
        newestLog,
        NightLogEvent(Instant.now(), "error", mapOf("step" to "load_night_state", "cause" to (cause.message ?: cause.toString())))
    )
}

/**
 * Writes the night state atomically: a temp file is written and fsynced first, the current live file (if
 * any) is preserved as [NIGHT_STATE_BACKUP_FILE_NAME], then the temp file is renamed over the live one - so
 * a crash mid-write cannot corrupt it, and a bad write still leaves yesterday's good state recoverable.
 * Returns true on success; callers are responsible for logging a failure to the night log.
 */
fun saveNightState(context: Context, state: NightState): Boolean {
    val file = nightStateFile(context)
    val tempFile = File(context.filesDir, "$NIGHT_STATE_FILE_NAME.tmp")
    val backupFile = File(context.filesDir, NIGHT_STATE_BACKUP_FILE_NAME)
    return try {
        FileOutputStream(tempFile).use { stream ->
            stream.write(encodeNightState(state).toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
        }
        if (file.exists()) file.copyTo(backupFile, overwrite = true)
        val renamed = tempFile.renameTo(file)
        if (!renamed) Log.e(LOG_TAG, "failed to rename ${tempFile.path} to ${file.path}")
        renamed
    } catch (error: Exception) {
        Log.e(LOG_TAG, "failed to save night state to ${file.path}", error)
        false
    }
}

/**
 * Deletes the persisted night state, ending night tracking - the backup too (A2): a backup only ever belongs
 * to the night that wrote it (there is no `startedAt` guard to speak of once both copies are simply gone), so
 * leaving it behind would let a corrupt live file after the NEXT night started fall back to a stranger night's
 * state instead of failing loudly.
 */
fun clearNightState(context: Context) {
    nightStateFile(context).delete()
    File(context.filesDir, NIGHT_STATE_BACKUP_FILE_NAME).delete()
    // D3: this file belongs only to the night that just ended too - left behind, it would wrongly merge a
    // stale fired instant into the very first loadNightState of the next night.
    clearPhoneAlarmFiredFor(context)
}

private fun nightStateFile(context: Context): File = File(context.filesDir, NIGHT_STATE_FILE_NAME)
