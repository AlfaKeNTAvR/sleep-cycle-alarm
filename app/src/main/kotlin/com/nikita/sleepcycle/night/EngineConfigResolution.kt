package com.nikita.sleepcycle.night

// File purpose: the ONE function that supplies the EngineConfig for every engine call site in the app - the
// orchestrator and the UI support functions alike - so a fast debug night and the real night can never
// disagree about which timing the screens and the service are using. The fast-night numbers are named
// constants here, in one place, each checked against every field engine.validateConfig requires.

import com.nikita.sleepcycle.engine.EngineConfig
import java.time.Duration

/** One sleep cycle, shortened for a desk test that should finish in minutes rather than hours. */
private val FAST_NIGHT_CYCLE_LENGTH: Duration = Duration.ofMinutes(5)

/**
 * The nap after falling back asleep (rule 7), shortened to match [FAST_NIGHT_CYCLE_LENGTH]. Chosen so that
 * napLength minus the sync delay is still at least [FAST_NIGHT_MIN_ALARM_LEAD] (see
 * `EngineConfigResolutionTest.the fast nap length leaves at least one sync's worth of lead before the alarm`):
 * a nap only detected one sync delay late still has its own alarm (onset + this) at least a lead ahead of the
 * tick that detects it, so the tester actually sees the NAP card - and the phone alarm firing - on time,
 * instead of the alarm having to be pulled forward the instant sleep resumes (D8).
 */
private val FAST_NIGHT_NAP_LENGTH: Duration = Duration.ofMinutes(3)

/** Assumed time to fall asleep from "now" when not asleep yet. */
private val FAST_NIGHT_FALL_ASLEEP_ESTIMATE: Duration = Duration.ofMinutes(1)

/** Awake marks shorter than this do not split a stretch. */
private val FAST_NIGHT_MIN_AWAKENING: Duration = Duration.ofSeconds(10)

/** The phone alarm is never armed closer to "now" than this (D8). */
private val FAST_NIGHT_MIN_ALARM_LEAD: Duration = Duration.ofMinutes(1)

/** Inside this window before the phone alarm, sync faster. */
private val FAST_NIGHT_NEAR_ALARM_SYNC_WINDOW: Duration = Duration.ofMinutes(2)

/** Sync cadence close to the alarm. */
private val FAST_NIGHT_FREQUENT_SYNC_DELAY: Duration = Duration.ofMinutes(1)

/** Sync cadence away from the alarm. */
private val FAST_NIGHT_NORMAL_SYNC_DELAY: Duration = Duration.ofMinutes(1)

/** D4/H7.1: the out-of-bed nudge, shortened so a debug tester sees it ring without a real 15 min wait. H7.1 kept this proportionate to the real 15 min value at the same 1:20 ratio the original 10 min:30 s pair used. */
private val FAST_NIGHT_OUT_OF_BED_DELAY: Duration = Duration.ofSeconds(45)

/** F1: AlarmRingService's auto-stop, shortened to stay strictly less than [FAST_NIGHT_OUT_OF_BED_DELAY] - see EngineConfig.ringAutoStopAfter's own doc for why that must hold. */
private val FAST_NIGHT_RING_AUTO_STOP_AFTER: Duration = Duration.ofSeconds(20)

/** H7.3: the pre-nudge check's own lead time, shortened to stay strictly less than [FAST_NIGHT_OUT_OF_BED_DELAY] - see EngineConfig.preNudgeCheckLead's own doc for why that must hold. */
private val FAST_NIGHT_PRE_NUDGE_CHECK_LEAD: Duration = Duration.ofSeconds(10)

/** The picker options stay the same set (3/4/5/6 cycles) fast or real - only the cycle length changes what they mean in wall-clock minutes. */
private val FAST_NIGHT_ENGINE_CONFIG = EngineConfig(
    cycleLength = FAST_NIGHT_CYCLE_LENGTH,
    napLength = FAST_NIGHT_NAP_LENGTH,
    fallAsleepEstimate = FAST_NIGHT_FALL_ASLEEP_ESTIMATE,
    minAwakening = FAST_NIGHT_MIN_AWAKENING,
    minAlarmLead = FAST_NIGHT_MIN_ALARM_LEAD,
    nearAlarmSyncWindow = FAST_NIGHT_NEAR_ALARM_SYNC_WINDOW,
    frequentSyncDelay = FAST_NIGHT_FREQUENT_SYNC_DELAY,
    normalSyncDelay = FAST_NIGHT_NORMAL_SYNC_DELAY,
    outOfBedDelay = FAST_NIGHT_OUT_OF_BED_DELAY,
    ringAutoStopAfter = FAST_NIGHT_RING_AUTO_STOP_AFTER,
    preNudgeCheckLead = FAST_NIGHT_PRE_NUDGE_CHECK_LEAD,
)

/**
 * The EngineConfig every engine call site in the app must use - the orchestrator's tick, the before-bed
 * picker, and the night screen alike - so a fast debug night is always fast everywhere at once, never fast
 * on one screen and real on another. [debugOptions] should already have passed through [resolveDebugOptions].
 */
fun resolveEngineConfig(debugOptions: DebugOptions): EngineConfig =
    if (debugOptions.fastNight) FAST_NIGHT_ENGINE_CONFIG else EngineConfig()
