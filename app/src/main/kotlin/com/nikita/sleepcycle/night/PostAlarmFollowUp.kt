package com.nikita.sleepcycle.night

// File purpose: P3 (owner spec, 2026-09-30) - the Android side of the post-alarm cycle. Every decision is
// PostAlarmCycle.kt's pure `nextFollowUp`; this file only reads the current record, asks it, and arms what it
// says in the one out-of-bed slot (request code 2003), persisting the record and logging it. Four callers, one
// per event: PhoneAlarmReceiver (an alarm fired), AlarmRingService (a ring ended by Stop or auto-stop), and the
// Night screen's "Nap 20 min" and "I'm up" buttons (NightViewModel).
//
// The H7.3/M1 pre-nudge check is armed ONLY for a nudge pending before the morning alarm has rung (a pre-wake
// rule 7 nap's own nudge, which a still-pending morning alarm can legitimately take over - M1). After the
// morning alarm the engine arms nothing (WakeAlarm.kt's `morningAlarmHasRung`), so M1's "is another alarm
// armed and still ahead" can never be true there: the check would be dead weight, and for a nap it would be
// harmful (it cancels "the nudge" by clearing the one shared record). So every arming here either schedules it
// (pre-morning nudge) or cancels any left over (everything else). Deleting the check outright is the owner's
// open decision; this keeps it exactly where it still has a job.
//
// No lock, like every other writer of this record (see NightController.finishNightIfNeeded's own L3.2 note):
// the realistic overlap - a Nap press landing in the same instant a ring ends - resolves to whichever wrote
// last, and both outcomes leave an alarm armed.

import android.content.Context
import com.nikita.sleepcycle.alarm.AlarmLabel
import com.nikita.sleepcycle.alarm.cancelPhoneAlarm
import com.nikita.sleepcycle.alarm.scheduleOutOfBedAlarm
import com.nikita.sleepcycle.engine.morningAlarmHasRung
import java.time.Instant

/** P3: the wording the follow-up rings under - the owner's own nap rings as "Nap alarm", the nudge as itself. */
fun alarmLabelForFollowUp(kind: FollowUpKind): AlarmLabel =
    if (kind == FollowUpKind.NAP) AlarmLabel.NAP else AlarmLabel.OUT_OF_BED

/**
 * P3: an alarm just fired (any real one - morning, engine nap, nudge, or the owner's nap): arms the safety-net
 * nudge `ringAutoStopAfter + outOfBedDelay` from [now], which [rearmFollowUpAfterRingEnded] moves to 10 min after
 * the ring actually ends. [state] is reloaded first, so a morning-alarm attribution this same firing just wrote
 * is already visible to the pre-check decision.
 */
fun armFollowUpAfterAlarmFired(context: Context, state: NightState, now: Instant, cause: String) {
    val fresh = loadNightState(context) ?: state
    val next = nextFollowUp(PostAlarmEvent.AlarmFired(now), readPendingFollowUp(context), fresh.settings.deadline, resolveEngineConfig(fresh.debugOptions))
        ?: return
    armFollowUp(context, fresh, next, now, cause)
}

/**
 * P3: the ring ended - the owner's Stop, or the ring's own auto-stop. Moves the nudge to `now + outOfBedDelay`
 * ("once I stop it ... in 10 minutes"), unless the owner already chose a nap. No night, nothing to do.
 */
fun rearmFollowUpAfterRingEnded(context: Context, now: Instant) {
    val state = loadNightState(context) ?: return
    val pending = readPendingFollowUp(context)
    val next = nextFollowUp(PostAlarmEvent.RingEnded(now), pending, state.settings.deadline, resolveEngineConfig(state.debugOptions))
    if (next == null || next == pending) return
    armFollowUp(context, state, next, now, "ring_ended")
}

/**
 * P3: the Night screen's "Nap 20 min". Trades the pending nudge for a nap alarm 20 min out (capped by a deadline
 * still ahead - see nextFollowUp). Returns whether a nap was armed. Never ends the night and never touches the
 * engine's own alarm: the nap lives only in the out-of-bed slot. A press with no nudge pending (a stale screen,
 * a double tap) changes nothing and is logged.
 */
fun startManualNap(context: Context, now: Instant): Boolean {
    val state = loadNightState(context) ?: return false
    val pending = readPendingFollowUp(context)
    val next = nextFollowUp(PostAlarmEvent.NapPressed(now), pending, state.settings.deadline, resolveEngineConfig(state.debugOptions))
    if (next == null || next == pending) {
        appendNightLog(
            context, state.startedAt,
            NightLogEvent(now, "manual_nap_refused", mapOf("cause" to "no out-of-bed nudge pending to trade for a nap", "pending" to pending.toString())),
            state.debugOptions.isAnyEnabled
        )
        return false
    }
    return armFollowUp(context, state, next, now, "nap_pressed")
}

/**
 * Owner spec, 2026-10-02: the Night screen's "I'm up", pressed before the morning alarm rang. Stands in for
 * that alarm being stopped right now: records [now] as the morning alarm's firing (so the engine arms nothing
 * more, WakeAlarm.kt's `morningAlarmHasRung`), cancels the pending phone alarm, and arms the out-of-bed nudge
 * 10 min out - which is what offers the Nap button. Under the night lock so a tick in flight cannot re-arm the
 * alarm just cancelled from a state read before the press. Returns whether it took effect; a press after the
 * morning alarm already rang (a stale screen) changes nothing and is logged.
 */
suspend fun pressImUp(context: Context, now: Instant): Boolean = withNightTransactionLock {
    val state = loadNightState(context) ?: return@withNightTransactionLock false
    val debugNight = state.debugOptions.isAnyEnabled
    if (morningAlarmHasRung(state.wakeAlarmFiredAt, state.morningAlarmAt, state.phoneAlarmFiredFor)) {
        appendNightLog(context, state.startedAt, NightLogEvent(now, "im_up_refused", mapOf("cause" to "the morning alarm already rang")), debugNight)
        return@withNightTransactionLock false
    }
    if (!saveWakeAlarmFiredAt(context, now)) {
        appendNightLog(
            context, state.startedAt,
            NightLogEvent(now, "error", mapOf("step" to "save_night_state", "cause" to "failed to persist wakeAlarmFiredAt for I'm up")),
            debugNight
        )
        return@withNightTransactionLock false
    }
    cancelPhoneAlarm(context)
    appendNightLog(context, state.startedAt, NightLogEvent(now, "im_up_pressed", mapOf("cancelledAlarmAt" to state.lastPlan?.wakeAt.toString())), debugNight)
    val upState = state.copy(wakeAlarmFiredAt = now)
    val next = nextFollowUp(PostAlarmEvent.ImUpPressed(now), readPendingFollowUp(context), upState.settings.deadline, resolveEngineConfig(upState.debugOptions))
        ?: return@withNightTransactionLock false
    armFollowUp(context, upState, next, now, "im_up_pressed")
}

/**
 * Arms [followUp] in the out-of-bed slot, then records and logs it. Scheduling comes FIRST: if it fails, the
 * previously armed follow-up is still in place in AlarmManager and its record is left describing it - nothing
 * is cleared on the strength of an arm that did not happen.
 */
private fun armFollowUp(context: Context, state: NightState, followUp: PendingFollowUp, now: Instant, cause: String): Boolean {
    val debugNight = state.debugOptions.isAnyEnabled
    if (!scheduleOutOfBedAlarm(context, followUp.at, alarmLabelForFollowUp(followUp.kind))) {
        appendNightLog(
            context, state.startedAt,
            NightLogEvent(now, "error", mapOf("step" to "out_of_bed_alarm", "cause" to "could not arm the ${followUp.kind} at ${followUp.at} ($cause) - exact alarm permission was likely revoked")),
            debugNight
        )
        return false
    }
    val event = if (followUp.kind == FollowUpKind.NAP) "manual_nap_armed" else "out_of_bed_nudge_armed"
    appendNightLog(context, state.startedAt, NightLogEvent(now, event, mapOf("at" to followUp.at.toString(), "cause" to cause)), debugNight)
    if (!savePendingFollowUp(context, followUp)) {
        appendNightLog(
            context, state.startedAt,
            NightLogEvent(now, "error", mapOf("step" to "save_night_state", "cause" to "failed to persist the pending ${followUp.kind} instant")),
            debugNight
        )
    }
    if (followUpNeedsPreNudgeCheck(followUp, state)) {
        val config = resolveEngineConfig(state.debugOptions)
        if (!schedulePreNudgeCheck(context, followUp.at.minus(config.preNudgeCheckLead))) {
            appendNightLog(
                context, state.startedAt,
                NightLogEvent(now, "error", mapOf("step" to "pre_nudge_check", "cause" to "exact alarm permission was likely revoked - the nudge will still ring on schedule")),
                debugNight
            )
        }
    } else {
        cancelPreNudgeCheck(context)
    }
    return true
}

/** P3: the pre-nudge check guards only a NUDGE pending before the morning alarm has rung - see this file's own header. */
internal fun followUpNeedsPreNudgeCheck(followUp: PendingFollowUp, state: NightState): Boolean =
    followUp.kind == FollowUpKind.NUDGE &&
        !morningAlarmHasRung(state.wakeAlarmFiredAt, state.morningAlarmAt, state.phoneAlarmFiredFor)
