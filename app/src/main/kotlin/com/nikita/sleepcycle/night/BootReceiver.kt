package com.nikita.sleepcycle.night

// File purpose: re-arms tracking after a reboot or an app update, and reacts to the clock or timezone
// changing mid-night - the phone alarm's hour/minute is local, so a zone change needs a fresh tick to
// re-derive it correctly.

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nikita.sleepcycle.alarm.alarmLabelFor
import com.nikita.sleepcycle.alarm.schedulePhoneAlarm
import com.nikita.sleepcycle.alarm.scheduleOutOfBedAlarm
import com.nikita.sleepcycle.engine.EngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Runs on BOOT_COMPLETED, MY_PACKAGE_REPLACED (F13), TIME_SET and TIMEZONE_CHANGED. State is loaded off the
 * main thread via [goAsync]. F13: Android cancels every alarm an app has set when its package is replaced -
 * there is no exemption for an alarm-clock app - so an app update mid-night silently drops the wake alarm,
 * the nudge and the tick alarm with nothing to re-arm them unless this receiver also handles that action.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                when (action) {
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> handleBoot(context)
                    Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> handleClockChange(context, action)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * F3: whenever a night state exists at all, tracking must resume - the ONLY thing that stays conditional
     * is the phone alarm's own (re)arming. The old code returned early, orphaning the whole night, in two real
     * cases: the phone was off past the alarm time (AlarmManager alarms do not survive a reboot, so the alarm
     * never even had a chance to fire - the very first tick pulling a stale wakeAt forward to now + minAlarmLead,
     * D8, is exactly what should happen instead), and a reboot moments after the owner tapped Stop with the
     * night meant to continue (D6) - nap detection, the nudge, and ever reaching FINISHED all died with it.
     * [shouldArmPhoneAlarm] already refuses a null, past, or already-fired wakeAt (an old-build state file's
     * plan with `wakeAt = null` included), so it is safe to call unconditionally here too. [shouldResumeNightServiceOnBoot]
     * is [state] != null spelled out as its own decision (mirroring shouldArmPhoneAlarm), so a plain JVM test
     * can pin that ticking resumes regardless of what `plan.wakeAt` says, without needing this function's own
     * Context/AlarmManager plumbing.
     *
     * G4/H5: a pending out-of-bed nudge (armed but not yet fired when the reboot or app update happened) is
     * restored the same way, from its own persisted instant - see OutOfBedNudgeStore.kt. H5: restored
     * INDEPENDENTLY of whether a night state exists, unlike everything else in this function - G1's
     * FINISHED-bookkeeping deliberately clears the night state while leaving an already-armed nudge alone (it
     * must still fire), so a reboot in that window used to hit the early return below and never re-arm it.
     * The nudge's own H7.3 pre-check has no such recovery (see OutOfBedPreNudgeCheck.kt) - losing it is fine,
     * since its own fail-open rule already treats "no check happened" the same as "the check found nothing".
     */
    private suspend fun handleBoot(context: Context) {
        val state = withContext(Dispatchers.IO) { loadNightState(context) }
        restorePendingOutOfBedNudge(context, state)
        if (!shouldResumeNightServiceOnBoot(state)) return
        val nonNullState = requireNotNull(state)
        val plan = nonNullState.lastPlan
        // T4: virtual - compared against plan.wakeAt, which is night state.
        val now = nowInstant()
        if (plan != null && shouldArmPhoneAlarm(plan.wakeAt, now, nonNullState.phoneAlarmFiredFor)) {
            val wakeAt = requireNotNull(plan.wakeAt)
            // J5 (reviewer note, 2026-09-21): schedulePhoneAlarm's own result was DISCARDED here, so a boot
            // while the exact-alarm permission is revoked left this receiver believing it had re-armed the
            // night's alarm when nothing was armed at all - the same class of mistake as the reverted J4 guard
            // (treating an intention as evidence). Logged now, so a missed alarm after a reboot is diagnosable
            // from the night log alone, without adb. Nothing else changes: the ordinary tick this function
            // starts below retries arming every tick anyway (see armPhoneAlarmIfNeeded's own doc), which is
            // still the only recovery there is.
            if (!schedulePhoneAlarm(context, wakeAt, alarmLabelFor(plan.mode, wakeAt, nonNullState.morningAlarmAt))) {
                logBootEvent(
                    context, nonNullState, now, "error",
                    mapOf("step" to "phone_alarm", "cause" to "could not re-arm $wakeAt after boot - exact alarm permission was likely revoked, the next tick will retry")
                )
            }
        }
        startServiceForTickSafely(context, nonNullState)
    }

    /**
     * H5: re-arms a pending out-of-bed nudge that survived a reboot or app update, whether or not a night state
     * also survived - see [handleBoot]'s own doc.
     *
     * J5 (owner-reported, 2026-09-21) ADDS the OVERDUE case, which this used to drop outright by restoring only
     * an instant still in the future. Traced sequence: the morning alarm fires at 06:30 and persists a nudge for
     * 06:45; the phone powers off at 06:40 and boots at 06:46; the band's last reading is awake from 06:31 and
     * no further sleep happens. The nudge is not restored because its time has passed, and the awake path
     * produces no wake target of its own either (the morning alarm has already fired), so the promised follow-up
     * simply never rings. This predates the J-series entirely.
     *
     * Decision: an overdue nudge RINGS shortly after boot, bounded, rather than being dropped. See
     * [restoredOutOfBedNudgeAt] for the bound and for why that direction was chosen. The re-armed instant is
     * persisted back over the old one so the file never describes an alarm that does not exist (the inverse of
     * the stale-future-instant problem OutOfBedNudgeStore.kt's own doc describes), and a nudge too stale to ring
     * has its record cleared instead of left to linger until the night ends.
     *
     * The nudge's own H7.3 pre-check is deliberately NOT re-scheduled here, overdue or not - unchanged from
     * before J5, and for the reason [handleBoot]'s own doc already gives: the check has no reboot recovery, and
     * its fail-open rule treats "no check happened" the same as "the check found nothing".
     */
    private fun restorePendingOutOfBedNudge(context: Context, state: NightState?) {
        // T4: virtual - compared against pendingNudgeAt, which was persisted as a virtual instant (T5).
        val now = nowInstant()
        val pendingNudgeAt = readOutOfBedNudgePendingAt(context) ?: return
        val config = resolveEngineConfig(state?.debugOptions ?: DebugOptions())
        val restoreAt = restoredOutOfBedNudgeAt(pendingNudgeAt, now, config)
        if (restoreAt == null) {
            logBootEvent(
                context, state, now, "out_of_bed_nudge_dropped",
                mapOf("pendingNudgeAt" to pendingNudgeAt.toString(), "cause" to "more than ${config.outOfBedDelay.toMinutes()} min overdue by the time the phone booted - too stale to still be a follow-up to the alarm that armed it")
            )
            clearOutOfBedNudgePendingAt(context)
            return
        }
        if (!scheduleOutOfBedAlarm(context, restoreAt)) {
            logBootEvent(
                context, state, now, "error",
                mapOf("step" to "out_of_bed_alarm", "cause" to "could not restore the pending nudge at $restoreAt after boot - exact alarm permission was likely revoked")
            )
            return
        }
        if (restoreAt == pendingNudgeAt) return
        logBootEvent(
            context, state, now, "out_of_bed_nudge_restored_late",
            mapOf("pendingNudgeAt" to pendingNudgeAt.toString(), "restoredAt" to restoreAt.toString())
        )
        if (!saveOutOfBedNudgePendingAt(context, restoreAt)) {
            logBootEvent(
                context, state, now, "error",
                mapOf("step" to "save_night_state", "cause" to "restored the overdue nudge at $restoreAt but could not persist the new instant - the screen may still show $pendingNudgeAt")
            )
        }
    }

    /** H5: a boot-time event is only loggable when a night state survived to carry a `startedAt` to log against - see [handleBoot]'s own H5 note for why the nudge restore itself must run regardless. */
    private fun logBootEvent(context: Context, state: NightState?, at: Instant, event: String, fields: Map<String, String>) {
        if (state == null) return
        appendNightLog(context, state.startedAt, NightLogEvent(at, event, fields), state.debugOptions.isAnyEnabled)
    }

    /** G7: startForegroundService can throw ForegroundServiceStartNotAllowedException. BOOT_COMPLETED and MY_PACKAGE_REPLACED are both on Android's exemption list so this should not fire, but if it ever does the night must not die with nothing written anywhere the owner would look - PhoneAlarmReceiver's own equivalent guard around AlarmRingService is this function's model. An exception here would otherwise propagate out of receiverScope.launch's coroutine with no handler, reaching the thread's default handler rather than the night log. */
    private fun startServiceForTickSafely(context: Context, state: NightState) {
        try {
            startNightServiceForTick(context)
        } catch (error: Exception) {
            appendNightLog(
                context, state.startedAt,
                NightLogEvent(nowInstant(), "error", mapOf("step" to "boot_service_start", "cause" to (error.message ?: error.toString()))),
                state.debugOptions.isAnyEnabled
            )
        }
    }

    private suspend fun handleClockChange(context: Context, action: String?) {
        val state = withContext(Dispatchers.IO) { loadNightState(context) } ?: return
        appendNightLog(context, state.startedAt, NightLogEvent(nowInstant(), "clock_changed", mapOf("action" to (action ?: "unknown"))), state.debugOptions.isAnyEnabled)
        requestImmediateTick(context)
    }
}

/**
 * F3: whenever a night state exists at all, tracking must resume - true whenever [state] is non-null,
 * regardless of what its plan's own `wakeAt` says (already in the past, or null on an old-build state file
 * decoded before D1 existed). Extracted as its own decision, mirroring [shouldArmPhoneAlarm], so the two
 * reboot-recovery cases F3 fixed can be pinned by a plain JVM test without needing [handleBoot]'s own
 * Context/AlarmManager plumbing.
 */
internal fun shouldResumeNightServiceOnBoot(state: NightState?): Boolean = state != null

/**
 * J5 (owner-reported, 2026-09-21): the instant a pending out-of-bed nudge should actually be re-armed at after
 * a reboot or app update, or null to drop it deliberately. `internal`, not `private`: the one decision that
 * matters here, JVM-testable without [BootReceiver]'s own Context/AlarmManager plumbing, mirroring
 * [shouldArmPhoneAlarm] and [shouldResumeNightServiceOnBoot].
 *
 * Three cases:
 *  - [pendingNudgeAt] still in the future: returned UNCHANGED. The alarm simply did not survive the reboot
 *    (F13), and re-arming it for its own original instant is all that was ever needed. Unchanged behaviour.
 *  - overdue by at most [EngineConfig.outOfBedDelay]: re-armed at `now + minAlarmLead`, so it rings shortly
 *    after boot instead of not at all.
 *  - overdue by more than that: null. Dropped on purpose.
 *
 * WHY RING RATHER THAN DROP, for the bounded case. The nudge is a PROMISE made by an alarm that already rang:
 * "if you are still in bed in 15 minutes, I will ring again". The traced sequence that exposed this (alarm
 * 06:30, nudge 06:45, phone off 06:40, boot 06:46, band reading awake throughout) leaves nothing else able to
 * ring at all - the wake alarm has already fired, so rule 7's AWAKE branch produces no target either (see
 * WakeAlarm.kt's `napAlarm` and H8's own `morningAlarmAlreadyRang`), and the only other things that ever arm a
 * nudge are a firing, this restore, and a speed change. With a deadline set, the night then reaches FINISHED in
 * silence. This app's standing direction on exactly this trade is that a missed ring is worse than an extra
 * one (F1's own non-idempotent ring, D8's pull-forward recovery, the J5 revert this round), so the nudge rings.
 *
 * WHY BOUNDED, and why this particular bound. An unbounded restore is wrong in the other direction: a phone
 * left off for hours would boot into a nudge belonging to a night long over, with no explanation attached to
 * it. [EngineConfig.outOfBedDelay] is the natural bound because it is the nudge's OWN unit of "long enough to
 * still be in bed": once more than another whole delay has passed since it came due, more than half an hour
 * separates it from the alarm it was following up, and it has stopped being a follow-up and become an
 * unexplained alarm. Its record is cleared at the same time, so nothing stale lingers on screen.
 *
 * WHY `now + minAlarmLead` RATHER THAN IMMEDIATELY. Android fires a past exact alarm immediately, which here
 * would mean ringing in the middle of the boot storm, before the screen, the audio stack and the foreground
 * service path are reliably up. [EngineConfig.minAlarmLead] is the same smallest-safe-distance this codebase
 * already arms everything else at (D8's own pull-forward uses it), so there is no new constant and no new
 * judgement call.
 */
internal fun restoredOutOfBedNudgeAt(pendingNudgeAt: Instant, now: Instant, config: EngineConfig): Instant? = when {
    pendingNudgeAt.isAfter(now) -> pendingNudgeAt
    now.isAfter(pendingNudgeAt.plus(config.outOfBedDelay)) -> null
    else -> now.plus(config.minAlarmLead)
}
