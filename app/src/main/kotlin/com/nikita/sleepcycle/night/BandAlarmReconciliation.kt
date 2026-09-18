package com.nikita.sleepcycle.night

// File purpose: reconciliation against this tick's freshly read band alarm table, for decideBandAlarmCommands
// (BandAlarmDecision.kt), split out to keep that file under its size budget, the same way
// BandAlarmBlindMode.kt and BandAlarmSingleSlotMode.kt are: the whole "what does the table say about what we
// believe" pass (rule 1), orphan-slot adoption (C3 - a SET that really landed just before the app was killed,
// before it could persist the commitment), the smart-wakeup-flag check (see BandAlarmMapping.kt's header),
// and the small table-lookup primitives every slot mode shares. `internal`, not `private`, on every
// declaration here - a `private` top-level declaration is file-scoped in Kotlin and would not be callable
// from BandAlarmDecision.kt at all.

import com.nikita.sleepcycle.bridge.BandAlarmSlot
import java.time.Instant
import java.time.LocalTime

/**
 * What reconciling this tick's table against our own records produced, before any new command is decided.
 * Every slot mode (alternating, single-slot, blind) starts from this. On a blind tick there is no table, so
 * it is just our own records passed through untouched.
 */
internal data class ReconciledBandAlarm(
    val commands: List<BandAlarmCommand>,
    val requested: BandAlarmCommitment?,
    val confirmed: BandAlarmCommitment?,
    val pendingDismissTitles: Set<String>,
    val outcome: BandAlarmOutcome,
    val smartWakeupWarning: BandAlarmSmartWakeupWarning?
)

/**
 * Rule 1: reconcile first, always, before any new command is decided. Re-sends a dismissal the table still
 * shows as present, demotes a confirmed alarm that has vanished, promotes a pending request the table now
 * carries (under whatever hour/minute the band actually holds, never the time we asked for), adopts or clears
 * orphan slots (C3), and reports the band's own smart-wakeup flag if it sits on one of our slots.
 */
internal fun reconcileAgainstTable(
    slots: List<BandAlarmSlot>,
    requested: BandAlarmCommitment?,
    confirmed: BandAlarmCommitment?,
    pendingDismissTitles: Set<String>,
    desiredLocalTime: LocalTime?,
    now: Instant
): ReconciledBandAlarm {
    val commands = mutableListOf<BandAlarmCommand>()
    var currentRequested = requested
    var currentConfirmed = confirmed
    var outcome = BandAlarmOutcome.UNCHANGED

    val (stillPresent, nowGone) = pendingDismissTitles.partition { title -> titlePresent(slots, title) }
    stillPresent.forEach { title -> commands.add(BandAlarmCommand.Dismiss(title)) }
    var currentPendingDismiss = stillPresent.toSet()
    if (nowGone.isNotEmpty()) outcome = outcome.orIfIdle(BandAlarmOutcome.DISMISSED)
    else if (stillPresent.isNotEmpty()) outcome = outcome.orIfIdle(BandAlarmOutcome.DISMISS_PENDING)

    val confirmedBeforeReconcile = currentConfirmed
    if (confirmedBeforeReconcile != null && !titlePresent(slots, confirmedBeforeReconcile.title)) {
        currentConfirmed = null
        outcome = BandAlarmOutcome.CONFIRMED_LOST
    }

    val pending = currentRequested
    val landedSlot = pending?.let { findActiveSlot(slots, it.title) }
    if (pending != null && landedSlot != null) {
        val promoted = pending.copy(hour = landedSlot.hour, minute = landedSlot.minute)
        val previousConfirmed = currentConfirmed
        if (previousConfirmed != null && previousConfirmed.title != promoted.title) {
            commands.add(BandAlarmCommand.Dismiss(previousConfirmed.title))
            currentPendingDismiss = currentPendingDismiss + previousConfirmed.title
        }
        currentConfirmed = promoted
        currentRequested = null
        outcome = BandAlarmOutcome.CONFIRMED
    }

    // C3: a slot under one of our titles that nothing above just accounted for is an orphan - the trace of a
    // SET that really landed on the band just before the app was killed, before it could persist the
    // commitment (write-ahead persistence in NightOrchestrator.kt closes the other half of this gap).
    val orphanAdoption = adoptOrphanSlots(slots, currentRequested, currentConfirmed, currentPendingDismiss, desiredLocalTime, now)
    if (orphanAdoption.outcome != null) {
        commands.addAll(orphanAdoption.commands)
        currentConfirmed = orphanAdoption.confirmedBandAlarm ?: currentConfirmed
        currentPendingDismiss = orphanAdoption.pendingDismissTitles
        outcome = outcome.orIfIdle(orphanAdoption.outcome)
    }

    return ReconciledBandAlarm(
        commands, currentRequested, currentConfirmed, currentPendingDismiss, outcome,
        findSmartWakeupWarning(slots, currentRequested, currentConfirmed)
    )
}

/** The band's own smart-wakeup flag is set on the slot currently holding [title] - not fixable from our side (see BandAlarmMapping.kt's header), only ever reported. [position] and [windowMinutes] are the slot's own, for the night log and the Night screen's amber line; [windowMinutes] is null when the table did not report one. */
data class BandAlarmSmartWakeupWarning(val title: String, val position: Int, val windowMinutes: Int?)

/**
 * Reconciliation: the band's own smart-wakeup flag can survive on a slot forever once Gadgetbridge's
 * third-party alarm setter reuses it (see BandAlarmMapping.kt's header), so a slot we now believe is a plain
 * SET_ALARM can silently still be a smart one, buzzing up to its own window ahead of the time we asked for.
 * Checked against the slot(s) actually backing [confirmed] and [requested] - never fixed from here, only
 * reported, since Gadgetbridge gives no way to clear it from our side.
 */
internal fun findSmartWakeupWarning(
    slots: List<BandAlarmSlot>,
    requested: BandAlarmCommitment?,
    confirmed: BandAlarmCommitment?
): BandAlarmSmartWakeupWarning? {
    val ourTitles = listOfNotNull(confirmed?.title, requested?.title)
    val smartSlot = slots.firstOrNull { it.smartWakeup && it.title != null && it.title in ourTitles } ?: return null
    val title = requireNotNull(smartSlot.title) { "filtered for a non-null title above" }
    return BandAlarmSmartWakeupWarning(title, smartSlot.position, smartSlot.smartWakeupWindowMinutes)
}

/** What [adoptOrphanSlots] found: the dismiss commands for any orphan that did not match, the (possibly newly adopted) confirmed commitment, the updated pending-dismiss set, and the outcome to report - null when nothing was orphaned. */
internal data class OrphanAdoption(
    val commands: List<BandAlarmCommand>,
    val confirmedBandAlarm: BandAlarmCommitment?,
    val pendingDismissTitles: Set<String>,
    val outcome: BandAlarmOutcome?
)

/**
 * C3: any ENABLED slot carrying exactly one of [ALL_BAND_ALARM_TITLES] that is referenced by neither
 * [currentRequested] nor [currentConfirmed] nor already tracked in [currentPendingDismiss] is an orphan -
 * nothing in this run's state accounts for it, which is exactly the trace a kill between sending a SET and
 * saving state leaves behind. A match against [desiredLocalTime] is adopted outright as confirmed; anything
 * else is dismissed and tracked pending, same as any other dismissal.
 */
internal fun adoptOrphanSlots(
    slots: List<BandAlarmSlot>,
    currentRequested: BandAlarmCommitment?,
    currentConfirmed: BandAlarmCommitment?,
    currentPendingDismiss: Set<String>,
    desiredLocalTime: LocalTime?,
    now: Instant
): OrphanAdoption {
    val referencedTitles = setOfNotNull(currentRequested?.title, currentConfirmed?.title) + currentPendingDismiss
    val commands = mutableListOf<BandAlarmCommand>()
    var confirmedBandAlarm: BandAlarmCommitment? = null
    var pendingDismissTitles = currentPendingDismiss
    var outcome: BandAlarmOutcome? = null
    ALL_BAND_ALARM_TITLES.filter { it !in referencedTitles }.forEach { title ->
        val orphan = slots.firstOrNull { it.enabled && it.title == title } ?: return@forEach
        if (desiredLocalTime != null && orphan.hour == desiredLocalTime.hour && orphan.minute == desiredLocalTime.minute) {
            confirmedBandAlarm = BandAlarmCommitment(title, orphan.hour, orphan.minute, now)
            outcome = BandAlarmOutcome.CONFIRMED
        } else {
            commands.add(BandAlarmCommand.Dismiss(title))
            pendingDismissTitles = pendingDismissTitles + title
            if (outcome == null) outcome = BandAlarmOutcome.DISMISS_PENDING
        }
    }
    return OrphanAdoption(commands, confirmedBandAlarm, pendingDismissTitles, outcome)
}

/** A slot carrying [title], exactly (never by substring - see BandAlarmMapping.kt for the substring-collision check), regardless of enabled state. */
internal fun titleOccupied(slots: List<BandAlarmSlot>, title: String): Boolean = slots.any { it.title == title }

internal fun titlePresent(slots: List<BandAlarmSlot>, title: String): Boolean = titleOccupied(slots, title)

/** The enabled slot carrying [title], exactly - what counts as "the band really has this alarm" for confirmation. */
internal fun findActiveSlot(slots: List<BandAlarmSlot>, title: String): BandAlarmSlot? =
    slots.firstOrNull { it.enabled && it.title == title }

internal fun matchesLocalTime(commitment: BandAlarmCommitment, time: LocalTime): Boolean =
    commitment.hour == time.hour && commitment.minute == time.minute

/** [candidate] only if nothing more specific has already happened this tick; keeps a reconciliation-driven transition (confirmed, lost, dismissed) as the tick's headline over a routine follow-up action. */
internal fun BandAlarmOutcome.orIfIdle(candidate: BandAlarmOutcome): BandAlarmOutcome =
    if (this == BandAlarmOutcome.UNCHANGED) candidate else this
