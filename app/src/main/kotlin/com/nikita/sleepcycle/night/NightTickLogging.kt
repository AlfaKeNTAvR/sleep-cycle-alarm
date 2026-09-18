package com.nikita.sleepcycle.night

// File purpose: everything runNightTick logs about the data it read and the band alarm commands it sent -
// split out of NightOrchestrator.kt to keep that file focused on the tick's own control flow. Answers the
// three field questions from the reviews: does the band publish sleep marks mid-night (data/segments),
// did a band alarm command really land (band_alarm_command/band_alarm_table/band_alarm_confirmed), and what
// did the plan actually decide and why (plan).

import android.content.Context
import com.nikita.sleepcycle.bridge.BandAlarmSlot
import com.nikita.sleepcycle.engine.AlarmPlan
import com.nikita.sleepcycle.engine.SleepSegment
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * Formats a tick's `scheduledFor`/`receivedAt` log field. Both are null exactly for a tick not triggered by
 * the exact tick alarm (the night's very first tick, or an immediate UI-requested one, e.g. opening the Night
 * screen) - a genuine absence, not a bug, so it reads as "immediate" rather than an empty string that looks
 * like missing data when scanning a night log. `internal`, not `private`, so it is JVM-testable directly.
 */
internal fun formatTickTimeField(instant: Instant?): String = instant?.toString() ?: "immediate"

/** The "data" event only lists segments when the list changed since last tick (C2); each newly-seen segment carries firstSeenAt. `source` says whether the segments came from the simulator or the band (debug mode). The "plan" event carries every field a field question needs, per the reason sentence (C3). */
fun logDataAndPlan(context: Context, state: NightState, outcome: SyncOutcome, plan: AlarmPlan, now: Instant) {
    val debugNight = state.debugOptions.isAnyEnabled
    val sleepState = buildNightEngineView(state.copy(lastSegments = outcome.segments), now).sleepState
    val segmentsChanged = outcome.segments != state.lastSegments
    val dataFields = mutableMapOf(
        "segmentCount" to outcome.segments.size.toString(),
        "sleepState" to sleepState.name,
        "newestSampleAt" to (outcome.newestSampleAt?.toString() ?: ""),
        "exportFileModifiedAt" to (outcome.exportFileModifiedAt?.toString() ?: ""),
        "segmentsChanged" to segmentsChanged.toString(),
        "source" to outcome.source.name.lowercase()
    )
    if (segmentsChanged) {
        dataFields["segments"] = encodeSegmentsForLog(outcome.segments, state.lastSegments, now)
    }
    appendNightLog(context, state.startedAt, NightLogEvent(now, "data", dataFields), debugNight)

    appendNightLog(
        context, state.startedAt,
        NightLogEvent(
            now, "plan",
            mapOf(
                "mode" to plan.mode.name,
                "bandAlarm" to (plan.bandAlarm?.toString() ?: ""),
                "phoneAlarm" to (plan.phoneAlarm?.toString() ?: ""),
                "cycles" to plan.cycles.toString(),
                // The two numbers rule 2's night total is decided from: how much sleep was already had, and
                // how many whole cycles that left owed BEFORE any deadline cap. Fields, not just prose in
                // `reason`, because together with `cycles` they are what says whether the total or the
                // deadline bound this plan.
                "sleptSoFarMinutes" to plan.sleptSoFar.toMinutes().toString(),
                "owedCycles" to plan.owedCycles.toString(),
                "referenceOnset" to (plan.referenceOnset?.toString() ?: ""),
                "onsetIsProjected" to plan.onsetIsProjected.toString(),
                "overdueSince" to (plan.overdueSince?.toString() ?: ""),
                "reason" to plan.reason
            )
        ),
        debugNight
    )
}

private fun encodeSegmentsForLog(segments: List<SleepSegment>, previousSegments: List<SleepSegment>, now: Instant): String {
    val previousSet = previousSegments.toSet()
    val array = JSONArray()
    segments.forEach { segment ->
        array.put(
            JSONObject().apply {
                put("start", segment.start.toString())
                put("end", segment.end.toString())
                put("kind", segment.kind.name)
                if (segment !in previousSet) put("firstSeenAt", now.toString())
            }
        )
    }
    return array.toString()
}

/**
 * Sends every command through [sendBandAlarmCommand] (a no-op in dry run) and logs each one (type, title,
 * time, whether it was sent blind, and `dryRun` - see DebugBandCommandSending.kt), then the table read this
 * tick if any, and a confirmation's latency since the request (C4).
 */
fun applyBandAlarmDecision(
    context: Context,
    state: NightState,
    deviceMac: String,
    decision: BandAlarmDecision,
    slots: List<BandAlarmSlot>?,
    now: Instant
) {
    val debugNight = state.debugOptions.isAnyEnabled
    val dryRun = state.debugOptions.bandCommandMode == BandCommandMode.DRY_RUN
    val blind = decision.outcome == BandAlarmOutcome.BLIND
    decision.commands.forEach { command ->
        sendBandAlarmCommand(context, state.debugOptions, deviceMac, command)
        val fields = when (command) {
            is BandAlarmCommand.Set -> mapOf("type" to "SET", "title" to command.title, "time" to "%02d:%02d".format(command.hour, command.minute), "blind" to blind.toString())
            is BandAlarmCommand.Dismiss -> mapOf("type" to "DISMISS", "title" to command.title, "time" to "", "blind" to (slots == null).toString())
        }
        appendNightLog(context, state.startedAt, NightLogEvent(now, "band_alarm_command", fields + ("dryRun" to dryRun.toString())), debugNight)
    }
    if (slots != null) {
        appendNightLog(context, state.startedAt, NightLogEvent(now, "band_alarm_table", mapOf("rows" to encodeBandAlarmSlotsForLog(slots))), debugNight)
    }
    // Which protocol this tick ran (BandAlarmSlotMode.kt), logged every tick: in single-slot mode the band is
    // briefly without an alarm on every move, so the log must say plainly which mode produced each command.
    appendNightLog(
        context, state.startedAt,
        NightLogEvent(
            now, "band_alarm_mode",
            mapOf(
                "mode" to decision.slotMode.name.lowercase(),
                "resendsUsed" to decision.singleSlotResendsUsed.toString(),
                "resendLimit" to MAX_SINGLE_SLOT_RESENDS_PER_NIGHT.toString()
            )
        ),
        debugNight
    )
    outcomeLogEvent(decision, now)?.let { appendNightLog(context, state.startedAt, it, debugNight) }
    // Item [smart-wakeup]: logged every tick this is still true (BandAlarmDecision.kt never fixes it, only
    // reports it), regardless of decision.outcome, since another transition (e.g. RESENT) can be this tick's
    // headline while the smart flag remains a live problem underneath it.
    decision.smartWakeupWarning?.let { warning ->
        val windowClause = warning.windowMinutes?.let { "a $it minute window" } ?: "an unknown window"
        appendNightLog(
            context, state.startedAt,
            NightLogEvent(
                now, "error",
                mapOf(
                    "step" to "band_alarm",
                    "cause" to "slot ${warning.position} holding \"${warning.title}\" still has the band's own smart-wakeup flag set, with $windowClause"
                )
            ),
            debugNight
        )
    }
}

/**
 * `internal`, not `private`, so it is JVM-testable directly without a Context. C5: [BandAlarmOutcome.DISMISS_PENDING]
 * - the expected one-tick lag while alternating titles, waiting for the OTHER title's dismissal to be
 * confirmed gone - is logged as info (`band_alarm_waiting`), never `error`: it is a known, self-explaining
 * wait, not something that needs the owner's attention.
 */
internal fun outcomeLogEvent(decision: BandAlarmDecision, now: Instant): NightLogEvent? = when (decision.outcome) {
    BandAlarmOutcome.CONFIRMED -> {
        val confirmed = decision.confirmedBandAlarm
        NightLogEvent(
            now, "band_alarm_confirmed",
            mapOf(
                "title" to (confirmed?.title ?: ""),
                "time" to (confirmed?.let { "%02d:%02d".format(it.hour, it.minute) } ?: ""),
                "secondsSinceRequest" to (confirmed?.let { Duration.between(it.at, now).seconds.toString() } ?: "")
            )
        )
    }
    BandAlarmOutcome.CONFIRMED_LOST -> NightLogEvent(now, "band_alarm_missing", mapOf("cause" to "a previously confirmed band alarm is no longer present in the table"))
    // Single-slot self-healing: the SET we are still waiting on is not in the table, so it never landed.
    // Re-sent on this same tick rather than waiting for the wake time to move again.
    BandAlarmOutcome.MISSING_RESENT -> NightLogEvent(
        now, "band_alarm_missing",
        mapOf(
            "cause" to "the pending band alarm is not in the table; re-sending it now",
            "title" to (decision.requestedBandAlarm?.title ?: ""),
            "resendsUsed" to decision.singleSlotResendsUsed.toString()
        )
    )
    BandAlarmOutcome.RESEND_LIMIT_REACHED -> NightLogEvent(
        now, "error",
        mapOf(
            "step" to "band_alarm",
            // Never claims a phone alarm exists: on a night with no deadline and no backup there is none, and
            // saying otherwise is exactly the contradiction the Night screen's own wording had.
            "cause" to "tonight's single-slot re-send limit ($MAX_SINGLE_SLOT_RESENDS_PER_NIGHT) is spent; " +
                "not re-sending again - whatever phone alarm this night has is the only thing left"
        )
    )
    // The one place a dismissal precedes a confirmed replacement: with a single usable slot there is nowhere
    // else to put the new time, so the band has no alarm between these two commands.
    BandAlarmOutcome.MOVED_IN_ONE_SLOT -> NightLogEvent(
        now, "band_alarm_single_slot_move",
        mapOf(
            "title" to (decision.requestedBandAlarm?.title ?: ""),
            "time" to (decision.requestedBandAlarm?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "")
        )
    )
    BandAlarmOutcome.NO_FREE_SLOT -> NightLogEvent(now, "error", mapOf("step" to "band_alarm", "cause" to "no band alarm slot in the table can take one of our titles; withholding a new SET"))
    BandAlarmOutcome.DISMISSED -> NightLogEvent(now, "band_alarm_dismissed", mapOf("remainingPending" to decision.pendingDismissTitles.size.toString()))
    BandAlarmOutcome.DISMISS_PENDING -> NightLogEvent(now, "band_alarm_waiting", mapOf("pending" to decision.pendingDismissTitles.joinToString(",")))
    // C1: TOO_SOON is logged separately in NightOrchestrator.resolveBandAlarmState, at info with both times -
    // that call site is the only one with the target AND the refreshed clock both on hand. BLIND_FROZEN is
    // logged there too (band_alarm_frozen, with the held time and the desired target), for the same reason.
    BandAlarmOutcome.REQUESTED, BandAlarmOutcome.RESENT, BandAlarmOutcome.BLIND, BandAlarmOutcome.UNCHANGED,
    BandAlarmOutcome.TOO_SOON, BandAlarmOutcome.BLIND_FROZEN -> null
}

private fun encodeBandAlarmSlotsForLog(slots: List<BandAlarmSlot>): String {
    val array = JSONArray()
    slots.forEach { slot ->
        array.put(
            JSONObject().apply {
                put("position", slot.position)
                put("enabled", slot.enabled)
                put("time", "%02d:%02d".format(slot.hour, slot.minute))
                put("title", slot.title ?: "")
            }
        )
    }
    return array.toString()
}
