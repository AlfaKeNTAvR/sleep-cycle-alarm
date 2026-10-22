package com.nikita.sleepcycle.night

// File purpose: owner spec, 2026-10-21 (PER-126) - the one rule that decides whether ending a night discards it.
// A night started by mistake, or with the wrong setting, should be cheap to undo: under an hour after Start
// night, End night and I'm up both discard the night (no morning report, no rating question, no log kept unless
// it was a simulation) instead of ending it. Pure, so the rule is tested on its own (ShortNightTest.kt); the
// Night screen's dialogs (BuildNightUiState.kt) and the teardown (NightController.discardNight) both read it here.

import java.time.Duration
import java.time.Instant

/** Owner spec, 2026-10-21: a night ended less than this long after Start night is discarded. */
val SHORT_NIGHT_DISCARD_LIMIT: Duration = Duration.ofHours(1)

/**
 * Whether a night started at [startedAt] and ended at [now] is short enough to discard. Counts from Start night
 * (NightState.startedAt), not from falling asleep (default chosen with the owner, 2026-10-21), and on the
 * night's own clock: both instants are virtual, so a simulated night measures its hour on the simulated clock.
 */
fun isShortNight(startedAt: Instant, now: Instant): Boolean =
    Duration.between(startedAt, now) < SHORT_NIGHT_DISCARD_LIMIT

/** Owner spec, 2026-10-21: a discarded night's log is deleted, unless the night was a simulation (any debug switch on, the same test that names its log `night-sim-`). */
fun keepsDiscardedNightLog(debugOptions: DebugOptions): Boolean = debugOptions.isAnyEnabled
