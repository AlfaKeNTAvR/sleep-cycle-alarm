package com.nikita.sleepcycle.night

// File purpose: the band alarm protocol's pure decision function. Gadgetbridge's SET_ALARM only logs an
// error when no slot is free - the sender is never told - so this never trusts a "set" broadcast alone: only
// a matching, enabled, EXACT-title slot in the next export counts as confirmed. Reconciliation against the
// freshly read table always runs first, before any new command is decided (rule 1): a pending request that
// is present is confirmed under whatever hour/minute the band actually holds, and a confirmed alarm that
// vanished is demoted rather than trusted. Dismissals are tracked as pending until the table shows the title
// gone (rule 2), so a lost DISMISS_ALARM is retried instead of silently leaving a stale slot occupied. A
// replacement never dismisses the alarm it is replacing until the new one is confirmed (rule 3): the band is
// never left with zero alarms because a target moved. With no table at all, the very first alarm of the
// night is still sent blind so one never fails to exist (rule 4). While the export stays broken, a target
// that moves is NEVER chased: the same export that would carry a moved target is the only source of fresh
// sleep data too, so a moving target while blind reflects nothing new, only the projected onset sliding with
// the clock. Chasing it by dismissing the pending title and setting another one blind risked the SET failing
// silently (Gadgetbridge never reports that failure), which could leave the band at zero alarms - the one
// outcome this app exists to prevent - so an existing commitment (requested or confirmed) is instead held
// exactly as it is (band_alarm_frozen), never dismissed, never retargeted, while blind. A brand-new commitment
// (nothing requested or confirmed yet) is still sent once, blind. A requested-but-unconfirmed commitment gets
// exactly one bounded retry per blind episode, after BLIND_RESEND_INTERVAL_TICKS consecutive blind ticks, in
// case the original send silently never landed (C2) - never more than that one extra SET, so a broken-export
// night can duplicate a title's slot at most once, never grow without bound. Title matching is always exact
// equality (rule 5): Gadgetbridge itself dismisses by substring, which is why
// the setup checklist (see BandAlarmMapping.kt) separately flags a foreign alarm whose title merely contains
// ours. C1: the send margin (MIN_SEND_MARGIN, 45 s) is checked against the caller's own freshly re-read
// clock, truncated to the minute the band actually stores - a physical "do we still have time to send this"
// check, deliberately smaller than and independent of the engine's own minAlarmLead (already enforced inside
// the engine itself), never a second planning rule. C3: whenever a table is available, any ENABLED slot under
// one of our titles that nothing currently references is adopted as an orphan - the trace of a SET that
// really landed just before the app was killed, before it could persist the commitment.

import com.nikita.sleepcycle.bridge.BandAlarmSlot
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** C1: below this margin before the target's local minute starts, sending is physically too late to matter. */
val MIN_SEND_MARGIN: Duration = Duration.ofSeconds(45)

/** C2: sentinel [BandAlarmCommitment.blindResendCount] value meaning this blind episode's one bounded re-send already happened - frozen for good until a real table read resets the count to 0. See BandAlarmBlindMode.kt. */
internal const val BLIND_RESEND_EXHAUSTED = -1

/** Shared with BandAlarmBlindMode.kt, which cannot see a `private` top-level value declared in this file. */
internal val ALL_BAND_ALARM_TITLES: List<String> = listOf(BAND_ALARM_TITLE_A, BAND_ALARM_TITLE_B)

/**
 * What we asked the band to carry under [title]: [hour]:[minute], (re)requested at [at]. [blindResendCount]
 * (C2) counts consecutive blind ticks since this was last (re)sent while no table was available to verify it,
 * or holds the sentinel [BLIND_RESEND_EXHAUSTED] once this blind episode's one allowed re-send already
 * happened; either way it is only meaningful while the export stays broken, and a real table read always
 * resets it back to 0.
 */
data class BandAlarmCommitment(val title: String, val hour: Int, val minute: Int, val at: Instant, val blindResendCount: Int = 0)

sealed interface BandAlarmCommand {
    data class Set(val title: String, val hour: Int, val minute: Int) : BandAlarmCommand
    data class Dismiss(val title: String) : BandAlarmCommand
}

/** What happened this tick, for logging. See the per-value comments for exactly which transition each one is. */
enum class BandAlarmOutcome {
    /** A brand-new SET went out (nothing was requested or confirmed before this tick). */
    REQUESTED,

    /** An already-pending SET was re-sent, unconfirmed, possibly retargeted because the plan moved again. */
    RESENT,

    /** A pending request was found present in this tick's table and promoted to confirmed. */
    CONFIRMED,

    /** A previously confirmed alarm is no longer present in this tick's table. */
    CONFIRMED_LOST,

    /** A DISMISS was sent or re-sent this tick and is still not confirmed gone. */
    DISMISS_PENDING,

    /** At least one pending dismissal was confirmed gone by this tick's table. */
    DISMISSED,

    /** A SET was sent with no table available to verify it against (rule 4). */
    BLIND,

    /**
     * Blind, with a requested or confirmed commitment already on file: nothing was sent, the commitment is
     * unchanged, even if the desired minute moved. Logged by the caller as `band_alarm_frozen` (mirroring how
     * [TOO_SOON] is logged outside this file, in NightOrchestrator.kt, since only the caller has the desired
     * target on hand) with the held time, the desired time, and the reason "no band alarm table available".
     */
    BLIND_FROZEN,

    /** The target's minute is not at least the configured lead ahead of the refreshed clock; nothing was sent. */
    TOO_SOON,

    /** Both of our titles are occupied in the table; a fresh SET was withheld rather than guessing which is safe. */
    BOTH_SLOTS_OCCUPIED,

    UNCHANGED
}

/** The commands to send this tick, plus the new pending/confirmed commitments and pending dismissals to persist. */
data class BandAlarmDecision(
    val commands: List<BandAlarmCommand>,
    val requestedBandAlarm: BandAlarmCommitment?,
    val confirmedBandAlarm: BandAlarmCommitment?,
    val pendingDismissTitles: Set<String>,
    val outcome: BandAlarmOutcome
)

/**
 * Decides the next band alarm commands from [desiredBandAlarm] (the engine's current `plan.bandAlarm`, or
 * null once the engine no longer wants one), what we last asked for ([requested]), what is confirmed active
 * ([confirmed]), which titles are mid-dismissal and not yet confirmed gone ([pendingDismissTitles]), and this
 * tick's freshly read [slots] - or null when no table could be read this tick (sync failed, data was stale,
 * or the export was otherwise unusable; the caller decides that, not this function). [now] must be read
 * after any I/O the caller did to get [slots], so the lead check in rule 6 is judged against a clock that is
 * actually current. [zone] is used only to turn [desiredBandAlarm] into an hour/minute the band understands.
 */
fun decideBandAlarmCommands(
    desiredBandAlarm: Instant?,
    requested: BandAlarmCommitment?,
    confirmed: BandAlarmCommitment?,
    pendingDismissTitles: Set<String>,
    slots: List<BandAlarmSlot>?,
    now: Instant,
    zone: ZoneId,
    minSendMargin: Duration = MIN_SEND_MARGIN
): BandAlarmDecision {
    val hadRequestedBefore = requested != null
    val commands = mutableListOf<BandAlarmCommand>()
    var currentRequested = requested
    var currentConfirmed = confirmed
    var currentPendingDismiss = pendingDismissTitles
    var outcome = BandAlarmOutcome.UNCHANGED
    val desiredLocalTime = desiredBandAlarm?.let { LocalTime.ofInstant(it, zone) }

    if (slots != null) {
        val (stillPresent, nowGone) = currentPendingDismiss.partition { title -> titlePresent(slots, title) }
        stillPresent.forEach { title -> commands.add(BandAlarmCommand.Dismiss(title)) }
        currentPendingDismiss = stillPresent.toSet()
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

        // C3: a slot under one of our titles that nothing above just accounted for is an orphan - the trace
        // of a SET that really landed on the band just before the app was killed, before it could persist the
        // commitment (write-ahead persistence in NightOrchestrator.kt closes the other half of this gap).
        val orphanAdoption = adoptOrphanSlots(slots, currentRequested, currentConfirmed, currentPendingDismiss, desiredLocalTime, now)
        if (orphanAdoption.outcome != null) {
            commands.addAll(orphanAdoption.commands)
            currentConfirmed = orphanAdoption.confirmedBandAlarm ?: currentConfirmed
            currentPendingDismiss = orphanAdoption.pendingDismissTitles
            outcome = outcome.orIfIdle(orphanAdoption.outcome)
        }
    }

    if (desiredBandAlarm == null) {
        listOfNotNull(currentConfirmed?.title, currentRequested?.title).distinct().forEach { title ->
            if (title !in currentPendingDismiss) {
                commands.add(BandAlarmCommand.Dismiss(title))
                currentPendingDismiss = currentPendingDismiss + title
                outcome = BandAlarmOutcome.DISMISS_PENDING
            }
        }
        return BandAlarmDecision(commands, requestedBandAlarm = null, confirmedBandAlarm = null, currentPendingDismiss, outcome)
    }

    val desiredTime = requireNotNull(desiredLocalTime) { "desiredLocalTime must be set whenever desiredBandAlarm is non-null" }
    val marginUntilTargetMinute = Duration.between(now, desiredBandAlarm.truncatedTo(ChronoUnit.MINUTES))
    if (marginUntilTargetMinute < minSendMargin) {
        return BandAlarmDecision(commands, currentRequested, currentConfirmed, currentPendingDismiss, outcome.orIfIdle(BandAlarmOutcome.TOO_SOON))
    }

    if (slots == null) {
        return decideBlindBandAlarmCommands(currentRequested, currentConfirmed, currentPendingDismiss, desiredTime, now)
    }

    val confirmedNow = currentConfirmed
    val requestedNow = currentRequested
    when {
        confirmedNow != null && requestedNow == null && matchesLocalTime(confirmedNow, desiredTime) -> {
            // Already correct; nothing to do.
        }
        confirmedNow != null && requestedNow == null -> {
            val otherTitle = nextBandAlarmTitle(confirmedNow.title)
            val blocked = otherTitle in currentPendingDismiss || titleOccupied(slots, otherTitle)
            if (blocked) {
                outcome = outcome.orIfIdle(BandAlarmOutcome.BOTH_SLOTS_OCCUPIED)
            } else {
                commands.add(BandAlarmCommand.Set(otherTitle, desiredTime.hour, desiredTime.minute))
                currentRequested = BandAlarmCommitment(otherTitle, desiredTime.hour, desiredTime.minute, now)
                outcome = outcome.orIfIdle(BandAlarmOutcome.REQUESTED)
            }
        }
        requestedNow != null -> {
            commands.add(BandAlarmCommand.Set(requestedNow.title, desiredTime.hour, desiredTime.minute))
            // A table is available again, so this send is a real, verifiable one, not blind - a fresh blind
            // episode later starts with its own bounded re-send budget rather than inheriting an exhausted one.
            currentRequested = requestedNow.copy(hour = desiredTime.hour, minute = desiredTime.minute, at = now, blindResendCount = 0)
            outcome = outcome.orIfIdle(if (hadRequestedBefore) BandAlarmOutcome.RESENT else BandAlarmOutcome.REQUESTED)
        }
        else -> {
            if (ALL_BAND_ALARM_TITLES.all { titleOccupied(slots, it) }) {
                outcome = outcome.orIfIdle(BandAlarmOutcome.BOTH_SLOTS_OCCUPIED)
            } else {
                val title = ALL_BAND_ALARM_TITLES.firstOrNull { !titleOccupied(slots, it) && it !in currentPendingDismiss }
                if (title == null) {
                    outcome = outcome.orIfIdle(BandAlarmOutcome.BOTH_SLOTS_OCCUPIED)
                } else {
                    commands.add(BandAlarmCommand.Set(title, desiredTime.hour, desiredTime.minute))
                    currentRequested = BandAlarmCommitment(title, desiredTime.hour, desiredTime.minute, now)
                    outcome = outcome.orIfIdle(BandAlarmOutcome.REQUESTED)
                }
            }
        }
    }

    return BandAlarmDecision(commands, currentRequested, currentConfirmed, currentPendingDismiss, outcome)
}

/** What [adoptOrphanSlots] found: the dismiss commands for any orphan that did not match, the (possibly newly adopted) confirmed commitment, the updated pending-dismiss set, and the outcome to report - null when nothing was orphaned. */
private data class OrphanAdoption(
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
private fun adoptOrphanSlots(
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
private fun titleOccupied(slots: List<BandAlarmSlot>, title: String): Boolean = slots.any { it.title == title }

private fun titlePresent(slots: List<BandAlarmSlot>, title: String): Boolean = titleOccupied(slots, title)

/** The enabled slot carrying [title], exactly - what counts as "the band really has this alarm" for confirmation. */
private fun findActiveSlot(slots: List<BandAlarmSlot>, title: String): BandAlarmSlot? =
    slots.firstOrNull { it.enabled && it.title == title }

private fun matchesLocalTime(commitment: BandAlarmCommitment, time: LocalTime): Boolean =
    commitment.hour == time.hour && commitment.minute == time.minute

/** [candidate] only if nothing more specific has already happened this tick; keeps a reconciliation-driven transition (confirmed, lost, dismissed) as the tick's headline over a routine follow-up action. */
private fun BandAlarmOutcome.orIfIdle(candidate: BandAlarmOutcome): BandAlarmOutcome =
    if (this == BandAlarmOutcome.UNCHANGED) candidate else this
