package com.nikita.sleepcycle.night

// File purpose: the single-slot half of the band alarm decision (BandAlarmSlotMode.SINGLE_SLOT), split out of
// BandAlarmDecision.kt to keep that file under its size budget, the same way BandAlarmBlindMode.kt is. The
// owner's band has exactly one slot this app can use: position 0 is forced to be the band's own smart alarm
// and is excluded (see BandAlarmMapping.kt's header), and freeing a second alarm is not something the owner
// wants to be forced into. With one slot there is no other title to set the replacement under, so moving the
// wake time means DISMISS our own confirmed title and SET the same title again, in that order, in one tick -
// the band is briefly without an alarm, which is why the Night screen says so and why a night that starts on
// a one-slot band must have a phone alarm of its own (a deadline or the phone backup - see
// SetupCompleteness.kt's singleSlotNightNeedsPhoneAlarm): every SET here can silently fail, and without a
// phone alarm nothing would be left to ring. This is the ONLY place allowed to dismiss before a replacement
// is confirmed, and only because the table itself proved there is no second slot to alternate into (rule 3
// stands everywhere else).
//
// Self-healing: a SET that silently never lands (Gadgetbridge never reports that failure) leaves a pending
// request that the next tick's table does not show. That tick re-sends it immediately rather than waiting for
// the wake time to move again - bounded by MAX_SINGLE_SLOT_RESENDS_PER_NIGHT so a band that stopped accepting
// alarms altogether cannot be hammered all night.

import com.nikita.sleepcycle.bridge.BandAlarmSlot
import com.nikita.sleepcycle.bridge.countUsableBandAlarmSlots
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
    // The table proves what can be placed. With nothing free and nothing of ours enabled, every command this
    // protocol could send would be dropped by Gadgetbridge unheard (SET_ALARM only logs its own failure), so
    // it is withheld instead - including the re-send, which would otherwise burn the whole night's bound on
    // sends that never had a slot to land in.
    if (countUsableBandAlarmSlots(slots, ALL_BAND_ALARM_TITLES.toSet()) == 0) {
        return singleSlotDecision(
            reconciled, reconciled.requested, reconciled.confirmed, emptyList(), BandAlarmOutcome.NO_FREE_SLOT, resendsUsed
        )
    }

    val confirmed = reconciled.confirmed
    if (confirmed != null) return moveConfirmedAlarmInPlace(reconciled, confirmed, desiredTime, now, resendsUsed)

    val requested = reconciled.requested
    // Reconciliation already ran, so a request still pending here is one the table did not show AT THE TIME WE
    // ASKED FOR: the SET never landed (or, after a move, the DISMISS did not either and the old alarm is still
    // sitting there under the same title).
    if (requested != null) return resendMissingAlarm(reconciled, requested, slots, desiredTime, now, resendsUsed)

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

/**
 * Self-healing for a request the table does not show at the time we asked for: re-send it at once, at the
 * current target, until the per-night bound is spent. Every attempt costs one of the night's re-sends,
 * including the case where the title is still on the band at the OLD time - a move whose DISMISS was lost
 * leaves exactly that, and the whole move (DISMISS then SET, same order as any other single-slot move) has to
 * go out again. Without charging that case the bound was never reached at all: the stale alarm looked like a
 * landed request, was promoted, and the move repeated every tick for the rest of the night.
 */
private fun resendMissingAlarm(
    reconciled: ReconciledBandAlarm,
    requested: BandAlarmCommitment,
    slots: List<BandAlarmSlot>,
    desiredTime: LocalTime,
    now: Instant,
    resendsUsed: Int
): BandAlarmDecision {
    if (resendsUsed >= MAX_SINGLE_SLOT_RESENDS_PER_NIGHT) {
        return singleSlotDecision(reconciled, requested, null, emptyList(), BandAlarmOutcome.RESEND_LIMIT_REACHED, resendsUsed)
    }
    val staleAlarmStillThere = titleOccupied(slots, requested.title)
    val commands = if (staleAlarmStillThere) {
        listOf(
            BandAlarmCommand.Dismiss(requested.title),
            BandAlarmCommand.Set(requested.title, desiredTime.hour, desiredTime.minute)
        )
    } else {
        listOf(BandAlarmCommand.Set(requested.title, desiredTime.hour, desiredTime.minute))
    }
    return singleSlotDecision(
        reconciled,
        requestedBandAlarm = requested.copy(hour = desiredTime.hour, minute = desiredTime.minute, at = now, blindResendCount = 0),
        confirmedBandAlarm = null,
        extraCommands = commands,
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
