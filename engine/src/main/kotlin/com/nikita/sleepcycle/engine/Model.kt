package com.nikita.sleepcycle.engine

import java.time.Duration
import java.time.Instant

/** The kind of one band-reported interval: light or deep sleep, or awake. */
enum class SegmentKind { LIGHT, DEEP, AWAKE }

/** A single raw band mark. May be unsorted, overlapping, or dated after `now` (clock skew); the engine cleans it. */
data class SleepSegment(val start: Instant, val end: Instant, val kind: SegmentKind)

/**
 * One continuous run of sleep, after [normalizeSegments] and [buildSleepStretches] have cleaned the timeline.
 * [followsAwakening] is true when a recorded awake interval separates this stretch from an earlier one (rule 6).
 */
data class SleepStretch(val onset: Instant, val end: Instant, val followsAwakening: Boolean)

/** The band-derived state as of `now`, per rule 6's meaningful-awakening boundary. */
enum class SleepState { NOT_YET_ASLEEP, ASLEEP, AWAKE }

/** Which alarm rule produced the current plan. [FINISHED] is an amendment layered on top of rules 1-7. */
enum class AlarmMode { FULL_CYCLES, DEADLINE_ONLY, NAP, FINISHED }

/** The picker choice for one night (rule 2), and the optional deadline (rules 4, 5). */
data class NightSettings(
    val deadline: Instant?,
    val pickedCycles: Int
)

/**
 * The engine's decision for one sync. `referenceOnset` is null exactly when [mode] is [AlarmMode.FINISHED]:
 * the night is over, there is nothing left to plan. `wakeAt` is null in that same case, and - since F5 - also
 * in the one case where [mode] is [AlarmMode.NAP] but rule 7's AWAKE safety net has nothing left to arm once
 * the wake alarm has already fired (see WakeAlarm.kt's `napAlarm`), and - since H8 - in the matching case for
 * rules 3/4/5/6, where the morning alarm this plan would name has ALREADY rung (see WakeAlarm.kt's
 * `morningAlarmAlreadyRang`): a caller must not assume a non-FINISHED mode always carries an alarm. Otherwise `wakeAt` is the single alarm for the night: every tick arms the
 * phone at this instant (D1), the band is a sensor only. `reason` is one plain-English sentence for the night
 * log.
 *
 * [sleptSoFar] and [owedCycles] are the two numbers rule 2's night total is decided from (see
 * `sumSleepAlreadyHad` and `countOwedCycles`), carried on the plan so the night log can record them as fields
 * rather than only inside [reason]'s prose: [cycles] alone cannot say whether the deadline or the total bound.
 */
data class AlarmPlan(
    val mode: AlarmMode,
    val wakeAt: Instant?,
    val cycles: Int,
    val referenceOnset: Instant?,
    val onsetIsProjected: Boolean,
    val reason: String,
    /** Sleep already had tonight, excluding the stretch this plan's alarm is measured from. */
    val sleptSoFar: Duration = Duration.ZERO,
    /** Whole cycles still owed of the picked total, BEFORE any deadline cap ([cycles] is after it). */
    val owedCycles: Int = 0
)

/** One entry in the wake-time picker: waking after [cycles] cycles, at [wakeTime], having slept [sleepDuration]. */
data class WakeOption(val cycles: Int, val wakeTime: Instant, val sleepDuration: Duration)

/**
 * One stretch's summary for the morning report: its span, [duration], and [cycles] as a decimal
 * (duration divided by cycleLength).
 */
data class StretchSummary(val onset: Instant, val end: Instant, val duration: Duration, val cycles: Double)

/** The whole night's summary: total retained sleep, plus the per-stretch breakdown. */
data class NightSummary(val totalSleep: Duration, val stretches: List<StretchSummary>)

/** Every tunable constant the engine uses, so nothing is a magic number and everything can be calibrated. */
data class EngineConfig(
    val cycleLength: Duration = Duration.ofMinutes(90),
    val napLength: Duration = Duration.ofMinutes(20),
    val fallAsleepEstimate: Duration = Duration.ofMinutes(15),
    val minAwakening: Duration = Duration.ofMinutes(1),
    val minAlarmLead: Duration = Duration.ofMinutes(2),
    val allowedCycleCounts: Set<Int> = setOf(3, 4, 5, 6),
    val nearAlarmSyncWindow: Duration = Duration.ofMinutes(30),
    val frequentSyncDelay: Duration = Duration.ofMinutes(5),
    val normalSyncDelay: Duration = Duration.ofMinutes(15),
    /**
     * D4/H7.1: how long after the wake alarm (or a D5/rule 7 nap alarm) fires before the out-of-bed nudge
     * rings. Armed by the app layer when that alarm fires, not by the engine - kept here so a fast debug
     * night can still shorten it like every other timing constant. H7.1 (owner decision, 2026-09-20): raised
     * from 10 to 15 minutes.
     */
    val outOfBedDelay: Duration = Duration.ofMinutes(15),
    /**
     * F1: how long AlarmRingService lets the ring sound before auto-stopping if nobody touches it, and the
     * timeout on the ringing wake lock (AlarmWakeLock) - not read by the engine itself, kept here purely so a
     * fast debug night can shorten it too, and so [validateConfig] can enforce it stays strictly less than
     * [outOfBedDelay]. The two used to both be 10 minutes, measured from different instants (this one from
     * when ringing actually starts, outOfBedDelay's nudge from when the firing broadcast was received) - close
     * enough that the nudge routinely fired a moment before this auto-stop tore the still-ringing service
     * down, swallowing it. A real, enforced margin instead of a coincidence.
     */
    val ringAutoStopAfter: Duration = Duration.ofMinutes(9),
    /**
     * H7.3 (owner decision, 2026-09-20): how long before the out-of-bed nudge is due to ring the app-layer
     * pre-nudge check re-syncs the band and asks whether the owner is asleep right now. Not read by the
     * engine itself (the check and its own decision are app-layer, same as the nudge - see
     * PhoneAlarmReceiver/OutOfBedPreNudgeCheck.kt); kept here so a fast debug night can shorten it too, and so
     * [validateConfig] can enforce it stays strictly less than [outOfBedDelay] - the check must land strictly
     * between the nudge being armed and it firing, never before or after either.
     */
    val preNudgeCheckLead: Duration = Duration.ofMinutes(2)
)
