package com.nikita.sleepcycle.night

// File purpose: assembles the whole "is this app ready to run tonight" picture - band alarm slot
// availability, band data freshness, and the phone alarm's notification readiness - into plain-English
// lines for the setup checklist. runConnectionTest is a thin wrapper that joins the lines.

import android.content.Context
import android.net.Uri
import com.nikita.sleepcycle.alarm.AlarmNotificationReadiness
import com.nikita.sleepcycle.alarm.readAlarmNotificationReadiness
import com.nikita.sleepcycle.bridge.BAND_DATA_FRESHNESS_THRESHOLD
import com.nikita.sleepcycle.bridge.BandAlarmSlot
import com.nikita.sleepcycle.bridge.BandDataResult
import com.nikita.sleepcycle.bridge.checkDataFreshness
import com.nikita.sleepcycle.bridge.countFreeBandAlarmSlots
import com.nikita.sleepcycle.bridge.countUsableBandAlarmSlots
import com.nikita.sleepcycle.bridge.describeOtherBandAlarm
import com.nikita.sleepcycle.bridge.findTitlesConflictingWithOurs
import com.nikita.sleepcycle.bridge.listOtherEnabledBandAlarms
import com.nikita.sleepcycle.bridge.listPoachableSmartWakeupBandAlarmSlots
import com.nikita.sleepcycle.bridge.syncAndReadBandData
import java.time.Duration
import java.time.Instant

private val CONNECTION_TEST_LOOKBACK: Duration = Duration.ofDays(1)

/**
 * One usable slot is all a night ever needs, and all it ever uses: the app holds one title in one slot all
 * night and moves the wake time by clearing that slot and setting it again in the same tick
 * (BandAlarmSingleSlotMode.kt). Below this, nothing can be set at all, which is the only slot situation that
 * blocks "Start night". "Usable" is [countUsableBandAlarmSlots]'s definition and nothing looser: a disabled
 * slot still carrying our own title is NOT usable, because Gadgetbridge's picker skips it and nothing ever
 * clears it.
 */
private const val MIN_USABLE_BAND_ALARM_SLOTS = 1

/** How urgently one setup check line needs the owner's attention, so the UI can style it without parsing English. */
enum class SetupCheckLineSeverity {
    /** Everything is fine; shown as ordinary text. */
    INFO,

    /** Worth knowing but does not block "Start night", e.g. another band alarm that will still ring regardless. */
    WARNING,

    /** Blocks [SetupCheckReport.isReady]; shown prominently (amber) so the owner acts on it before bed. */
    ACTION_NEEDED
}

/** One line of the setup check report, plain English plus how urgently it needs attention. */
data class SetupCheckLine(val text: String, val severity: SetupCheckLineSeverity)

/** Everything the setup checklist needs to know to decide whether tonight can start. [usableBandAlarmSlots] is [countUsableBandAlarmSlots]'s count, persisted by the caller and shown on the checklist; only one of them is ever used. */
data class SetupCheckReport(
    val isReady: Boolean,
    val lines: List<SetupCheckLine>,
    val freeBandAlarmSlots: Int,
    val otherEnabledBandAlarms: List<String>,
    val usableBandAlarmSlots: Int = 0
)

/**
 * Runs one sync-and-read cycle, checks band alarm slot availability and data freshness, and reports the
 * phone alarm's notification readiness, all as plain English lines. Every step is written to setup.jsonl.
 * [isReady] requires a successful sync/export/read, fresh data, and at least [MIN_USABLE_BAND_ALARM_SLOTS]
 * band alarm slots this app could actually put an alarm into.
 */
suspend fun runSetupCheck(context: Context, deviceMac: String, exportUri: Uri, now: Instant): SetupCheckReport {
    val since = now.minus(CONNECTION_TEST_LOOKBACK)
    val result = syncAndReadBandData(context, exportUri, deviceMac, since) { event -> appendSetupLog(context, event) }
    val report = buildReport(context, result, now)
    appendSetupLog(
        context,
        NightLogEvent(now, "setup_check", mapOf("isReady" to report.isReady.toString(), "freeBandAlarmSlots" to report.freeBandAlarmSlots.toString()))
    )
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
            lines = listOf(SetupCheckLine("Failed at ${result.step}: ${result.cause}", SetupCheckLineSeverity.ACTION_NEEDED)) + phoneLines,
            freeBandAlarmSlots = 0,
            otherEnabledBandAlarms = emptyList()
        )
        is BandDataResult.Success -> buildSuccessReport(result, now, phoneLines)
    }
}

internal fun buildSuccessReport(result: BandDataResult.Success, now: Instant, phoneLines: List<SetupCheckLine>): SetupCheckReport {
    val freshness = checkDataFreshness(result.newestSampleAt, now, result.exportFileModifiedAt, previousExportFileModifiedAt = null, threshold = BAND_DATA_FRESHNESS_THRESHOLD)
    val freeSlots = countFreeBandAlarmSlots(result.bandAlarms)
    val usableSlots = countUsableBandAlarmSlots(result.bandAlarms, OUR_BAND_ALARM_TITLES)
    val otherAlarms = listOtherEnabledBandAlarms(result.bandAlarms, OUR_BAND_ALARM_TITLES).map(::describeOtherBandAlarm)
    val poachableSmartSlots = listPoachableSmartWakeupBandAlarmSlots(result.bandAlarms)
    val slotsShort = usableSlots < MIN_USABLE_BAND_ALARM_SLOTS

    val lines = mutableListOf(
        SetupCheckLine(
            "Connected. Found ${result.segments.size} sleep segments, ${freshnessLine(result.newestSampleAt, freshness.isFresh)}.",
            if (freshness.isFresh) SetupCheckLineSeverity.INFO else SetupCheckLineSeverity.ACTION_NEEDED
        ),
        SetupCheckLine(
            slotAvailabilityLine(freeSlots, usableSlots),
            if (slotsShort) SetupCheckLineSeverity.ACTION_NEEDED else SetupCheckLineSeverity.INFO
        )
    )
    if (otherAlarms.isNotEmpty()) {
        lines += SetupCheckLine(
            "Other band alarms will still ring regardless: ${otherAlarms.joinToString(", ")}.",
            SetupCheckLineSeverity.WARNING
        )
    }
    val conflictingTitles = findTitlesConflictingWithOurs(result.bandAlarms, OUR_BAND_ALARM_TITLES).map(::describeOtherBandAlarm)
    if (conflictingTitles.isNotEmpty()) {
        lines += SetupCheckLine(
            "Rename or clear these before bed - dismissing our own alarm would clear them too: ${conflictingTitles.joinToString(", ")}.",
            SetupCheckLineSeverity.ACTION_NEEDED
        )
    }
    poachableSmartSlots.forEach { slot ->
        lines += SetupCheckLine(describeSmartWakeupActionNeeded(slot), SetupCheckLineSeverity.ACTION_NEEDED)
    }
    lines += phoneLines

    return SetupCheckReport(
        isReady = freshness.isFresh && !slotsShort && conflictingTitles.isEmpty() && poachableSmartSlots.isEmpty(),
        lines = lines,
        freeBandAlarmSlots = freeSlots,
        otherEnabledBandAlarms = otherAlarms,
        usableBandAlarmSlots = usableSlots
    )
}

/**
 * Plain-English ACTION_NEEDED line for one band alarm slot that is still a poachable smart-wakeup slot
 * (disabled, untitled - see [listPoachableSmartWakeupBandAlarmSlots]). [slot]'s own [BandAlarmSlot.position]
 * is 0-indexed in the exported table; Gadgetbridge's alarm list shows no number of its own, so this uses
 * position + 1 to name the slot the way the owner counts it from the top of that list. The fix is never
 * "turn off smart wakeup" - on the owner's Honor Band 5 that checkbox is forced on for this slot and cannot be
 * unchecked (verified against Gadgetbridge's own HuaweiCoordinator.forcedSmartWakeup) - so the only safe move
 * is to park the slot with a title and leave it disabled, so Gadgetbridge's own picker (first disabled AND
 * untitled) skips over it for good.
 */
private fun describeSmartWakeupActionNeeded(slot: BandAlarmSlot): String {
    val displayNumber = slot.position + 1
    return "Band alarm $displayNumber in Gadgetbridge is the band's own smart alarm and cannot be made a " +
        "normal one. Give it any title (for example \"Smart\") and leave it switched off, so it is never used " +
        "for your wake-up. Then clear the title of another, switched-off alarm instead."
}

private fun freshnessLine(newestSampleAt: Instant?, isFresh: Boolean): String = when {
    newestSampleAt == null -> "no recent heart-rate samples"
    isFresh -> "newest heart-rate sample at $newestSampleAt"
    else -> "newest heart-rate sample at $newestSampleAt, which is stale"
}

private fun slotAvailabilityLine(freeSlots: Int, usableSlots: Int): String =
    if (usableSlots >= MIN_USABLE_BAND_ALARM_SLOTS) {
        "$freeSlots band alarm slot(s) free, $usableSlots usable tonight."
    } else {
        val short = MIN_USABLE_BAND_ALARM_SLOTS - usableSlots
        "No band alarm can be set: in Gadgetbridge open the band's alarms and clear the title of $short more " +
            "switched-off alarm(s)."
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
