package com.nikita.sleepcycle.night

// File purpose: assembles the whole "is this app ready to run tonight" picture - band data freshness and the
// phone alarm's notification readiness - into plain-English lines for the setup checklist. runConnectionTest
// is a thin wrapper that joins the lines. D2/D7: the band alarm slot checks that used to live here are gone -
// there is no band alarm to set, so nothing about the band's alarm table matters to setup anymore.

import android.content.Context
import android.net.Uri
import com.nikita.sleepcycle.alarm.AlarmNotificationReadiness
import com.nikita.sleepcycle.alarm.readAlarmNotificationReadiness
import com.nikita.sleepcycle.bridge.BAND_DATA_FRESHNESS_THRESHOLD
import com.nikita.sleepcycle.bridge.BandDataResult
import com.nikita.sleepcycle.bridge.checkDataFreshness
import com.nikita.sleepcycle.bridge.syncAndReadBandData
import java.time.Duration
import java.time.Instant

private val CONNECTION_TEST_LOOKBACK: Duration = Duration.ofDays(1)

/** How urgently one setup check line needs the owner's attention, so the UI can style it without parsing English. */
enum class SetupCheckLineSeverity {
    /** Everything is fine; shown as ordinary text. */
    INFO,

    /** Worth knowing but does not block "Start night". */
    WARNING,

    /** Blocks [SetupCheckReport.isReady]; shown prominently (amber) so the owner acts on it before bed. */
    ACTION_NEEDED
}

/** One line of the setup check report, plain English plus how urgently it needs attention. */
data class SetupCheckLine(val text: String, val severity: SetupCheckLineSeverity)

/** Everything the setup checklist needs to know to decide whether tonight can start. */
data class SetupCheckReport(
    val isReady: Boolean,
    val lines: List<SetupCheckLine>
)

/**
 * Runs one sync-and-read cycle and checks data freshness and the phone alarm's notification readiness, all as
 * plain English lines. Every step is written to setup.jsonl. [isReady] requires a successful sync/export/read
 * and fresh data; the phone alarm's own readiness lines never block it (see [phoneAlarmReadinessLines]).
 */
suspend fun runSetupCheck(context: Context, deviceMac: String, exportUri: Uri, now: Instant): SetupCheckReport {
    val since = now.minus(CONNECTION_TEST_LOOKBACK)
    val result = syncAndReadBandData(context, exportUri, deviceMac, since) { event -> appendSetupLog(context, event) }
    val report = buildReport(context, result, now)
    appendSetupLog(context, NightLogEvent(now, "setup_check", mapOf("isReady" to report.isReady.toString())))
    return report
}

/** Thin wrapper over [runSetupCheck] for callers that just want the plain-English lines joined into one string. */
suspend fun runConnectionTest(context: Context, deviceMac: String, exportUri: Uri, now: Instant): String =
    runSetupCheck(context, deviceMac, exportUri, now).lines.joinToString("\n") { it.text }

private fun buildReport(context: Context, result: BandDataResult, now: Instant): SetupCheckReport {
    val readiness = readAlarmNotificationReadiness(context)
    val phoneLines = phoneAlarmReadinessLines(readiness)
    return when (result) {
        is BandDataResult.Failure -> SetupCheckReport(
            isReady = false,
            lines = listOf(SetupCheckLine("Failed at ${result.step}: ${result.cause}", SetupCheckLineSeverity.ACTION_NEEDED)) + phoneLines
        )
        is BandDataResult.Success -> buildSuccessReport(result, now, phoneLines)
    }
}

internal fun buildSuccessReport(result: BandDataResult.Success, now: Instant, phoneLines: List<SetupCheckLine>): SetupCheckReport {
    val freshness = checkDataFreshness(result.newestSampleAt, now, result.exportFileModifiedAt, previousExportFileModifiedAt = null, threshold = BAND_DATA_FRESHNESS_THRESHOLD)

    val lines = mutableListOf(
        SetupCheckLine(
            "Connected. Found ${result.segments.size} sleep segments, ${freshnessLine(result.newestSampleAt, freshness.isFresh)}.",
            if (freshness.isFresh) SetupCheckLineSeverity.INFO else SetupCheckLineSeverity.ACTION_NEEDED
        )
    )
    lines += phoneLines

    return SetupCheckReport(
        isReady = freshness.isFresh,
        lines = lines
    )
}

private fun freshnessLine(newestSampleAt: Instant?, isFresh: Boolean): String = when {
    newestSampleAt == null -> "no recent heart-rate samples"
    isFresh -> "newest heart-rate sample at $newestSampleAt"
    else -> "newest heart-rate sample at $newestSampleAt, which is stale"
}

/** Full-screen intent and notifications block the alarm outright, so a problem there is prominent too; the phone alarm still has the exact-alarm safety net either way, so neither blocks [SetupCheckReport.isReady]. */
private fun phoneAlarmReadinessLines(readiness: AlarmNotificationReadiness): List<SetupCheckLine> = buildList {
    add(
        if (readiness.canUseFullScreenIntent) SetupCheckLine("Full-screen alarms are allowed.", SetupCheckLineSeverity.INFO)
        else SetupCheckLine("Full-screen alarms are blocked in Settings; the alarm will not show over the lock screen.", SetupCheckLineSeverity.ACTION_NEEDED)
    )
    add(
        if (readiness.notificationsEnabled) SetupCheckLine("Notifications are enabled.", SetupCheckLineSeverity.INFO)
        else SetupCheckLine("Notifications are disabled; the alarm will not show its stop screen.", SetupCheckLineSeverity.ACTION_NEEDED)
    )
    if (readiness.alarmChannelBlocked) {
        add(SetupCheckLine("The alarm notification channel is blocked; re-enable it in Settings.", SetupCheckLineSeverity.ACTION_NEEDED))
    }
    if (readiness.alarmChannelDowngraded) {
        add(SetupCheckLine("The alarm notification channel was downgraded; it may not sound or show full screen.", SetupCheckLineSeverity.WARNING))
    }
}
