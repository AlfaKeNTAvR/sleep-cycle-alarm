package com.nikita.sleepcycle.night

// File purpose: what the band should actually be carrying right now, which is not always what the engine's
// plan asks for. Night 1 (2026-09-18) moved the band alarm 14 times across a 77 min spread, because every
// short awakening the band marked put the engine back on a PROJECTED onset (now + fallAsleepEstimate) that
// slides forward with the clock, and the owed-cycle count flipped between 2 and 3 as it slid. Each tick's new
// target was a fresh write to the band.
//
// The rule: while the sleeper is not ASLEEP, an existing band alarm commitment is HELD, not retargeted. The
// band keeps what it has. A commitment is only moved once the band reports a real, non-projected onset, and
// the plan computed from it differs. A brand-new commitment (nothing requested or confirmed yet) is still sent
// off a projected onset, exactly as before, or the first alarm of the night would never exist.
//
// This is a band-write rule and lives here, in the app's band alarm decision layer, NOT in the engine. Why:
// the engine is one pure function of (segments, settings, now, previousPlan) and its plan drives the phone
// alarm and the whole Night screen as well, both of which SHOULD keep following the projected onset - the
// screen has to show what would happen if you fell asleep now, and the phone alarm is the safety net that must
// stay ahead of the band. Only the band write is expensive and only the band write is the thing that goes
// stale. Teaching the engine the difference would mean handing it the band's commitment (which slot holds
// which minute, whether it was confirmed by read-back) - Android-layer state it is deliberately free of - or
// having it emit two different alarm times. The one thing the engine does owe us is on the plan already:
// AlarmPlan.referenceOnset and AlarmPlan.onsetIsProjected say which onset a target was computed from.
//
// NAP while awake is deliberately exempt (rule 7, decisions.md): its target is `now + napLength`, kept ahead
// of the clock on purpose and locked the moment sleep is detected, so that a sync delay can never stretch the
// nap past 20 min. Freezing it at the first awake tick would put the nap alarm in the past by the time the
// owner actually fell back asleep - the exact failure the sliding was built to prevent. It moves forward only,
// by one sync interval at a time, never by an hour, and never backwards.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.AlarmPlan
import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.engine.SleepState
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * What the band should carry this tick. Returns [AlarmPlan.bandAlarm] unchanged in every case except the one
 * this file exists for: a commitment already exists ([heldCommitment], the requested-or-confirmed one), the
 * sleeper is not [SleepState.ASLEEP], the mode is not [AlarmMode.NAP], and the held minute still belongs to
 * tonight - then the held minute is returned instead, so the tick decides no move at all.
 *
 * The held minute is resolved back to an instant as the next occurrence of that local hour:minute at or after
 * [now], which is exactly how the band itself reads a slot that only stores hour and minute.
 */
fun resolveDesiredBandAlarm(
    plan: AlarmPlan,
    sleepState: SleepState,
    heldCommitment: BandAlarmCommitment?,
    now: Instant,
    zone: ZoneId,
    config: EngineConfig = EngineConfig()
): Instant? {
    val plannedBandAlarm = plan.bandAlarm ?: return null
    if (heldCommitment == null) return plannedBandAlarm
    if (sleepState == SleepState.ASLEEP) return plannedBandAlarm
    if (plan.mode == AlarmMode.NAP) return plannedBandAlarm
    val heldBandAlarm = nextLocalOccurrence(heldCommitment.hour, heldCommitment.minute, now, zone)
    // A held minute whose next occurrence is further out than the longest night this app could ever plan has
    // already passed today and wrapped to tomorrow: the band either buzzed at it or never had it, and holding
    // a tomorrow-morning alarm would leave the rest of tonight with nothing on the band at all.
    if (Duration.between(now, heldBandAlarm) > longestPlannableNight(config)) return plannedBandAlarm
    return heldBandAlarm
}

/** The furthest ahead the engine can ever put a band alarm: the longest picked total, measured from an onset it has only projected. */
private fun longestPlannableNight(config: EngineConfig): Duration =
    config.cycleLength.multipliedBy(config.allowedCycleCounts.max().toLong()).plus(config.fallAsleepEstimate)

/** True when [resolveDesiredBandAlarm] held an existing commitment instead of following [plan], for the night log's `band_alarm_held` line. */
fun isBandAlarmHeld(plan: AlarmPlan, desiredBandAlarm: Instant?): Boolean =
    plan.bandAlarm != null && desiredBandAlarm != null && desiredBandAlarm != plan.bandAlarm

/**
 * The first instant at or after [now] whose local time in [zone] is [hour]:[minute] - the band stores only
 * hour and minute, so this is the only meaning a stored slot has. Seconds are zero, as the band's own alarm is.
 */
internal fun nextLocalOccurrence(hour: Int, minute: Int, now: Instant, zone: ZoneId): Instant {
    val localNow = now.atZone(zone)
    val today = localNow.toLocalDate().atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant()
    if (!today.isBefore(now)) return today
    return localNow.toLocalDate().plusDays(NEXT_DAY_OFFSET).atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant()
}

/** Days to add when the stored hour:minute has already passed today, so it means tomorrow instead. */
private const val NEXT_DAY_OFFSET = 1L
