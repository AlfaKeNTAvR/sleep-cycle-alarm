package com.nikita.sleepcycle.engine

// File purpose: M3 - the one shared, pure tolerance helper every "is this the alarm I already armed/rang for"
// comparison in the app now goes through (see AlarmInstantTolerance.kt's own doc for the owner's objection to
// M2's exact-equality fix and the one-second justification). Two shapes, two functions: sameAlarmInstant for
// the five equality-shaped call sites, firedAtOrBefore for the two ordering-shaped ones in WakeAlarm.kt.

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class AlarmInstantToleranceTest {
    private val base: Instant = Instant.parse("2026-09-22T02:01:18.443955Z")

    // ---- sameAlarmInstant ------------------------------------------------------------------------------------

    @Test fun `sameAlarmInstant is true for two identical instants`() {
        assertTrue(sameAlarmInstant(base, base))
    }

    @Test fun `sameAlarmInstant is true for a sub-millisecond difference, the precision loss it exists to absorb`() {
        // The actual shape M2 fixed at the source: a projected onset carrying microseconds, round-tripped
        // through AlarmManager's own millisecond-truncating epoch-milli extra.
        assertTrue(sameAlarmInstant(base, base.plusNanos(443_955_000)))
    }

    @Test fun `sameAlarmInstant is true just inside the one-second tolerance, either direction`() {
        assertTrue(sameAlarmInstant(base, base.plusMillis(999)))
        assertTrue(sameAlarmInstant(base, base.minusMillis(999)))
    }

    @Test fun `sameAlarmInstant is false exactly AT the one-second tolerance boundary`() {
        // The boundary itself counts as a different instant, not the same one - matching
        // NapAlarmCountingTest's own pinned "one second after the morning alarm is already a genuine nap" case
        // for firedAlarmIsWakeAlarm, which is built directly on this function.
        assertFalse(sameAlarmInstant(base, base.plusSeconds(1)))
        assertFalse(sameAlarmInstant(base, base.minusSeconds(1)))
    }

    @Test fun `sameAlarmInstant is false just outside the one-second tolerance`() {
        assertFalse(sameAlarmInstant(base, base.plusSeconds(1).plusMillis(1)))
    }

    @Test fun `sameAlarmInstant is false for two genuinely different targets one minute apart - the J1_2 regression, pinned at the helper level`() {
        // The owner's own night log has consecutive targets exactly one minute apart (02:04:00Z, 02:05:00Z),
        // both real, distinct alarm targets. J1.2 widened this exact comparison to a three-minute window and
        // J2 reverted it because a window that size swallowed cases like this one - see
        // ALARM_INSTANT_TOLERANCE's own doc. One second must never confuse these two.
        assertFalse(sameAlarmInstant(instant("2026-09-22T02:04:00"), instant("2026-09-22T02:05:00")))
    }

    @Test fun `sameAlarmInstant is false when either argument is null`() {
        assertFalse(sameAlarmInstant(null, base))
        assertFalse(sameAlarmInstant(base, null))
        assertFalse(sameAlarmInstant(null, null))
    }

    // ---- firedAtOrBefore ------------------------------------------------------------------------------------

    @Test fun `firedAtOrBefore is true when the candidate is exactly the fired marker`() {
        assertTrue(firedAtOrBefore(base, base))
    }

    @Test fun `firedAtOrBefore is true when the candidate is well before the fired marker, by any margin`() {
        // Unlike sameAlarmInstant this side is unbounded - a target long spent is unambiguously fired for.
        assertTrue(firedAtOrBefore(base.minusSeconds(3600), base))
    }

    @Test fun `firedAtOrBefore is true just inside the tolerance AFTER the fired marker`() {
        assertTrue(firedAtOrBefore(base.plusMillis(999), base))
    }

    @Test fun `firedAtOrBefore is false exactly AT the tolerance boundary after the fired marker`() {
        assertFalse(firedAtOrBefore(base.plusSeconds(1), base))
    }

    @Test fun `firedAtOrBefore is false just outside the tolerance after the fired marker`() {
        assertFalse(firedAtOrBefore(base.plusSeconds(1).plusMillis(1), base))
    }

    @Test fun `firedAtOrBefore is false for a genuinely later target one minute after the fired marker`() {
        // The ordering-shape mirror of the J1.2 regression test above: a target a full minute after a firing
        // is a new, unrelated target, not the same alarm read late.
        assertFalse(firedAtOrBefore(instant("2026-09-22T02:05:00"), instant("2026-09-22T02:04:00")))
    }

    @Test fun `firedAtOrBefore is false when the fired marker is null - nothing has fired yet`() {
        assertFalse(firedAtOrBefore(base, null))
    }
}
