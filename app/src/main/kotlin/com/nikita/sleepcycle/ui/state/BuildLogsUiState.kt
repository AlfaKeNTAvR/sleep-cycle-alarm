package com.nikita.sleepcycle.ui.state

// File purpose: pure formatting of an already-listed set of night log files for the Logs screen. Listing the
// files themselves is Android/disk I/O and happens in the ViewModel via night.listNightLogs.

import com.nikita.sleepcycle.night.NIGHT_LOG_SIMULATED_PREFIX
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val BYTES_PER_KILOBYTE = 1024.0
private const val BYTES_PER_MEGABYTE = BYTES_PER_KILOBYTE * 1024.0
private val LOG_DISPLAY_NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

/** Builds the Logs screen's state, newest first. */
fun buildLogsUiState(logFiles: List<File>, zone: ZoneId): LogsUiState =
    LogsUiState(logFiles.map { toLogSummary(it, zone) })

private fun toLogSummary(file: File, zone: ZoneId): NightLogSummary = NightLogSummary(
    file = file,
    displayName = LOG_DISPLAY_NAME_FORMAT.withZone(zone).format(Instant.ofEpochMilli(file.lastModified())),
    sizeLabel = formatFileSize(file.length()),
    isSimulated = file.name.startsWith(NIGHT_LOG_SIMULATED_PREFIX),
)

private fun formatFileSize(bytes: Long): String = when {
    bytes < BYTES_PER_KILOBYTE -> "$bytes B"
    bytes < BYTES_PER_MEGABYTE -> "${(bytes / BYTES_PER_KILOBYTE).toInt()} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / BYTES_PER_MEGABYTE)
}
