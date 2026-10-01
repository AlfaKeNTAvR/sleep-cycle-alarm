package com.nikita.sleepcycle.night

// File purpose: P3 (owner spec, 2026-09-30) - the post-alarm cycle, as one pure decision. The owner's words:
// "Once the morning alarm works, once I stop it, it automatically arms the out-of-bed nudge in 10 minutes,
// unless I press the button on the screen which says Nap for 20 minutes. If I click that, it disarms the nudge
// and just lets me nap. The same cycle repeats once I wake up after the nap. And it just works indefinitely."
// And: "without even detecting whether I fell asleep or not, just set the alarm in 20 minutes."
//
// What this replaces: after the morning alarm, the engine used to watch the band and arm a nap 20 minutes after
// a DETECTED return to sleep (rule 7 / D5 / G8, capped at two), with H7.2 cancelling the nudge when it did and
// J5 putting it back when the owner woke again. The real nights of 2026-09-25..30 showed why that never worked
// for him: the band reports a fall-asleep 5 to 24 minutes late, so by the time a nap could have been armed the
// nudge had already rung into the doze. Now nothing after the morning alarm reads the band (WakeAlarm.kt's
// `morningAlarmHasRung` gate) and the owner says when he is napping, with one button.
//
// ONE slot, ONE record. The nudge and the manual nap share the out-of-bed alarm's request code (2003) and
// OutOfBedNudgeStore.kt's pending record, with [FollowUpKind] saying which it is. So at most one follow-up is
// ever pending, a nap replaces the nudge atomically (same PendingIntent identity), and everything that already
// keeps a pending nudge alive - BootReceiver's restore, L2.1's FINISHED deferral, the speed-change re-arm,
// endNight's cancel - keeps a pending nap alive the same way without a line of its own.
//
// The manual nap is never counted against MAX_NAP_ALARMS: its firing arrives on the out-of-bed slot, which
// PhoneAlarmReceiver.firedAlarmRecordsPlanBookkeeping already keeps out of every engine fact (D1/F2/G8/H2).

import com.nikita.sleepcycle.engine.EngineConfig
import java.time.Instant

/** P3: which kind of follow-up alarm is pending in the out-of-bed slot - the repeating nudge (D4/L1) or a nap the owner chose. */
enum class FollowUpKind { NUDGE, NAP }

/** P3: the one follow-up alarm pending after an alarm has rung, and when it rings (virtual - T4). */
data class PendingFollowUp(val kind: FollowUpKind, val at: Instant)

/** P3: what just happened in the post-alarm cycle, each at its own virtual instant. */
sealed interface PostAlarmEvent {
    /** Any real alarm fired: the morning alarm, an engine nap, a nudge, or a manual nap. */
    data class AlarmFired(val at: Instant) : PostAlarmEvent

    /** The ring ended by the owner's Stop, or by the ring's own auto-stop - never by the night ending. */
    data class RingEnded(val at: Instant) : PostAlarmEvent

    /** The owner pressed the Night screen's "Nap 20 min" button. */
    data class NapPressed(val at: Instant) : PostAlarmEvent
}

/**
 * P3: the follow-up that should be pending once [event] has happened, given the one currently [pending]. A
 * return value equal to [pending] means "leave it alone"; null means nothing is pending.
 *
 * - [PostAlarmEvent.AlarmFired]: a safety-net nudge at `firing + ringAutoStopAfter + outOfBedDelay` - exactly
 *   where the nudge lands if the ring runs to its own auto-stop. Armed at the firing (not only at the ring's
 *   end) so a ring whose end is never observed - the ring service killed mid-ring - still has a nudge behind it.
 *   Replaces whatever was pending: a fresh ring is a fresh cycle.
 * - [PostAlarmEvent.RingEnded]: the nudge moves to `end + outOfBedDelay` ("once I stop it ... in 10 minutes"),
 *   unless a nap is pending - a nap chosen while the alarm was still ringing must survive the Stop after it.
 * - [PostAlarmEvent.NapPressed]: only a pending NUDGE can be traded for a nap, at `press + napLength`, never
 *   later than a wake-by deadline still ahead (the deadline is the hardest promise this app makes). A deadline
 *   already past caps nothing: capping at a past instant would ring at once, and L2 keeps the cycle running past
 *   the deadline anyway. A press with a nap already pending, or nothing pending, changes nothing.
 */
fun nextFollowUp(event: PostAlarmEvent, pending: PendingFollowUp?, deadline: Instant?, config: EngineConfig): PendingFollowUp? =
    when (event) {
        is PostAlarmEvent.AlarmFired ->
            PendingFollowUp(FollowUpKind.NUDGE, event.at.plus(config.ringAutoStopAfter).plus(config.outOfBedDelay))
        is PostAlarmEvent.RingEnded ->
            if (pending?.kind == FollowUpKind.NAP) pending else PendingFollowUp(FollowUpKind.NUDGE, event.at.plus(config.outOfBedDelay))
        is PostAlarmEvent.NapPressed ->
            if (pending?.kind != FollowUpKind.NUDGE) pending else PendingFollowUp(FollowUpKind.NAP, napEndsAt(event.at, deadline, config))
    }

/** P3: `press + napLength`, capped by a deadline that is still ahead of [pressedAt] - see [nextFollowUp]. */
private fun napEndsAt(pressedAt: Instant, deadline: Instant?, config: EngineConfig): Instant {
    val full = pressedAt.plus(config.napLength)
    return if (deadline != null && deadline.isAfter(pressedAt)) minOf(full, deadline) else full
}
