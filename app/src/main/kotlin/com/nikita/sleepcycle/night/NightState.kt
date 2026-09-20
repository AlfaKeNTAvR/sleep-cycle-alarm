package com.nikita.sleepcycle.night

// File purpose: the persisted night state - JSON round trip, atomic file writes with a backup and
// quarantine for corruption, load/save/clear. Decoding tolerates unknown enum values and missing optional
// fields: one bad piece of state degrades gracefully rather than throwing the whole night away.

import android.content.Context
import android.util.Log
import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.AlarmPlan
import com.nikita.sleepcycle.engine.MAX_NAP_ALARMS
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.SegmentKind
import com.nikita.sleepcycle.engine.SleepSegment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.time.Instant

private const val NIGHT_STATE_FILE_NAME = "night_state.json"
private const val NIGHT_STATE_BACKUP_FILE_NAME = "night_state.bak"
private const val LOG_TAG = "NightState"

/** Everything needed to resume tracking a night after the app or the phone restarts. */
data class NightState(
    val startedAt: Instant,
    val settings: NightSettings,
    val lastPlan: AlarmPlan?,
    val lastSyncAt: Instant?,
    val lastSyncOk: Boolean?,
    val lastSegments: List<SleepSegment>,
    val lastExportFileModifiedAt: Instant?,
    /** Set only when the last sync itself failed (a step timed out or errored); null when it succeeded, even if the data it returned was stale. Lets the UI say which of the two happened. */
    val lastSyncFailureCause: String?,
    /** The last phone alarm instant that has already fired (wake, nap, or a rule 7 mid-night nap alike), so armPhoneAlarmIfNeeded never re-arms a past instant Android would fire immediately. Null until the first firing. */
    val phoneAlarmFiredFor: Instant? = null,
    /** F6: the MAIN wake alarm's own fired instant - never a nap's. The engine's `wakeAlarmFiredAt` input to computeWakeAlarm: G8 SUPERSEDES D5 - this no longer has anything to do with the nap cap (NightState.napAlarmsUsed alone decides that now); its one remaining job is G3's guard on rule 7's sliding AWAKE nap (see WakeAlarm.kt's `napAlarm`). A rule 7 mid-night nap firing must never set this. Null until the wake alarm fires. */
    val wakeAlarmFiredAt: Instant? = null,
    /** The debug options this night was started with (see DebugOptions.kt), captured once at startNight so toggling the Debug screen mid-night never changes a night already in progress. Defaults to all-off so state saved before this field existed still decodes as a normal night. */
    val debugOptions: DebugOptions = DebugOptions(),
    /**
     * D3/D6: when the owner tapped "I'm awake" on the ring screen, recorded by NightController.endNight just
     * before it logs and snapshots the night. Null for a night still in progress, or one ended any other way
     * (deadline, nap cap, or "Stop night"/"I'm up, end night" from the Night screen).
     *
     * F15: written to the night log (`night_end`, via nightEndFields) and to the morning-report snapshot, but
     * nothing in the UI reads it back yet - it is recorded on the chance a later feature wants it (e.g. the
     * morning report saying "you confirmed awake at HH:MM"), not for any current consumer. Kept rather than
     * removed because it costs nothing to persist and the alternative is re-deriving it later from the night
     * log alone, which is strictly worse. Deliberately asymmetric by design, not a bug: only [AlarmActivity]'s
     * "I'm awake" passes a non-null value to `endNight`; the Night screen's "Stop night"/"I'm up, end night"
     * (NightViewModel.confirmEndNight) always ends the night with this left null, because tapping either of
     * those is not itself a claim of being awake - the owner could be ending the night for the deadline, or
     * just to stop tracking. If this field is ever surfaced, both write paths already do the right thing; only
     * the read side needs building.
     */
    val awakeConfirmedAt: Instant? = null,
    /** F2 SUPERSEDES the original spec: how many D5 nap alarms have actually FIRED this night, not how many have been armed - clamped at MAX_NAP_ALARMS. Incremented by PhoneAlarmReceiver when a real nap alarm fires, never at arm time (immune to retargeting and pull-forward). Resets to 0 with every new night. */
    val napAlarmsUsed: Int = 0,
    /**
     * H1: the night's own morning alarm time, LATCHED - set from `lastPlan.wakeAt` whenever a tick produces a
     * FULL_CYCLES or DEADLINE_ONLY plan, and never overwritten by a NAP plan's own slid `wakeAt` (see
     * NightOrchestrator.latchMorningAlarmAt). The engine's `morningAlarmAt` input to computeAlarmPlan: its one
     * job is letting rule 7's sliding AWAKE nap tell that the morning's alarm time has passed PERMANENTLY,
     * even when no firing was ever recorded for it (H1 superseded G3's `previousPlan?.wakeAt` reading, which
     * was unreachable in exactly the case it existed for - see WakeAlarm.kt's `napAlarm`). Null until the
     * first such plan exists.
     */
    val morningAlarmAt: Instant? = null,
    /**
     * H2: the most recent nap alarm's own fired instant, mid-night (rule 7) or post-wake (D5) alike - null
     * until the first nap alarm ever fires this night. The engine's `lastNapAlarmFiredAt` input to
     * computeAlarmPlan: lets it tell "a nap alarm already rang for THIS stretch, the owner slept through it"
     * apart from "this target is merely overdue" (a nap detected late, or an alarm computed in the past after
     * a reboot) - only the first case skips the ordinary pull-forward for a genuinely fresh napLength instead
     * of a stale two-minute reprise of the alarm that just rang. Set by PhoneAlarmReceiver at fire time, same
     * as wakeAlarmFiredAt/napAlarmsUsed - see PhoneAlarmFiredStore.kt.
     */
    val lastNapAlarmFiredAt: Instant? = null
)

/** Turns a night state into its JSON text form, the inverse of [decodeNightState]. */
fun encodeNightState(state: NightState): String {
    val json = JSONObject()
    json.put("startedAt", state.startedAt.toString())
    json.put("settings", encodeNightSettings(state.settings))
    json.put("lastPlan", state.lastPlan?.let(::encodeAlarmPlan) ?: JSONObject.NULL)
    json.put("lastSyncAt", state.lastSyncAt?.toString() ?: JSONObject.NULL)
    json.put("lastSyncOk", state.lastSyncOk ?: JSONObject.NULL)
    json.put("lastSegments", encodeSleepSegments(state.lastSegments))
    json.put("lastExportFileModifiedAt", state.lastExportFileModifiedAt?.toString() ?: JSONObject.NULL)
    json.put("lastSyncFailureCause", state.lastSyncFailureCause ?: JSONObject.NULL)
    json.put("phoneAlarmFiredFor", state.phoneAlarmFiredFor?.toString() ?: JSONObject.NULL)
    json.put("debugOptions", encodeDebugOptions(state.debugOptions))
    json.put("awakeConfirmedAt", state.awakeConfirmedAt?.toString() ?: JSONObject.NULL)
    json.put("napAlarmsUsed", state.napAlarmsUsed)
    json.put("wakeAlarmFiredAt", state.wakeAlarmFiredAt?.toString() ?: JSONObject.NULL)
    json.put("morningAlarmAt", state.morningAlarmAt?.toString() ?: JSONObject.NULL)
    json.put("lastNapAlarmFiredAt", state.lastNapAlarmFiredAt?.toString() ?: JSONObject.NULL)
    return json.toString()
}

/**
 * Parses a night state from JSON text, the inverse of [encodeNightState]. Two fields fail loudly rather than
 * degrading, because each is read with `getX`/`Instant.parse` directly rather than through a tolerant `optX`
 * wrapped in its own try/catch the way `lastPlan`, `lastSegments` and `debugOptions` are: `startedAt` and
 * `settings`, both structurally required. G6 SUPERSEDES F14: `napAlarmsUsed` and `awakeConfirmedAt` used to be
 * on this list too (a malformed one quarantined the live file and fell back to the backup, and if that also
 * failed the owner had no night and no alarm) - both have an obvious safe default (0, null respectively), the
 * same reasoning [decodeDebugOptionsTolerant] already used, so they now degrade like every other optional
 * field instead. Everything else malformed (an unknown plan mode, a corrupt segment) is dropped and logged
 * instead of failing the whole parse. D2: a state file written by the old build may still carry
 * `requestedBandAlarm`, `pendingDismissTitles` and the rest of the deleted band-alarm fields - those keys are
 * simply ignored, since [JSONObject] only ever reads the keys this decoder asks for.
 */
fun decodeNightState(text: String): NightState {
    val json = JSONObject(text)
    return NightState(
        startedAt = Instant.parse(json.getString("startedAt")),
        settings = decodeNightSettings(json.getJSONObject("settings")),
        lastPlan = decodePlanTolerant(json.optJSONObject("lastPlan")),
        lastSyncAt = json.optStringOrNull("lastSyncAt")?.let(Instant::parse),
        lastSyncOk = if (json.isNull("lastSyncOk")) null else json.getBoolean("lastSyncOk"),
        lastSegments = decodeSleepSegmentsTolerant(json.optJSONArray("lastSegments")),
        lastExportFileModifiedAt = json.optStringOrNull("lastExportFileModifiedAt")?.let(Instant::parse),
        lastSyncFailureCause = json.optStringOrNull("lastSyncFailureCause"),
        phoneAlarmFiredFor = json.optStringOrNull("phoneAlarmFiredFor")?.let(Instant::parse),
        debugOptions = decodeDebugOptionsTolerant(json.optJSONObject("debugOptions")),
        // G6: absent (a state file written before D3/D6) or unparseable decodes as "not confirmed yet", the
        // same default a brand new night starts with, rather than throwing the whole state away.
        awakeConfirmedAt = decodeAwakeConfirmedAtTolerant(json),
        // G6/F2: absent (a state file written before D5) or not a JSON number decodes as 0, same reasoning as
        // awakeConfirmedAt above. Clamped defensively either way - the store file is the normal source of
        // truth (see mergeAlarmFiredStores), this blob copy is only ever a fallback, and a count above the cap
        // must never survive into a plan.
        napAlarmsUsed = decodeNapAlarmsUsedTolerant(json).coerceAtMost(MAX_NAP_ALARMS),
        wakeAlarmFiredAt = json.optStringOrNull("wakeAlarmFiredAt")?.let(Instant::parse),
        // H1: same non-tolerant style as phoneAlarmFiredFor/wakeAlarmFiredAt above. Unlike those two,
        // morningAlarmAt is NightOrchestrator's own bookkeeping (computed synchronously inside the tick's own
        // transaction, never PhoneAlarmReceiver's), so it has no separate PhoneAlarmFiredStore.kt file to
        // merge in - this blob copy is the only one, current as of the last committed tick.
        morningAlarmAt = json.optStringOrNull("morningAlarmAt")?.let(Instant::parse),
        // H2: same non-tolerant style, but this one IS re-merged fresh from PhoneAlarmFiredStore.kt on every
        // load (see loadNightState) - this blob copy is only ever a fallback for a state file saved before
        // this field existed (absent, decodes as null).
        lastNapAlarmFiredAt = json.optStringOrNull("lastNapAlarmFiredAt")?.let(Instant::parse)
    )
}

/** G6: absent (a state file written before D5) decodes as 0; present but not a JSON number decodes as 0 too, rather than throwing the whole state away - napAlarmsUsed has an obvious safe default, the same reasoning [decodeDebugOptionsTolerant] uses. */
private fun decodeNapAlarmsUsedTolerant(json: JSONObject): Int {
    if (!json.has("napAlarmsUsed")) return 0
    return try {
        json.getInt("napAlarmsUsed")
    } catch (error: Exception) {
        0
    }
}

/** G6: absent or unparseable decodes as null ("not confirmed yet"), the same default a brand new night starts with, rather than throwing the whole state away. */
private fun decodeAwakeConfirmedAtTolerant(json: JSONObject): Instant? {
    val text = json.optStringOrNull("awakeConfirmedAt") ?: return null
    return try {
        Instant.parse(text)
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
            fastNight = json.getBoolean("fastNight")
        )
    } catch (error: Exception) {
        DebugOptions()
    }
}

private fun encodeDebugOptions(options: DebugOptions): JSONObject = JSONObject().apply {
    put("simulatedBandData", options.simulatedBandData)
    put("fastNight", options.fastNight)
}

private fun encodeNightSettings(settings: NightSettings): JSONObject = JSONObject().apply {
    put("deadline", settings.deadline?.toString() ?: JSONObject.NULL)
    put("pickedCycles", settings.pickedCycles)
}

private fun decodeNightSettings(json: JSONObject): NightSettings = NightSettings(
    deadline = json.optStringOrNull("deadline")?.let(Instant::parse),
    pickedCycles = json.getInt("pickedCycles")
)

private fun encodeAlarmPlan(plan: AlarmPlan): JSONObject = JSONObject().apply {
    put("mode", plan.mode.name)
    put("wakeAt", plan.wakeAt?.toString() ?: JSONObject.NULL)
    put("cycles", plan.cycles)
    put("referenceOnset", plan.referenceOnset?.toString() ?: JSONObject.NULL)
    put("onsetIsProjected", plan.onsetIsProjected)
    put("reason", plan.reason)
    put("sleptSoFarSeconds", plan.sleptSoFar.seconds)
    put("owedCycles", plan.owedCycles)
}

private fun decodeAlarmPlan(json: JSONObject): AlarmPlan = AlarmPlan(
    mode = AlarmMode.valueOf(json.getString("mode")),
    wakeAt = json.optStringOrNull("wakeAt")?.let(Instant::parse),
    cycles = json.getInt("cycles"),
    referenceOnset = json.optStringOrNull("referenceOnset")?.let(Instant::parse),
    onsetIsProjected = json.getBoolean("onsetIsProjected"),
    reason = json.getString("reason"),
    sleptSoFar = if (json.has("sleptSoFarSeconds")) Duration.ofSeconds(json.getLong("sleptSoFarSeconds")) else Duration.ZERO,
    owedCycles = if (json.has("owedCycles")) json.getInt("owedCycles") else 0
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
 * deleted, and the failure is written into the newest night log so it is visible without adb. D3/F2/F6/H2:
 * [NightState.phoneAlarmFiredFor], [NightState.wakeAlarmFiredAt], [NightState.napAlarmsUsed] and
 * [NightState.lastNapAlarmFiredAt] are always re-merged from PhoneAlarmFiredStore.kt's own tiny files, which
 * are always the freshest values regardless of what stale copy this blob happens to carry.
 */
fun loadNightState(context: Context): NightState? =
    loadNightStateWithoutMerge(context)?.let {
        mergeAlarmFiredStores(
            it, readPhoneAlarmFiredFor(context), readWakeAlarmFiredAt(context), readNapAlarmsUsed(context), readLastNapAlarmFiredAt(context)
        )
    }

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
    // D3/F2/F6: these files belong only to the night that just ended too - left behind, they would wrongly
    // merge a stale fired instant, wake-alarm marker or nap count into the very first loadNightState of the
    // next night.
    clearAlarmFiredStores(context)
}

private fun nightStateFile(context: Context): File = File(context.filesDir, NIGHT_STATE_FILE_NAME)
