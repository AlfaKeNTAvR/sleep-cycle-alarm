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
// fired arms the out-of-bed nudge in turn (15 min later, H7.1) - the nudge itself never re-arms another one
// (no chaining), and never touches phoneAlarmFiredFor/wakeAlarmFiredAt/napAlarmsUsed/lastNapAlarmFiredAt
// (those four are D5/F2/F6/G8/H2's own bookkeeping for the wake/nap alarm that fired, never the nudge's own
// firing). G4/H5: the nudge's own pending fire instant is persisted separately (OutOfBedNudgeStore.kt) the
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

    /** Logs the firing, marks fired the instant THIS intent was scheduled for (D1, wake/nap alarms only), attributes it to either the wake alarm or a nap (F2/F6/G8, wake/nap alarms only), and arms the out-of-bed nudge (D4, wake/nap alarms only) - or, for the nudge's own firing, does nothing further (H5: its self-consuming record clear now happens in [onReceive] itself, unconditionally, since it must run even when [state] is null). */
    private fun recordRealAlarmFired(context: Context, state: NightState, intent: Intent, now: Instant, isOutOfBed: Boolean) {
        val eventName = if (isOutOfBed) "out_of_bed_alarm_fired" else "phone_alarm_fired"
        appendNightLog(context, state.startedAt, NightLogEvent(now, eventName, emptyMap()), state.debugOptions.isAnyEnabled)
        if (isOutOfBed) return
        val scheduledForMillis = intent.getLongExtra(EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI, -1L)
        if (scheduledForMillis >= 0) {
            val firedFor = Instant.ofEpochMilli(scheduledForMillis)
            markPhoneAlarmFired(context, state, firedFor)
            recordWakeOrNapFired(context, state, firedFor)
        }
        armOutOfBedNudge(context, state, now)
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
     * D4/H7.1: armed the moment the wake alarm (or a nap alarm, mid-night or post-wake) fires, at [now] +
     * EngineConfig.outOfBedDelay (now 15 min) - never for a test alarm ([recordRealAlarmFired] is only ever
     * called for a real one) and never re-armed by the nudge's own firing ([recordRealAlarmFired] returns
     * before reaching here when isOutOfBed is true). G4: the armed instant is persisted so
     * [com.nikita.sleepcycle.night.BootReceiver] can restore it across a reboot or app update before it fires.
     * H7.3: also schedules the pre-nudge check [preNudgeCheckLead] before [at] - see OutOfBedPreNudgeCheck.kt.
     * A reboot before the check fires simply loses it (it has no reboot-recovery of its own, unlike the nudge
     * itself), which is fine by the same fail-open rule the check itself follows: losing the extra safety net
     * still leaves the nudge ringing normally, never silently missing.
     */
    private fun armOutOfBedNudge(context: Context, state: NightState, now: Instant) {
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
