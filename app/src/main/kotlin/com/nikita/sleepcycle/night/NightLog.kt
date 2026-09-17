package com.nikita.sleepcycle.night

// File purpose: append-only JSONL logs - one JSON object per line - for the per-night log, the standalone
// setup-check log, and listing saved night logs.

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val LOG_TAG = "NightLog"
private const val NIGHT_LOGS_DIR_NAME = "nightlogs"
private const val SETUP_LOG_FILE_NAME = "setup.jsonl"
private val LOG_FILE_NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

/** One entry in a log. `type` is one of: night_start, alarm_readiness, tick, sync, data, plan,
 * band_alarm_requested, band_alarm_confirmed, band_alarm_missing, band_alarm_dismissed, stale_data,
 * phone_alarm_set, phone_alarm_fired, alarm_sound_chosen, clock_changed, error, night_end, setup_check. */
data class NightLogEvent(val at: Instant, val type: String, val fields: Map<String, String>)

/** Formats one log event as a single JSON line, the file's on-disk shape, the inverse of parsing that line back with [org.json.JSONObject]. */
fun formatNightLogLine(event: NightLogEvent): String {
    val json = JSONObject()
    json.put("at", event.at.toString())
    json.put("type", event.type)
    val fields = JSONObject()
    event.fields.forEach { (key, value) -> fields.put(key, value) }
    json.put("fields", fields)
    return json.toString()
}

/**
 * Appends one event to the log file for the night that started at [startedAt], creating the file and
 * directory on first use. [debugNight] must be true whenever this night ran with any debug option on (see
 * DebugOptions.kt) - it changes the file's name to `night-sim-...` so a simulated night can never be mistaken
 * for a real one (see [nightLogFile]). Defaults to false so every real-night call site is unaffected.
 */
fun appendNightLog(context: Context, startedAt: Instant, event: NightLogEvent, debugNight: Boolean = false) {
    appendLogLine(nightLogFile(context, startedAt, debugNight), event)
}

/** Appends one event to the standalone setup-check log, in the same logs folder as the per-night logs. */
fun appendSetupLog(context: Context, event: NightLogEvent) {
    appendLogLine(setupLogFile(context), event)
}

/**
 * Appends to the currently active night's log, or the newest saved night log if no night is active. Used by
 * alarm-ringing code, which runs from a receiver/service that has no `startedAt` of its own to look up.
 * Does nothing if there is neither an active night nor any saved log yet.
 */
fun appendToCurrentNightLog(context: Context, event: NightLogEvent) {
    val file = currentOrNewestNightLogFile(context) ?: return
    appendLogLine(file, event)
}

/** Appends one formatted log line directly to [file]. The shared primitive behind every log target above. */
fun appendLogLine(file: File, event: NightLogEvent) {
    try {
        file.parentFile?.mkdirs()
        file.appendText(formatNightLogLine(event) + "\n")
    } catch (error: Exception) {
        Log.e(LOG_TAG, "failed to append log event ${event.type} to ${file.path}", error)
    }
}

/** Prefix a simulated night's log file carries, so the Logs screen and this file's own listing can tell it apart from a real night without opening it. */
const val NIGHT_LOG_SIMULATED_PREFIX = "night-sim-"
private const val NIGHT_LOG_REAL_PREFIX = "night-"

/** Lists saved night log files, newest first, for the Logs screen. */
fun listNightLogs(context: Context): List<File> =
    nightLogsDir(context).listFiles()
        ?.filter { it.isFile && it.name != SETUP_LOG_FILE_NAME }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()

private fun currentOrNewestNightLogFile(context: Context): File? {
    val activeState = loadNightState(context)
    if (activeState != null) return nightLogFile(context, activeState.startedAt, activeState.debugOptions.isAnyEnabled)
    return listNightLogs(context).firstOrNull()
}

private fun nightLogsDir(context: Context): File = File(context.filesDir, NIGHT_LOGS_DIR_NAME)

private fun setupLogFile(context: Context): File = File(nightLogsDir(context), SETUP_LOG_FILE_NAME)

/** [debugNight] picks between the real-night and the simulated-night file name prefix (see [NIGHT_LOG_SIMULATED_PREFIX]). */
private fun nightLogFile(context: Context, startedAt: Instant, debugNight: Boolean): File {
    val prefix = if (debugNight) NIGHT_LOG_SIMULATED_PREFIX else NIGHT_LOG_REAL_PREFIX
    val name = "$prefix${LOG_FILE_NAME_FORMAT.withZone(ZoneId.systemDefault()).format(startedAt)}.jsonl"
    return File(nightLogsDir(context), name)
}
