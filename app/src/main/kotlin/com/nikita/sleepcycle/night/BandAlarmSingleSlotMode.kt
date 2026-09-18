package com.nikita.sleepcycle.night

// File purpose: the single-slot half of the band alarm decision (BandAlarmSlotMode.SINGLE_SLOT), split out of
// BandAlarmDecision.kt to keep that file under its size budget, the same way BandAlarmBlindMode.kt is. The
// owner's band has exactly one slot this app can use: position 0 is forced to be the band's own smart alarm
// and is excluded (see BandAlarmMapping.kt's header), and freeing a second alarm is not something the owner
// wants to be forced into. With one slot there is no other title to set the replacement under, so moving the
// wake time means DISMISS our own confirmed title and SET the same title again, in that order, in one tick -
// the band is briefly without an alarm, which is why the Night screen says so and the phone alarm is the
// safety net. This is the ONLY place allowed to dismiss before a replacement is confirmed, and only because
// the table itself proved there is no second slot to alternate into (rule 3 stands everywhere else).
//
// Self-healing: a SET that silently never lands (Gadgetbridge never reports that failure) leaves a pending
// request that the next tick's table does not show. That tick re-sends it immediately rather than waiting for
// the wake time to move again - bounded by MAX_SINGLE_SLOT_RESENDS_PER_NIGHT so a band that stopped accepting
// alarms altogether cannot be hammered all night.

import com.nikita.sleepcycle.bridge.BandAlarmSlot
import java.time.Instant
import java.time.LocalTime

/**
 * How many times in one night a single-slot SET that the table never showed may be re-sent before the app
 * gives up and leaves the phone alarm as the only safety net. At the frequent sync cadence (5 min near the
 * alarm) this is about half an hour of trying.
 */
const val MAX_SINGLE_SLOT_RESENDS_PER_NIGHT = 6

/**
 * Decides band alarm commands for a tick whose table shows only one usable slot. [reconciled] is the result
 * of [reconcileAgainstTable] for this same tick, [desiredTime] and [now] are the caller's already-resolved
 * local target and post-I/O clock, and [resendsUsed] is how many bounded re-sends this night has already
 * spent (carried in [NightState.singleSlotResendsUsed]).
 */
internal fun decideSingleSlotBandAlarmCommands(
    reconciled: ReconciledBandAlarm,
    slots: List<BandAlarmSlot>,
    desiredTime: LocalTime,
    now: Instant,
    resendsUsed: Int
): BandAlarmDecision {
    val confirmed = reconciled.confirmed
    if (confirmed != null) return moveConfirmedAlarmInPlace(reconciled, confirmed, desiredTime, now, resendsUsed)

    val requested = reconciled.requested
    // Reconciliation already ran, so a request still pending here is one the table did not show: the SET
    // never landed, or the slot was cleared behind our back.
    if (requested != null) return resendMissingAlarm(reconciled, requested, desiredTime, now, resendsUsed)

    val title = ALL_BAND_ALARM_TITLES.firstOrNull { !titleOccupied(slots, it) && it !in reconciled.pendingDismissTitles }
        ?: return singleSlotDecision(reconciled, null, null, emptyList(), BandAlarmOutcome.NO_FREE_SLOT, resendsUsed)
    return singleSlotDecision(
        reconciled,
        requestedBandAlarm = BandAlarmCommitment(title, desiredTime.hour, desiredTime.minute, now),
        confirmedBandAlarm = null,
        extraCommands = listOf(BandAlarmCommand.Set(title, desiredTime.hour, desiredTime.minute)),
        candidateOutcome = BandAlarmOutcome.REQUESTED,
        resendsUsed = resendsUsed
    )
}

/**
 * The single-slot move: with nowhere else to put the replacement, the confirmed title is dismissed and then
 * re-set to the new time in the same tick. A stale request under any other title is dropped here - in
 * single-slot mode only one title is ever ours, and a request that never claimed a slot never will.
 */
private fun moveConfirmedAlarmInPlace(
    reconciled: ReconciledBandAlarm,
    confirmed: BandAlarmCommitment,
    desiredTime: LocalTime,
    now: Instant,
    resendsUsed: Int
): BandAlarmDecision {
    if (matchesLocalTime(confirmed, desiredTime)) {
        return singleSlotDecision(reconciled, null, confirmed, emptyList(), BandAlarmOutcome.UNCHANGED, resendsUsed)
    }
    return singleSlotDecision(
        reconciled,
        requestedBandAlarm = BandAlarmCommitment(confirmed.title, desiredTime.hour, desiredTime.minute, now),
        confirmedBandAlarm = null,
        extraCommands = listOf(
            BandAlarmCommand.Dismiss(confirmed.title),
            BandAlarmCommand.Set(confirmed.title, desiredTime.hour, desiredTime.minute)
        ),
        candidateOutcome = BandAlarmOutcome.MOVED_IN_ONE_SLOT,
        resendsUsed = resendsUsed
    )
}

/** Self-healing for a SET the table never showed: re-send it at once, at the current target, until the per-night bound is spent. */
private fun resendMissingAlarm(
    reconciled: ReconciledBandAlarm,
    requested: BandAlarmCommitment,
    desiredTime: LocalTime,
    now: Instant,
    resendsUsed: Int
): BandAlarmDecision {
    if (resendsUsed >= MAX_SINGLE_SLOT_RESENDS_PER_NIGHT) {
        return singleSlotDecision(reconciled, requested, null, emptyList(), BandAlarmOutcome.RESEND_LIMIT_REACHED, resendsUsed)
    }
    return singleSlotDecision(
        reconciled,
        requestedBandAlarm = requested.copy(hour = desiredTime.hour, minute = desiredTime.minute, at = now, blindResendCount = 0),
        confirmedBandAlarm = null,
        extraCommands = listOf(BandAlarmCommand.Set(requested.title, desiredTime.hour, desiredTime.minute)),
        candidateOutcome = BandAlarmOutcome.MISSING_RESENT,
        resendsUsed = resendsUsed + 1
    )
}

/** Joins this tick's reconciliation result with whatever the single-slot protocol decided on top of it. */
private fun singleSlotDecision(
    reconciled: ReconciledBandAlarm,
    requestedBandAlarm: BandAlarmCommitment?,
    confirmedBandAlarm: BandAlarmCommitment?,
    extraCommands: List<BandAlarmCommand>,
    candidateOutcome: BandAlarmOutcome,
    resendsUsed: Int
): BandAlarmDecision = BandAlarmDecision(
    commands = reconciled.commands + extraCommands,
    requestedBandAlarm = requestedBandAlarm,
    confirmedBandAlarm = confirmedBandAlarm,
    pendingDismissTitles = reconciled.pendingDismissTitles,
    outcome = reconciled.outcome.orIfIdle(candidateOutcome),
    smartWakeupWarning = reconciled.smartWakeupWarning,
    slotMode = BandAlarmSlotMode.SINGLE_SLOT,
    singleSlotResendsUsed = resendsUsed
)
