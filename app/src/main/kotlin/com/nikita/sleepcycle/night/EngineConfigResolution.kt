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
 * tick that detects it, so the tester actually sees the NAP card - and the band/phone alarm firing - before
 * OVERDUE, instead of landing on OVERDUE the instant sleep resumes.
 */
private val FAST_NIGHT_NAP_LENGTH: Duration = Duration.ofMinutes(3)

/** Assumed time to fall asleep from "now" when not asleep yet. */
private val FAST_NIGHT_FALL_ASLEEP_ESTIMATE: Duration = Duration.ofMinutes(1)

/** Phone backup offset after the band alarm, no-deadline case. */
private val FAST_NIGHT_PHONE_BACKUP_OFFSET: Duration = Duration.ofMinutes(1)

/** Awake marks shorter than this do not split a stretch. */
private val FAST_NIGHT_MIN_AWAKENING: Duration = Duration.ofSeconds(10)

/** A band alarm is never set closer to "now" than this. */
private val FAST_NIGHT_MIN_ALARM_LEAD: Duration = Duration.ofMinutes(1)

/** Inside this window before the band alarm, sync faster. */
private val FAST_NIGHT_NEAR_ALARM_SYNC_WINDOW: Duration = Duration.ofMinutes(2)

/** Sync cadence close to the alarm. */
private val FAST_NIGHT_FREQUENT_SYNC_DELAY: Duration = Duration.ofMinutes(1)

/** Sync cadence away from the alarm. */
private val FAST_NIGHT_NORMAL_SYNC_DELAY: Duration = Duration.ofMinutes(1)

/** The overdue period cap, from the missed band alarm. */
private val FAST_NIGHT_MAX_OVERDUE_DURATION: Duration = Duration.ofMinutes(5)

/** The picker options stay the same set (3/4/5/6 cycles) fast or real - only the cycle length changes what they mean in wall-clock minutes. */
private val FAST_NIGHT_ENGINE_CONFIG = EngineConfig(
    cycleLength = FAST_NIGHT_CYCLE_LENGTH,
    napLength = FAST_NIGHT_NAP_LENGTH,
    fallAsleepEstimate = FAST_NIGHT_FALL_ASLEEP_ESTIMATE,
    phoneBackupOffset = FAST_NIGHT_PHONE_BACKUP_OFFSET,
    minAwakening = FAST_NIGHT_MIN_AWAKENING,
    minAlarmLead = FAST_NIGHT_MIN_ALARM_LEAD,
    nearAlarmSyncWindow = FAST_NIGHT_NEAR_ALARM_SYNC_WINDOW,
    frequentSyncDelay = FAST_NIGHT_FREQUENT_SYNC_DELAY,
    normalSyncDelay = FAST_NIGHT_NORMAL_SYNC_DELAY,
    maxOverdueDuration = FAST_NIGHT_MAX_OVERDUE_DURATION,
)

/**
 * The EngineConfig every engine call site in the app must use - the orchestrator's tick, the before-bed
 * picker, and the night screen alike - so a fast debug night is always fast everywhere at once, never fast
 * on one screen and real on another. [debugOptions] should already have passed through [resolveDebugOptions].
 */
fun resolveEngineConfig(debugOptions: DebugOptions): EngineConfig =
    if (debugOptions.fastNight) FAST_NIGHT_ENGINE_CONFIG else EngineConfig()
