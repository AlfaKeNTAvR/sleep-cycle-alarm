package com.nikita.sleepcycle.night

// File purpose: P6 (2026-11-05) - when a night calls the band "not measuring heart rate". With the bedtime test
// on Warn only, a night can start before the band has taken any reading; the first tick would then call that
// "not measuring" at once. So the Night screen warning and the silent notification wait until the night is
// [BAND_NOT_MEASURING_GRACE] old. Pure; the alarm's own stale-data path (decideTickPlan) is not affected.

import com.nikita.sleepcycle.bridge.BAND_DATA_FRESHNESS_THRESHOLD
import com.nikita.sleepcycle.bridge.StaleReason
import java.time.Duration
import java.time.Instant

/**
 * How long into a night a band with no real heart-rate value is not yet called "not measuring". The same 30 min
 * as the freshness threshold: a band that measures at all has a real value within it.
 */
val BAND_NOT_MEASURING_GRACE: Duration = BAND_DATA_FRESHNESS_THRESHOLD

/**
 * Whether a sync verdict of [staleReason], made at [verdictAt] in a night started at [startedAt], counts as the
 * band not measuring: [StaleReason.NO_HEART_RATE_VALUES], at least [BAND_NOT_MEASURING_GRACE] into the night.
 * False with no verdict yet.
 */
fun isBandNotMeasuringWarned(staleReason: StaleReason?, startedAt: Instant, verdictAt: Instant?): Boolean =
    staleReason == StaleReason.NO_HEART_RATE_VALUES && verdictAt != null &&
        Duration.between(startedAt, verdictAt) >= BAND_NOT_MEASURING_GRACE
