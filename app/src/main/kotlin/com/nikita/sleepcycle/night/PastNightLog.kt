package com.nikita.sleepcycle.night

// File purpose: the night log read back as history. Writes the whole night summary into the night_end event as
// structured fields, and parses a saved log file back into what the Logs screen needs to redraw that night's
// morning report. Everything here is pure JVM (the caller hands in the file's lines), so it is unit testable.
//
// Nights logged before night_end carried structured fields only ever wrote one human-readable line,
// `total=PT6H50M stretches=5`. Those are parsed back as [PastNightSummary.TotalOnly]: the total and the count
// are real, the per-stretch times were never written down, and the screen says so rather than inventing them.

import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.NightSummary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Locale

private const val NIGHT_START_EVENT_TYPE = "night_start"
private const val NIGHT_END_EVENT_TYPE = "night_end"

private const val SUMMARY_TEXT_FIELD = "summary"
private const val TOTAL_SLEEP_FIELD = "totalSleep"
private const val STRETCHES_FIELD = "stretches"
private const val DEADLINE_FIELD = "deadline"
private const val PICKED_CYCLES_FIELD = "pickedCycles"
private const val SPEED_FIELD = "speed"

private const val STRETCH_ONSET_KEY = "onset"
private const val STRETCH_END_KEY = "end"
private const val STRETCH_CYCLES_KEY = "cycles"

/** What night_start and night_end both write when the night had no deadline. */
private const val NO_DEADLINE_FIELD_VALUE = "none"
private const val CYCLES_FORMAT = "%.1f"

/** The pre-structured night_end's one field, e.g. `total=PT6H50M stretches=5`. */
private val LEGACY_SUMMARY_PATTERN = Regex("""total=(\S+)\s+stretches=(\d+)""")

/** One sleep stretch as the night_end event recorded it: its span, and its cycle count to one decimal. */
data class RecordedStretch(val onset: Instant, val end: Instant, val cycles: Double)

/** How much of a finished night its log actually preserved. */
sealed interface PastNightSummary {
    /** Every stretch, from a night_end written with structured fields. */
    data class Detailed(val totalSleep: Duration, val stretches: List<RecordedStretch>) : PastNightSummary

    /** An older night_end: the total slept and how many stretches there were, and nothing more. */
    data class TotalOnly(val totalSleep: Duration, val stretchCount: Int) : PastNightSummary

    /** No usable night_end in the log: the night was never ended, so no summary was ever written. */
    data object Missing : PastNightSummary
}

/**
 * One saved night log read back. [deadline] and [pickedCycles] come from night_end when it carries them (every
 * night logged since this file existed) and from night_start otherwise - which is what lets an older log still
 * show the deadline and picked length it ran under. [speed] only ever appears on night_start.
 */
data class PastNightLog(
    val summary: PastNightSummary,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val deadline: Instant?,
    val pickedCycles: Int?,
    /** T7: the simulated-clock speed that night ran at (1 = real time), read from night_start's `speed` field. A log written before T7 (or one that still carries the old `fastNight` boolean) has no such field and reads as speed 1 - the least surprising default, and per T7's own instruction there is no fastNight-to-speed fallback to keep. */
    val speed: Int,
)

/**
 * The night_end event's fields: the human-readable summary line the log has always carried, plus the whole
 * summary and the night's settings as structured fields, so a past night can be redrawn from its log alone.
 */
fun encodeNightEndFields(summary: NightSummary, settings: NightSettings): Map<String, String> = mapOf(
    SUMMARY_TEXT_FIELD to nightEndSummaryText(summary),
    TOTAL_SLEEP_FIELD to summary.totalSleep.toString(),
    STRETCHES_FIELD to encodeRecordedStretches(summary).toString(),
    DEADLINE_FIELD to (settings.deadline?.toString() ?: NO_DEADLINE_FIELD_VALUE),
    PICKED_CYCLES_FIELD to settings.pickedCycles.toString(),
)

/** The one-line human-readable summary, unchanged from before night_end carried structured fields. */
private fun nightEndSummaryText(summary: NightSummary): String =
    "total=${summary.totalSleep} stretches=${summary.stretches.size}"

private fun encodeRecordedStretches(summary: NightSummary): JSONArray {
    val array = JSONArray()
    summary.stretches.forEach { stretch ->
        array.put(
            JSONObject().apply {
                put(STRETCH_ONSET_KEY, stretch.onset.toString())
                put(STRETCH_END_KEY, stretch.end.toString())
                put(STRETCH_CYCLES_KEY, String.format(Locale.US, CYCLES_FORMAT, stretch.cycles))
            }
        )
    }
    return array
}

/** Reads one saved night log file, or an empty log (everything unknown) if it cannot be read at all. */
fun readPastNightLog(file: File): PastNightLog = parsePastNightLog(
    try {
        file.readLines()
    } catch (error: Exception) {
        emptyList()
    }
)

/**
 * Parses a night log's lines into what the Logs screen needs. Only night_start and night_end carry anything
 * about the night as a whole; every other line, and every line that does not parse, is skipped. The LAST
 * event of each type wins, so a log that somehow holds two is read the way the night actually ended.
 */
fun parsePastNightLog(lines: List<String>): PastNightLog {
    var startEvent: NightLogEvent? = null
    var endEvent: NightLogEvent? = null
    lines.forEach { line ->
        val event = parseNightLogLine(line) ?: return@forEach
        when (event.type) {
            NIGHT_START_EVENT_TYPE -> startEvent = event
            NIGHT_END_EVENT_TYPE -> endEvent = event
            else -> Unit
        }
    }
    val startFields = startEvent?.fields ?: emptyMap()
    val endFields = endEvent?.fields ?: emptyMap()
    return PastNightLog(
        summary = readSummary(endEvent?.fields),
        startedAt = startEvent?.at,
        endedAt = endEvent?.at,
        deadline = readDeadline(endFields) ?: readDeadline(startFields),
        pickedCycles = endFields[PICKED_CYCLES_FIELD]?.toIntOrNull() ?: startFields[PICKED_CYCLES_FIELD]?.toIntOrNull(),
        speed = readSpeed(startFields),
    )
}

/** T7: night_start's own `speed` field; absent (an older log, or one written before T7) reads as speed 1. */
private fun readSpeed(startFields: Map<String, String>): Int = startFields[SPEED_FIELD]?.toIntOrNull() ?: 1

/** Structured fields first; a night_end that has only the old text line falls back to what that line says. */
private fun readSummary(endFields: Map<String, String>?): PastNightSummary {
    if (endFields == null) return PastNightSummary.Missing
    val totalSleep = parseDurationOrNull(endFields[TOTAL_SLEEP_FIELD])
    val stretches = parseRecordedStretchesOrNull(endFields[STRETCHES_FIELD])
    if (totalSleep != null && stretches != null) return PastNightSummary.Detailed(totalSleep, stretches)
    return parseLegacySummaryText(endFields[SUMMARY_TEXT_FIELD]) ?: PastNightSummary.Missing
}

/** The total and the stretch count of a night logged before night_end carried structured fields. Null when even that line is absent or malformed. */
private fun parseLegacySummaryText(text: String?): PastNightSummary.TotalOnly? {
    val match = LEGACY_SUMMARY_PATTERN.find(text ?: return null) ?: return null
    val totalSleep = parseDurationOrNull(match.groupValues[1]) ?: return null
    val stretchCount = match.groupValues[2].toIntOrNull() ?: return null
    return PastNightSummary.TotalOnly(totalSleep, stretchCount)
}

/** Null for an absent or malformed array, so the caller can fall back to the old text line instead of showing an empty night. */
private fun parseRecordedStretchesOrNull(text: String?): List<RecordedStretch>? {
    if (text == null) return null
    return try {
        val array = JSONArray(text)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            RecordedStretch(
                onset = Instant.parse(item.getString(STRETCH_ONSET_KEY)),
                end = Instant.parse(item.getString(STRETCH_END_KEY)),
                cycles = item.getString(STRETCH_CYCLES_KEY).toDouble(),
            )
        }
    } catch (error: Exception) {
        null
    }
}

private fun readDeadline(fields: Map<String, String>): Instant? {
    val text = fields[DEADLINE_FIELD] ?: return null
    if (text == NO_DEADLINE_FIELD_VALUE) return null
    return try {
        Instant.parse(text)
    } catch (error: Exception) {
        null
    }
}

private fun parseDurationOrNull(text: String?): Duration? = try {
    text?.let(Duration::parse)
} catch (error: Exception) {
    null
}
