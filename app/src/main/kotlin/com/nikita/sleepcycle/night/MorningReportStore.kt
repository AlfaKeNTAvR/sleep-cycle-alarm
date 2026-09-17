package com.nikita.sleepcycle.night

// File purpose: persists the morning report's source night state to disk when a night ends, so the report
// survives leaving and reopening the app once (including a process death) before "Done" is tapped. Cleared
// when the next night starts or the report is dismissed.

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.time.Instant

private const val MORNING_REPORT_FILE_NAME = "morning_report.json"
private const val LOG_TAG = "MorningReportStore"

/** The night state captured just before [clearNightState] ran, plus the instant the night actually ended. */
data class MorningReportSnapshot(val nightState: NightState, val endedAt: Instant)

/** Turns a morning-report snapshot into its JSON text form, the inverse of [decodeMorningReportSnapshot]. */
fun encodeMorningReportSnapshot(snapshot: MorningReportSnapshot): String {
    val json = JSONObject()
    json.put("nightState", encodeNightState(snapshot.nightState))
    json.put("endedAt", snapshot.endedAt.toString())
    return json.toString()
}

/** Parses a morning-report snapshot from JSON text, the inverse of [encodeMorningReportSnapshot]. */
fun decodeMorningReportSnapshot(text: String): MorningReportSnapshot {
    val json = JSONObject(text)
    return MorningReportSnapshot(
        nightState = decodeNightState(json.getString("nightState")),
        endedAt = Instant.parse(json.getString("endedAt"))
    )
}

/** Saves [snapshot], overwriting whatever was there before. A failure is logged; the morning report simply will not survive a restart. */
fun saveMorningReport(context: Context, snapshot: MorningReportSnapshot) {
    try {
        morningReportFile(context).writeText(encodeMorningReportSnapshot(snapshot))
    } catch (error: Exception) {
        Log.e(LOG_TAG, "failed to save the morning report snapshot", error)
    }
}

/** Reads the persisted morning-report snapshot, or null if there is none or it failed to parse. A corrupt file is deleted rather than left to fail again on every read. */
fun loadMorningReport(context: Context): MorningReportSnapshot? {
    val file = morningReportFile(context)
    if (!file.exists()) return null
    return try {
        decodeMorningReportSnapshot(file.readText())
    } catch (error: Exception) {
        Log.e(LOG_TAG, "failed to load the morning report snapshot from ${file.path}, discarding it", error)
        file.delete()
        null
    }
}

/** Deletes the persisted morning-report snapshot: called when the next night starts, and once the report has been shown and dismissed. */
fun clearMorningReport(context: Context) {
    morningReportFile(context).delete()
}

private fun morningReportFile(context: Context): File = File(context.filesDir, MORNING_REPORT_FILE_NAME)
