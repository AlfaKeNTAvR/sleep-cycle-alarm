package com.nikita.sleepcycle.night

// File purpose: the band alarm protocol's pure decision function. Gadgetbridge's SET_ALARM only logs an
// error when no slot is free - the sender is never told - so this never trusts a "set" broadcast alone: only
// a matching, enabled, EXACT-title slot in the next export counts as confirmed. Reconciliation against the
// freshly read table always runs first, before any new command is decided (rule 1): a pending request that
// is present is confirmed under whatever hour/minute the band actually holds, and a confirmed alarm that
// vanished is demoted rather than trusted. Dismissals are tracked as pending until the table shows the title
// gone (rule 2), so a lost DISMISS_ALARM is retried instead of silently leaving a stale slot occupied. A
// replacement never dismisses the alarm it is replacing until the new one is confirmed (rule 3): the band is
// never left with zero alarms because a target moved. The ONE exception is single-slot mode
// (BandAlarmSingleSlotMode.kt), chosen only when this tick's own table proves there is no second usable slot
// to alternate into - there, a move is DISMISS then SET under the same title, in that order, in one tick.
// Which protocol runs is decided from the table alone, never from a setting (BandAlarmSlotMode.kt). With no table at all, the very first alarm of the
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

// Item [smart-wakeup]: Gadgetbridge's DeviceAlarmReceiver.updateAlarm never touches SMART_WAKEUP (see
// BandAlarmMapping.kt's header), so a slot can keep that flag forever even after we clear its title and reuse
// it. If the slot currently holding one of OUR titles turns out to carry it, that is not something this file
// can fix - only report, every tick it is true, via [BandAlarmDecision.smartWakeupWarning] - see
// NightTickLogging.kt (the night-log error) and NightUiState.kt (the Night screen's amber line).

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
     * Single-slot mode only (BandAlarmSingleSlotMode.kt): the one confirmed title was dismissed and re-set to
     * the new time in the same tick, because the table proved there is no second slot to alternate into.
     */
    MOVED_IN_ONE_SLOT,

    /**
     * Single-slot mode only: a pending SET that this tick's table does not show was re-sent at once rather
     * than waiting for the target to move. Logged as `band_alarm_missing`, like a lost confirmation.
     */
    MISSING_RESENT,

    /** Single-slot mode only: [MAX_SINGLE_SLOT_RESENDS_PER_NIGHT] is spent for tonight; nothing was sent. */
    RESEND_LIMIT_REACHED,

    /**
     * Blind, with a requested or confirmed commitment already on file: nothing was sent, the commitment is
     * unchanged, even if the desired minute moved. Logged by the caller as `band_alarm_frozen` (mirroring how
     * [TOO_SOON] is logged outside this file, in NightOrchestrator.kt, since only the caller has the desired
     * target on hand) with the held time, the desired time, and the reason "no band alarm table available".
     */
    BLIND_FROZEN,

    /** The target's minute is not at least the configured lead ahead of the refreshed clock; nothing was sent. */
    TOO_SOON,

    /** No slot the table shows could take a fresh SET under one of our titles; it was withheld rather than guessing. */
    NO_FREE_SLOT,

    UNCHANGED
}

/**
 * The commands to send this tick, plus the new pending/confirmed commitments and pending dismissals to
 * persist. [smartWakeupWarning] is null exactly when no table was available this tick (blind mode never
 * resolves it) or no our-titled slot carries the flag; a real table read overwrites it fresh, never merges
 * with a previous tick's. [slotMode] is which protocol this tick ran (see [chooseBandAlarmSlotMode]), for the
 * night log and the Night screen. [singleSlotResendsUsed] is the night's running count of single-slot bounded
 * re-sends, to persist and feed back in next tick.
 */
data class BandAlarmDecision(
    val commands: List<BandAlarmCommand>,
    val requestedBandAlarm: BandAlarmCommitment?,
    val confirmedBandAlarm: BandAlarmCommitment?,
    val pendingDismissTitles: Set<String>,
    val outcome: BandAlarmOutcome,
    val smartWakeupWarning: BandAlarmSmartWakeupWarning? = null,
    val slotMode: BandAlarmSlotMode = BandAlarmSlotMode.ALTERNATING_TITLES,
    val singleSlotResendsUsed: Int = 0
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
    minSendMargin: Duration = MIN_SEND_MARGIN,
    singleSlotResendsUsed: Int = 0
): BandAlarmDecision {
    val hadRequestedBefore = requested != null
    val desiredLocalTime = desiredBandAlarm?.let { LocalTime.ofInstant(it, zone) }
    val slotMode = chooseBandAlarmSlotMode(slots)
    // Rule 1: reconciliation against the freshly read table always runs first, before any new command is
    // decided. With no table there is nothing to reconcile against, so our own records pass through.
    val reconciled = if (slots == null) {
        ReconciledBandAlarm(emptyList(), requested, confirmed, pendingDismissTitles, BandAlarmOutcome.UNCHANGED, null)
    } else {
        reconcileAgainstTable(slots, requested, confirmed, pendingDismissTitles, desiredLocalTime, now)
    }

    val commands = reconciled.commands.toMutableList()
    var currentRequested = reconciled.requested
    var currentConfirmed = reconciled.confirmed
    var currentPendingDismiss = reconciled.pendingDismissTitles
    var outcome = reconciled.outcome
    val smartWakeupWarning = reconciled.smartWakeupWarning

    if (desiredBandAlarm == null) {
        listOfNotNull(currentConfirmed?.title, currentRequested?.title).distinct().forEach { title ->
            if (title !in currentPendingDismiss) {
                commands.add(BandAlarmCommand.Dismiss(title))
                currentPendingDismiss = currentPendingDismiss + title
                outcome = BandAlarmOutcome.DISMISS_PENDING
            }
        }
        return BandAlarmDecision(commands, null, null, currentPendingDismiss, outcome, smartWakeupWarning, slotMode, singleSlotResendsUsed)
    }

    val desiredTime = requireNotNull(desiredLocalTime) { "desiredLocalTime must be set whenever desiredBandAlarm is non-null" }
    val marginUntilTargetMinute = Duration.between(now, desiredBandAlarm.truncatedTo(ChronoUnit.MINUTES))
    if (marginUntilTargetMinute < minSendMargin) {
        return BandAlarmDecision(
            commands, currentRequested, currentConfirmed, currentPendingDismiss,
            outcome.orIfIdle(BandAlarmOutcome.TOO_SOON), smartWakeupWarning, slotMode, singleSlotResendsUsed
        )
    }

    if (slots == null) {
        return decideBlindBandAlarmCommands(currentRequested, currentConfirmed, currentPendingDismiss, desiredTime, now, singleSlotResendsUsed)
    }
    if (slotMode == BandAlarmSlotMode.SINGLE_SLOT) {
        return decideSingleSlotBandAlarmCommands(reconciled, slots, desiredTime, now, singleSlotResendsUsed)
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
                outcome = outcome.orIfIdle(BandAlarmOutcome.NO_FREE_SLOT)
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
                outcome = outcome.orIfIdle(BandAlarmOutcome.NO_FREE_SLOT)
            } else {
                val title = ALL_BAND_ALARM_TITLES.firstOrNull { !titleOccupied(slots, it) && it !in currentPendingDismiss }
                if (title == null) {
                    outcome = outcome.orIfIdle(BandAlarmOutcome.NO_FREE_SLOT)
                } else {
                    commands.add(BandAlarmCommand.Set(title, desiredTime.hour, desiredTime.minute))
                    currentRequested = BandAlarmCommitment(title, desiredTime.hour, desiredTime.minute, now)
                    outcome = outcome.orIfIdle(BandAlarmOutcome.REQUESTED)
                }
            }
        }
    }

    return BandAlarmDecision(
        commands, currentRequested, currentConfirmed, currentPendingDismiss, outcome, smartWakeupWarning,
        slotMode, singleSlotResendsUsed
    )
}
