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

/** Which alarm rule produced the current plan. [OVERDUE] and [FINISHED] are amendments layered on top of rules 1-7. */
enum class AlarmMode { FULL_CYCLES, DEADLINE_ONLY, NAP, OVERDUE, FINISHED }

/** The picker choice for one night (rule 2), the optional deadline (rules 4, 5), and the phone backup toggle. */
data class NightSettings(
    val deadline: Instant?,
    val pickedCycles: Int,
    val phoneBackupEnabled: Boolean
)

/**
 * The engine's decision for one sync. `bandAlarm` and `referenceOnset` are null exactly when [mode] is
 * [AlarmMode.FINISHED]: the night is over, there is nothing left to plan. `reason` is one plain-English
 * sentence for the night log. `overdueSince` is null unless [mode] is [AlarmMode.OVERDUE]: the band alarm that was missed and started the
 * overdue period (the overdue rule's `maxOverdueDuration` cap counts from this instant). Added last with a
 * default so existing positional call sites keep compiling.
 *
 * [sleptSoFar] and [owedCycles] are the two numbers rule 2's night total is decided from (see
 * `sumSleepAlreadyHad` and `countOwedCycles`), carried on the plan so the night log can record them as fields
 * rather than only inside [reason]'s prose: [cycles] alone cannot say whether the deadline or the total bound.
 */
data class AlarmPlan(
    val mode: AlarmMode,
    val bandAlarm: Instant?,
    val phoneAlarm: Instant?,
    val cycles: Int,
    val referenceOnset: Instant?,
    val onsetIsProjected: Boolean,
    val reason: String,
    val overdueSince: Instant? = null,
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
    val phoneBackupOffset: Duration = Duration.ofMinutes(15),
    val minAwakening: Duration = Duration.ofMinutes(1),
    val minAlarmLead: Duration = Duration.ofMinutes(2),
    val allowedCycleCounts: Set<Int> = setOf(3, 4, 5, 6),
    val nearAlarmSyncWindow: Duration = Duration.ofMinutes(30),
    val frequentSyncDelay: Duration = Duration.ofMinutes(5),
    val normalSyncDelay: Duration = Duration.ofMinutes(15),
    val maxOverdueDuration: Duration = Duration.ofMinutes(30)
)
