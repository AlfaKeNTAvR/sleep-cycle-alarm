package com.nikita.sleepcycle.night

// File purpose: the blind-mode half of the band alarm decision, split out of BandAlarmDecision.kt to keep
// that file under its size budget. Rule 4 (no band alarm table at all): the same export that would carry a
// moved target is the only source of fresh sleep data too, so a target that moves while blind reflects
// nothing new - only the projected onset sliding with the clock. Chasing it by dismissing the pending title
// and setting another one blind risks the SET silently never landing (Gadgetbridge never reports that
// failure), which could leave the band at zero alarms - the one outcome this app exists to prevent. So a
// commitment already on file is held exactly as it is instead (BandAlarmOutcome.BLIND_FROZEN), and the only
// thing ever attempted here beyond the first blind SET of the night is one bounded retry per blind episode.
// Nothing in THIS file ever dismisses. The FINISHED teardown does dismiss blind, but it never reaches here:
// BandAlarmDecision.kt handles a null desired alarm before dispatching to any slot mode, and it dismisses
// deliberately - once no alarm is wanted at all, there is nothing left to protect and a stale title on the
// band would ring tomorrow morning.

import java.time.Instant
import java.time.LocalTime

/** C2: how many consecutive blind ticks an unconfirmed requested SET waits before its single bounded re-send for this blind episode. */
private const val BLIND_RESEND_INTERVAL_TICKS = 4

/**
 * Decides band alarm commands for the case [decideBandAlarmCommands] cannot reconcile against anything: no
 * band alarm table was read this tick. [desiredTime] and [now] are already the caller's freshly-resolved
 * local target time and post-I/O clock, same as the table-available path.
 */
internal fun decideBlindBandAlarmCommands(
    requested: BandAlarmCommitment?,
    confirmed: BandAlarmCommitment?,
    pendingDismissTitles: Set<String>,
    desiredTime: LocalTime,
    now: Instant,
    singleSlotResendsUsed: Int
): BandAlarmDecision = when {
    requested == null && confirmed == null -> {
        val title = ALL_BAND_ALARM_TITLES.firstOrNull { it !in pendingDismissTitles } ?: BAND_ALARM_TITLE_A
        val freshRequest = BandAlarmCommitment(title, desiredTime.hour, desiredTime.minute, now)
        blindDecision(
            listOf(BandAlarmCommand.Set(title, desiredTime.hour, desiredTime.minute)),
            freshRequest, null, pendingDismissTitles, BandAlarmOutcome.BLIND, singleSlotResendsUsed
        )
    }
    requested != null && dueForBoundedBlindResend(requested) -> {
        // C2: the one bounded re-send this blind episode ever gets - the ORIGINAL committed time, never the
        // (possibly since-moved) desired time, because a blind re-send never retargets.
        val resent = requested.copy(at = now, blindResendCount = BLIND_RESEND_EXHAUSTED)
        blindDecision(
            listOf(BandAlarmCommand.Set(requested.title, requested.hour, requested.minute)),
            resent, confirmed, pendingDismissTitles, BandAlarmOutcome.BLIND, singleSlotResendsUsed
        )
    }
    else -> {
        val heldRequest = requested?.let { advanceBlindResendCount(it) }
        blindDecision(emptyList(), heldRequest, confirmed, pendingDismissTitles, BandAlarmOutcome.BLIND_FROZEN, singleSlotResendsUsed)
    }
}

/** Every blind decision reports [BandAlarmSlotMode.BLIND] and carries the night's single-slot re-send count through untouched - a blind tick can neither spend nor reset it. */
private fun blindDecision(
    commands: List<BandAlarmCommand>,
    requested: BandAlarmCommitment?,
    confirmed: BandAlarmCommitment?,
    pendingDismissTitles: Set<String>,
    outcome: BandAlarmOutcome,
    singleSlotResendsUsed: Int
): BandAlarmDecision = BandAlarmDecision(
    commands, requested, confirmed, pendingDismissTitles, outcome,
    smartWakeupWarning = null, slotMode = BandAlarmSlotMode.BLIND, singleSlotResendsUsed = singleSlotResendsUsed
)

/** C2: only the initial, bounded re-send is ever due - once [BandAlarmCommitment.blindResendCount] is exhausted, it is never due again this blind episode. */
private fun dueForBoundedBlindResend(requested: BandAlarmCommitment): Boolean =
    requested.blindResendCount != BLIND_RESEND_EXHAUSTED && requested.blindResendCount + 1 >= BLIND_RESEND_INTERVAL_TICKS

/** Counts one more blind tick against the bounded re-send budget, unless that budget is already exhausted - an exhausted commitment must stay exhausted for the rest of this blind episode, never re-arm itself. */
private fun advanceBlindResendCount(requested: BandAlarmCommitment): BandAlarmCommitment =
    if (requested.blindResendCount == BLIND_RESEND_EXHAUSTED) requested
    else requested.copy(blindResendCount = requested.blindResendCount + 1)
