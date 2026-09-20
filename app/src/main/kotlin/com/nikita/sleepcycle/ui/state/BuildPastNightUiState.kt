package com.nikita.sleepcycle.ui.state

// File purpose: pure formatting of one already-parsed night log into the past-night screen's state. Reading the
// file is disk I/O and happens in the ViewModel via night.readPastNightLog.

import com.nikita.sleepcycle.night.PastNightLog
import com.nikita.sleepcycle.night.PastNightSummary
import com.nikita.sleepcycle.night.RecordedStretch
import com.nikita.sleepcycle.night.sleepLengthFor
import com.nikita.sleepcycle.ui.format.formatClockTime
import com.nikita.sleepcycle.ui.format.formatCycles
import com.nikita.sleepcycle.ui.format.formatDuration
import com.nikita.sleepcycle.ui.format.formatSleepLengthLabel
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Builds the past-night screen's state from the row the owner tapped and that night's parsed log. A night
 * whose log recorded only the total is rendered with [NightScreenContent.MorningReport.stretchDetailRecorded]
 * false, so the report shows the real total and the screen says the stretch times were never written down.
 */
fun buildPastNightUiState(row: NightLogSummary, log: PastNightLog, zone: ZoneId): PastNightUiState = PastNightUiState(
    title = row.displayName,
    isSimulated = row.isSimulated,
    report = toMorningReport(log, zone),
    deadlineTimeLabel = log.deadline?.let { formatClockTime(it, zone) },
    pickedLengthLabel = log.pickedCycles?.let { pickedLengthLabel(it) },
    recordedStretchCount = (log.summary as? PastNightSummary.TotalOnly)?.stretchCount,
)

private fun toMorningReport(log: PastNightLog, zone: ZoneId): NightScreenContent.MorningReport? = when (val summary = log.summary) {
    is PastNightSummary.Detailed -> morningReport(
        endedAt = log.endedAt,
        totalSleep = summary.totalSleep,
        wokeAt = summary.stretches.lastOrNull()?.end,
        stretches = summary.stretches.map { toStretchLine(it, zone) },
        stretchDetailRecorded = true,
        zone = zone,
    )
    is PastNightSummary.TotalOnly -> morningReport(
        endedAt = log.endedAt,
        totalSleep = summary.totalSleep,
        wokeAt = null,
        stretches = emptyList(),
        stretchDetailRecorded = false,
        zone = zone,
    )
    is PastNightSummary.Missing -> null
}

private fun morningReport(
    endedAt: Instant?,
    totalSleep: Duration,
    wokeAt: Instant?,
    stretches: List<StretchLine>,
    stretchDetailRecorded: Boolean,
    zone: ZoneId,
): NightScreenContent.MorningReport = NightScreenContent.MorningReport(
    endedAtTimeLabel = endedAt?.let { formatClockTime(it, zone) } ?: MISSING_TIME_LABEL,
    totalSleepDurationLabel = formatDuration(totalSleep),
    wokeAtTimeLabel = wokeAt?.let { formatClockTime(it, zone) },
    stretches = stretches,
    stretchDetailRecorded = stretchDetailRecorded,
)

private fun toStretchLine(stretch: RecordedStretch, zone: ZoneId): StretchLine = StretchLine(
    startTimeLabel = formatClockTime(stretch.onset, zone),
    endTimeLabel = formatClockTime(stretch.end, zone),
    durationLabel = formatDuration(Duration.between(stretch.onset, stretch.end)),
    cyclesLabel = formatCycles(stretch.cycles),
)

/**
 * The picked length as an hours label. T7: no longer takes the night's own speed - [sleepLengthFor] and
 * [formatSleepLengthLabel] are both unaffected by it now (a fast debug night comes from warping the clock,
 * never from shrinking EngineConfig's own cycle length), so a past night's picked length always reads the
 * same real hours it would today.
 */
private fun pickedLengthLabel(pickedCycles: Int): String = formatSleepLengthLabel(sleepLengthFor(pickedCycles))
