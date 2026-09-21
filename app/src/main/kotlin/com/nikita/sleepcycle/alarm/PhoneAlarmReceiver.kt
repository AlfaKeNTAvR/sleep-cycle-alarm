package com.nikita.sleepcycle.alarm

// File purpose: fires when the phone alarm rings; holds the ringing wake lock and hands off to
// AlarmRingService which owns sound, vibration and the full-screen intent. If the foreground service cannot
// be started at all (background-start restrictions, OS refusal), falls back to posting the alarm
// notification directly and launching AlarmActivity, so the alarm is never completely silent.
//
// D1: a firing intent carries EXTRA_ALARM_IS_TEST and EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI (see
// PhoneAlarmScheduler.kt). A test alarm (the Debug screen's "Ring phone alarm in 1 min") never loads, logs to,
// or writes night state - it only rings. A real alarm marks fired the instant ITS OWN intent was scheduled
// for, never `state.lastPlan?.phoneAlarm`, which could already point at a different, newer plan by the time
// this receiver runs.
//
// D4/H7.1: a firing intent also carries EXTRA_ALARM_IS_OUT_OF_BED_NUDGE. The wake alarm AND every D5/G8 nap
// alarm share the same request code/slot (D1: "one alarm, and it is the phone's"), so whichever of them just
// fired arms the out-of-bed nudge in turn (15 min later, H7.1).
//
// L1 (owner decision, 2026-09-21) SUPERSEDES the rule this paragraph used to state here, and again above
// [PhoneAlarmReceiver.armOutOfBedNudge]: "the nudge itself never re-arms another one (no chaining)". A nudge
// firing now arms the NEXT nudge, one outOfBedDelay later, exactly as a wake or nap firing already does, so
// the nudge repeats until the night ends. See armOutOfBedNudge's own L1 doc for the contract, what still ends
// the chain, and why there is no cap. Unchanged by L1: the nudge's own firing still never touches
// phoneAlarmFiredFor/wakeAlarmFiredAt/napAlarmsUsed/lastNapAlarmFiredAt
// (those four are D5/F2/F6/G8/H2's own bookkeeping for the wake/nap alarm that fired, never the nudge's own
// firing - see [firedAlarmRecordsPlanBookkeeping], the one seam that boundary now lives behind). G4/H5: the
// nudge's own pending fire instant is persisted separately (OutOfBedNudgeStore.kt) the
// moment it is armed here, so a reboot or app update in between still restores it (see BootReceiver.handleBoot,
// independently of whether night state also survived - H5). H5: it is cleared the moment the nudge fires, in
// onReceive itself rather than here, because that must happen even when night state is already gone (G1's
// FINISHED-bookkeeping can clear it first, while an already-armed nudge still correctly keeps ringing).
//
// H7.2: a nap superseding a still-pending nudge is NOT handled here - it happens in NightOrchestrator, right
// after a tick arms the nap, because that is where the tick already knows the new plan is NAP. See
// NightOrchestrator.cancelNudgeIfSupersededByNap.
//
// H7.3: arming the nudge here also arms a silent pre-nudge check [EngineConfig.preNudgeCheckLead] earlier -
// see OutOfBedPreNudgeCheck.kt for the re-sync and its fail-open decision.
//
// F1: the ring's own auto-stop duration is resolved HERE (from the night's own debug options, via
// resolveEngineConfig) and carried on the intent that starts AlarmRingService, since that service has no
// state file of its own to read one from - see EngineConfig.ringAutoStopAfter's doc for why it must differ
// from a fast debug night's outOfBedDelay.
//
// F2/F6/G8/H2: attributing a firing to the main wake alarm vs. a nap alarm (mid-night rule 7 or post-wake D5
// alike - G8 counts every nap alarm that fires, whichever rule armed it) is done HERE too, from the plan that
// was in effect the moment this alarm fired (state.lastPlan, matched against the instant THIS intent was
// scheduled for) - see recordWakeOrNapFired's own doc.

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nikita.sleepcycle.engine.MAX_NAP_ALARMS
import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.NightLogEvent
import com.nikita.sleepcycle.night.NightState
import com.nikita.sleepcycle.night.appendNightLog
import com.nikita.sleepcycle.night.appendSetupLog
import com.nikita.sleepcycle.night.clearOutOfBedNudgePendingAt
import com.nikita.sleepcycle.night.firedAlarmIsWakeAlarm
import com.nikita.sleepcycle.night.loadNightState
import com.nikita.sleepcycle.night.nowInstant
import com.nikita.sleepcycle.night.resolveEngineConfig
import com.nikita.sleepcycle.night.saveLastNapAlarmFiredAt
import com.nikita.sleepcycle.night.saveNapAlarmsUsed
import com.nikita.sleepcycle.night.saveOutOfBedNudgePendingAt
import com.nikita.sleepcycle.night.savePhoneAlarmFiredFor
import com.nikita.sleepcycle.night.saveWakeAlarmFiredAt
import com.nikita.sleepcycle.night.schedulePreNudgeCheck
import java.time.Instant

/**
 * L1 (owner decision, 2026-09-21): whether a firing does the phone alarm SLOT's own bookkeeping - D1's fired
 * marker, and F2/F6/G8/H2's wake-versus-nap attribution. True for the wake alarm and for every D5/G8 nap
 * alarm, false for the out-of-bed nudge, which has its own request code and its own label (D4) and is not a
 * plan alarm at all.
 *
 * This is now the ONLY thing the nudge's own firing is excluded from. Until L1 the same `isOutOfBed` flag
 * also gated the nudge ARMING that follows it, through one early return in [PhoneAlarmReceiver] covering
 * both - which is why the nudge rang exactly once per night. Splitting the two is the whole change: the four
 * fired-alarm facts stay the wake/nap alarm's alone, while every firing, the nudge's included, arms the next
 * nudge. `internal`, not `private`: the one pure decision seam, JVM-testable directly without a Context
 * (see OutOfBedNudgeSupersessionTest.kt's own L1 section).
 */
internal fun firedAlarmRecordsPlanBookkeeping(isOutOfBed: Boolean): Boolean = !isOutOfBed

class PhoneAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val isTest = intent.getBooleanExtra(EXTRA_ALARM_IS_TEST, false)
        val isOutOfBed = intent.getBooleanExtra(EXTRA_ALARM_IS_OUT_OF_BED_NUDGE, false)
        // W18: wording only, carried forward to whatever ends up showing the ring - see AlarmLabel.kt.
        val label = intent.readAlarmLabel()
        val state = if (isTest) null else loadNightState(context)
        val ringAutoStopAfter = resolveEngineConfig(state?.debugOptions ?: DebugOptions()).ringAutoStopAfter
        acquireAlarmWakeLock(context, ringAutoStopAfter)
        if (isTest) {
            // T8: the test alarm is a daylight check of the ring path, never part of a simulated night - real time.
            appendSetupLog(context, NightLogEvent(Instant.now(), "debug_test_alarm_fired", emptyMap()))
        } else {
            // T4: virtual - this firing (and everything it records) belongs to night state, which is entirely
            // in virtual time. nowInstant() correctly recovers the intended virtual instant even when this
            // receiver was woken by an AlarmManager alarm that fired at a T5-converted REAL instant.
            val now = nowInstant()
            // H5: the nudge's own pending-instant record is self-consuming REGARDLESS of whether night state
            // still exists - G1's FINISHED-bookkeeping can clear the state before an already-armed nudge fires
            // (deliberately: the nudge still rings correctly either way, see startForegroundService below), and
            // when that happens there is nothing left to log against, but the record must still be cleared here
            // or it outlives the night it belonged to, surviving until the next startNight overwrites it.
            if (isOutOfBed) clearOutOfBedNudgePendingAt(context)
            if (state != null) recordRealAlarmFired(context, state, intent, now, isOutOfBed)
        }
        try {
            context.startForegroundService(
                Intent(context, AlarmRingService::class.java)
                    .putExtra(EXTRA_ALARM_IS_OUT_OF_BED_NUDGE, isOutOfBed)
                    .putExtra(EXTRA_ALARM_LABEL, label.name)
                    .putExtra(EXTRA_RING_AUTO_STOP_AFTER_MILLIS, ringAutoStopAfter.toMillis())
            )
        } catch (error: Exception) {
            handleServiceStartFailure(context, state, isOutOfBed, label, error)
        }
    }

    /**
     * Logs the firing, then does the two separate things a firing means.
     *
     * The phone alarm slot's own bookkeeping - marking fired the instant THIS intent was scheduled for (D1)
     * and attributing it to either the wake alarm or a nap (F2/F6/G8/H2) - runs only for a wake or nap
     * firing, per [firedAlarmRecordsPlanBookkeeping].
     *
     * L1 (owner decision, 2026-09-21): arming the next out-of-bed nudge (D4) runs for EVERY real firing, the
     * nudge's own included. This function used to return early on [isOutOfBed] before it ever reached
     * [armOutOfBedNudge], which is exactly what made the nudge ring once per night and then stop; that one
     * early return conflated "this is not a plan alarm" with "nothing follows this". See [armOutOfBedNudge]'s
     * own L1 doc for the contract. H5: the nudge's self-consuming record clear happens in [onReceive] itself,
     * unconditionally and BEFORE this runs (it must also happen when [state] is null), so a repeat's own
     * [saveOutOfBedNudgePendingAt] writes over a record that was just cleared rather than racing it.
     */
    private fun recordRealAlarmFired(context: Context, state: NightState, intent: Intent, now: Instant, isOutOfBed: Boolean) {
        val eventName = if (isOutOfBed) "out_of_bed_alarm_fired" else "phone_alarm_fired"
        appendNightLog(context, state.startedAt, NightLogEvent(now, eventName, emptyMap()), state.debugOptions.isAnyEnabled)
        if (firedAlarmRecordsPlanBookkeeping(isOutOfBed)) {
            val scheduledForMillis = intent.getLongExtra(EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI, -1L)
            if (scheduledForMillis >= 0) {
                val firedFor = Instant.ofEpochMilli(scheduledForMillis)
                markPhoneAlarmFired(context, state, firedFor)
                recordWakeOrNapFired(context, state, firedFor)
            }
        }
        armOutOfBedNudge(context, state, now, isOutOfBed)
    }

    /**
     * D3: records which instant just fired, so armPhoneAlarmIfNeeded never re-arms it (Android fires a past
     * exact alarm immediately) - through [savePhoneAlarmFiredFor]'s own tiny file, never a read-modify-write
     * of the whole [NightState], which used to race a concurrent tick's own save under the night transaction
     * lock (whichever wrote last would silently discard the other's changes).
     */
    private fun markPhoneAlarmFired(context: Context, state: NightState, firedFor: Instant) {
        if (!savePhoneAlarmFiredFor(context, firedFor)) {
            appendNightLog(
                context, state.startedAt,
                NightLogEvent(nowInstant(), "error", mapOf("step" to "save_night_state", "cause" to "failed to persist phoneAlarmFiredFor after the alarm fired")),
                state.debugOptions.isAnyEnabled
            )
        }
    }

    /**
     * F2 SUPERSEDES the original spec, F6/G8: attributes THIS firing to either the main wake alarm or a nap,
     * based on the plan that was in effect right before it (`state.lastPlan`), matched against [firedFor] - a
     * defensive guard against a stale plan that no longer describes the alarm that just fired (the persisted
     * plan and the currently-armed alarm should always agree, since armPhoneAlarmIfNeeded saves both together
     * every tick, but a firing must never trust that blindly).
     *
     * G3: that guard used to bail out silently on a mismatch - a state save that failed after arming, a
     * quarantined state file falling back to an older backup, or a tick racing a delivery window (arming a
     * replacement while the old alarm is already queued) can all produce one, and every one of them meant the
     * alarm rang with nothing recorded and no trace of why. Now logged, so it is visible without adb.
     *
     * A mode other than NAP means the wake alarm itself just fired: [saveWakeAlarmFiredAt] records the ONE
     * marker H1's AWAKE-branch guard still needs (see WakeAlarm.kt's `napAlarm`), and only this branch may
     * ever set it (F6) - a rule 7 mid-night nap firing (mode NAP) must not. G8 SUPERSEDES F2: a NAP firing
     * always increments napAlarmsUsed now, clamped at [MAX_NAP_ALARMS] - mid-night (rule 7) and post-wake (D5)
     * alike, whether or not the main wake alarm has fired this night - and only because it FIRED, never for an
     * armed-but-not-yet-fired nap. H2: the same firing also records [saveLastNapAlarmFiredAt], the fact
     * WakeAlarm.kt's ASLEEP branch needs to tell "a nap already rang for THIS stretch" from "this target is
     * merely overdue" - see its own doc.
     */
    private fun recordWakeOrNapFired(context: Context, state: NightState, firedFor: Instant) {
        val lastPlanWakeAt = state.lastPlan?.wakeAt
        val firedPlan = state.lastPlan?.takeIf { lastPlanWakeAt == firedFor }
        if (firedPlan == null) {
            appendNightLog(
                context, state.startedAt,
                NightLogEvent(
                    nowInstant(), "error",
                    mapOf(
                        "step" to "attribute_alarm_fired",
                        "cause" to "state.lastPlan's own wakeAt (${lastPlanWakeAt ?: "null"}) did not match the instant this alarm fired for " +
                            "($firedFor) - attribution skipped, wakeAlarmFiredAt/napAlarmsUsed left unchanged"
                    )
                ),
                state.debugOptions.isAnyEnabled
            )
            return
        }
        if (firedAlarmIsWakeAlarm(firedPlan.mode, firedFor, state.morningAlarmAt)) {
            if (!saveWakeAlarmFiredAt(context, firedFor)) {
                appendNightLog(
                    context, state.startedAt,
                    NightLogEvent(nowInstant(), "error", mapOf("step" to "save_night_state", "cause" to "failed to persist wakeAlarmFiredAt after the wake alarm fired")),
                    state.debugOptions.isAnyEnabled
                )
            }
        } else {
            val newCount = (state.napAlarmsUsed + 1).coerceAtMost(MAX_NAP_ALARMS)
            if (!saveNapAlarmsUsed(context, newCount)) {
                appendNightLog(
                    context, state.startedAt,
                    NightLogEvent(nowInstant(), "error", mapOf("step" to "save_night_state", "cause" to "failed to persist napAlarmsUsed after the nap alarm fired")),
                    state.debugOptions.isAnyEnabled
                )
            }
            if (!saveLastNapAlarmFiredAt(context, firedFor)) {
                appendNightLog(
                    context, state.startedAt,
                    NightLogEvent(nowInstant(), "error", mapOf("step" to "save_night_state", "cause" to "failed to persist lastNapAlarmFiredAt after the nap alarm fired")),
                    state.debugOptions.isAnyEnabled
                )
            }
        }
    }

    /**
     * D4/H7.1: armed the moment ANY real alarm fires - the wake alarm, a D5/G8 nap alarm (mid-night or
     * post-wake), and since L1 the out-of-bed nudge itself - at [now] + EngineConfig.outOfBedDelay (now
     * 15 min). Never for a test alarm ([recordRealAlarmFired] is only ever called for a real one).
     *
     * L1 (owner decision, 2026-09-21) SUPERSEDES this doc's own previous claim that the nudge is "never
     * re-armed by the nudge's own firing". THE CONTRACT NOW: pressing "I'm awake" is the only thing that means
     * the owner is genuinely up, so if it was never pressed, something is wrong and ringing again is the right
     * answer. A nudge firing arms the next nudge, one outOfBedDelay later. There is deliberately NO cap, no
     * maximum count and no deadline stop: a night that can never be ended nags every outOfBedDelay until the
     * owner notices, which is the direction the owner judged right to fail in.
     *
     * EXACTLY TWO THINGS END A CHAIN. L2 (owner decision, 2026-09-21) SUPERSEDES this doc's own previous
     * "EXACTLY THREE THINGS" list, and answers the open question docs/decisions.md's L1 record used to carry -
     * see its own L2 record for the reasoning.
     *  1. The owner - "I'm awake" on the ring screen, or ending the night from the app. Both go through
     *     NightController.endNight, which cancels the pending nudge, its H7.3 pre-check and its record
     *     together. This is the intended one.
     *  2. The H7.3 pre-check, on a confirmed-ASLEEP re-sync, and ONLY on a night whose plan is not already
     *     FINISHED: OutOfBedPreNudgeCheck.runPreNudgeCheck cancels the alarm and clears the record, which ends
     *     the WHOLE chain, not just the one nudge. It restarts only if a nap alarm is armed and actually fires.
     *     Correct by intent (the owner is asleep again and the nap logic owns the wake-up), but it does mean
     *     the chain is not unconditional. L2.2 (OutOfBedPreNudgeCheck.shouldCancelNudgeForPreCheck) carves out
     *     a FINISHED night from this item: past the deadline, "he is confirmed asleep" is the reason to keep
     *     ringing, not the reason to go quiet, and nothing is left to restart the chain if this cancelled it
     *     (no nap can arm on a FINISHED plan).
     *
     * What used to be a third item - G1's FINISHED bookkeeping clearing night state, which on a deadline night
     * let the chain ring once more past the deadline and then stop by starving [onReceive] of a night state to
     * read - no longer happens. L2.1 (NightController.finishNightIfNeeded) now defers that bookkeeping
     * entirely while a nudge is pending, so night state survives for [onReceive] to keep reading from, and a
     * deadline night's chain now behaves exactly like a no-deadline night's: unconditional short of items 1
     * and 2 above, which is what the owner asked for on both kinds of night.
     *
     * G4: the armed instant is persisted so [com.nikita.sleepcycle.night.BootReceiver] can restore it across a
     * reboot or app update before it fires. A repeat goes through that same single record, which [onReceive]
     * cleared moments earlier, so a chain never leaves a stale instant behind - and an arm that FAILS leaves
     * the record cleared, correctly, since there is then no nudge to restore.
     *
     * H7.3: also schedules the pre-nudge check [preNudgeCheckLead] before [at] - see OutOfBedPreNudgeCheck.kt.
     * Every repeat gets its own check, which is what still lets the nap logic take over if the owner genuinely
     * fell back asleep between two nudges. A reboot before the check fires simply loses it (it has no
     * reboot-recovery of its own, unlike the nudge itself), which is fine by the same fail-open rule the check
     * itself follows: losing the extra safety net still leaves the nudge ringing normally, never silently
     * missing.
     *
     * L1: a successful arming is logged as `out_of_bed_nudge_armed`, carrying the instant and which kind of
     * firing armed it, so a repeating chain reads in the night log as a sequence of distinct, attributable
     * events rather than a run of identical `out_of_bed_alarm_fired` lines. No counter and no phase field is
     * kept for this: the log line is derived entirely from [isOutOfBed] and [at], both already in hand.
     */
    private fun armOutOfBedNudge(context: Context, state: NightState, now: Instant, isOutOfBed: Boolean) {
        val config = resolveEngineConfig(state.debugOptions)
        val at = now.plus(config.outOfBedDelay)
        val armed = scheduleOutOfBedAlarm(context, at)
        if (!armed) {
            appendNightLog(
                context, state.startedAt,
                NightLogEvent(nowInstant(), "error", mapOf("step" to "out_of_bed_alarm", "cause" to "exact alarm permission was likely revoked")),
                state.debugOptions.isAnyEnabled
            )
            return
        }
        appendNightLog(
            context, state.startedAt,
            NightLogEvent(
                now, "out_of_bed_nudge_armed",
                mapOf("at" to at.toString(), "cause" to if (isOutOfBed) "out_of_bed_nudge_fired" else "wake_or_nap_alarm_fired")
            ),
            state.debugOptions.isAnyEnabled
        )
        if (!saveOutOfBedNudgePendingAt(context, at)) {
            appendNightLog(
                context, state.startedAt,
                NightLogEvent(nowInstant(), "error", mapOf("step" to "save_night_state", "cause" to "failed to persist the pending out-of-bed nudge instant")),
                state.debugOptions.isAnyEnabled
            )
        }
        if (!schedulePreNudgeCheck(context, at.minus(config.preNudgeCheckLead))) {
            appendNightLog(
                context, state.startedAt,
                NightLogEvent(nowInstant(), "error", mapOf("step" to "pre_nudge_check", "cause" to "exact alarm permission was likely revoked - the nudge will still ring on schedule")),
                state.debugOptions.isAnyEnabled
            )
        }
    }

    /** startForegroundService can be refused outright (background-start restrictions); the alarm must still be reachable. */
    private fun handleServiceStartFailure(context: Context, state: NightState?, isOutOfBed: Boolean, label: AlarmLabel, error: Exception) {
        if (state != null) {
            appendNightLog(
                context, state.startedAt,
                NightLogEvent(nowInstant(), "error", mapOf("step" to "alarm_service_start", "cause" to (error.message ?: error.toString()))),
                state.debugOptions.isAnyEnabled
            )
        }
        releaseAlarmWakeLock()
        postAlarmNotificationDirectly(context, isOutOfBed, label)
        context.startActivity(
            Intent(context, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_ALARM_IS_OUT_OF_BED_NUDGE, isOutOfBed)
                .putExtra(EXTRA_ALARM_LABEL, label.name)
        )
    }
}
