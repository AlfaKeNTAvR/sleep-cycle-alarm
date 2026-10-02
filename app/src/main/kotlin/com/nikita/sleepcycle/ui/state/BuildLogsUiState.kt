package com.nikita.sleepcycle.ui.state

// File purpose: pure formatting of an already-listed set of night log files for the Logs screen. Listing the
// files themselves is Android/disk I/O and happens in the ViewModel via night.listNightLogs.

import com.nikita.sleepcycle.night.NIGHT_LOG_SIMULATED_PREFIX
import com.nikita.sleepcycle.night.NightRatings
import com.nikita.sleepcycle.night.nightLogStartLocalTime
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val BYTES_PER_KILOBYTE = 1024.0
private const val BYTES_PER_MEGABYTE = BYTES_PER_KILOBYTE * 1024.0
private val LOG_DISPLAY_NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

/** Builds the Logs screen's state, newest first. [ratings] is each file's night ratings, read by the caller (absent or null: none). */
fun buildLogsUiState(logFiles: List<File>, ratings: Map<File, NightRatings?>, zone: ZoneId): LogsUiState =
    LogsUiState(logFiles.map { toLogSummary(it, ratings[it], zone) })

private fun toLogSummary(file: File, ratings: NightRatings?, zone: ZoneId): NightLogSummary = NightLogSummary(
    file = file,
    // Owner request, 2026-10-02: when the night started (from the file name), not when its log was last written.
    displayName = nightLogStartLocalTime(file.name)?.let(LOG_DISPLAY_NAME_FORMAT::format)
        ?: LOG_DISPLAY_NAME_FORMAT.withZone(zone).format(Instant.ofEpochMilli(file.lastModified())),
    sizeLabel = formatFileSize(file.length()),
    isSimulated = file.name.startsWith(NIGHT_LOG_SIMULATED_PREFIX),
    ratingChips = listOfNotNull(ratings?.afterEndNight?.rating, ratings?.later?.rating),
)

private fun formatFileSize(bytes: Long): String = when {
    bytes < BYTES_PER_KILOBYTE -> "$bytes B"
    bytes < BYTES_PER_MEGABYTE -> "${(bytes / BYTES_PER_KILOBYTE).toInt()} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / BYTES_PER_MEGABYTE)
}
