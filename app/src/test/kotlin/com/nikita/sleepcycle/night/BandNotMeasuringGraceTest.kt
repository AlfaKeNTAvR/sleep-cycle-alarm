package com.nikita.sleepcycle.night

// File purpose: P6 (2026-11-05) - a band that has simply not measured yet at Start night is not called "not
// measuring" during the night's first 30 min, so the Night screen warning and the silent notification come
// about 30 min in, not on the first tick.

import com.nikita.sleepcycle.bridge.StaleReason
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class BandNotMeasuringGraceTest {
    private val startedAt = Instant.parse("2026-11-05T22:30:00Z")

    @Test
    fun `no real heart rate on the first ticks is not yet a warning`() {
        assertFalse(isBandNotMeasuringWarned(StaleReason.NO_HEART_RATE_VALUES, startedAt, startedAt))
        assertFalse(isBandNotMeasuringWarned(StaleReason.NO_HEART_RATE_VALUES, startedAt, startedAt.plus(Duration.ofMinutes(29))))
    }

    @Test
    fun `still none 30 min into the night is a warning`() {
        assertTrue(isBandNotMeasuringWarned(StaleReason.NO_HEART_RATE_VALUES, startedAt, startedAt.plus(Duration.ofMinutes(30))))
        assertTrue(isBandNotMeasuringWarned(StaleReason.NO_HEART_RATE_VALUES, startedAt, startedAt.plus(Duration.ofHours(3))))
    }

    @Test
    fun `other stale reasons, fresh data and no sync yet are never this warning`() {
        val late = startedAt.plus(Duration.ofHours(1))
        assertFalse(isBandNotMeasuringWarned(StaleReason.NO_NEW_SAMPLES, startedAt, late))
        assertFalse(isBandNotMeasuringWarned(null, startedAt, late))
        assertFalse(isBandNotMeasuringWarned(StaleReason.NO_HEART_RATE_VALUES, startedAt, verdictAt = null))
    }
}
