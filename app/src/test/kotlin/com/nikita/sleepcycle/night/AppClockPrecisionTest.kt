package com.nikita.sleepcycle.night

// File purpose: M2 (owner-reported, 2026-09-21) - pins AppClock.now()'s own millisecond truncation, the fix for
// the alarm that rang twice on the night of 2026-09-21/22 (see docs/decisions.md's M2 record for the night log
// evidence). AppClock is a plain object with no Context dependency (setWarp is a no-op only outside a debug
// build, per BuildConfig.DEBUG, which is true under :app:testDebugUnitTest), so it is directly JVM-testable,
// unlike the Context-bound call sites this fix protects (PhoneAlarmScheduler/PhoneAlarmReceiver's own
// AlarmManager round trip, exercised only on the phone - see app-spec.md's own Tests section on what stays
// Android-only).
//
// This is deliberately NOT a test of computeWakeAlarm/firedAlarmIsWakeAlarm/shouldArmPhoneAlarm with hand-fed
// sub-millisecond instants: M2's fix does not touch any of those comparisons, it stops a sub-millisecond instant
// from ever reaching them in the first place. A test that feeds one to those functions directly would fail
// identically before AND after M2 (they are unchanged), which would not be a regression test of this fix at all.
// The only production line M2 actually changes is AppClock.now()'s own return statement, so that is what this
// file exercises.

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class AppClockPrecisionTest {

    /** Every test here installs a warp; never leak one into a test that runs after it. */
    @AfterEach
    fun resetWarp() {
        AppClock.setWarp(null)
    }

    /**
     * M2: the exact night this was reproduced on. `anchorVirtual` carries the same microsecond precision the
     * real projected onset did (`2026-09-22T01:59:56.443955Z`, docs/decisions.md's M2 record) - reachable in
     * production because a projected onset is `now.plus(fallAsleepEstimate)` (engine/PlanSteps.kt's
     * findReferenceOnset) and, before this fix, `now` was `AppClock.now()` with no truncation of its own.
     * [AppClock.now] must never hand that remainder back out: every alarm this app arms is round-tripped through
     * epoch MILLISECONDS (PhoneAlarmScheduler.kt's `at.toEpochMilli()`, read back by PhoneAlarmReceiver.kt's own
     * `Instant.ofEpochMilli(...)`), and a value that does not survive that round trip is exactly what let the
     * app conclude an alarm that HAD already rung never had, and arm a second one - see this file's own header
     * for why the fix belongs here rather than at any one of the comparisons that broke.
     */
    @Test fun `M2 AppClock now truncates a microsecond-precision virtual anchor to whole milliseconds`() {
        val anchorVirtual = Instant.parse("2026-09-22T01:59:56.443955Z")
        AppClock.setWarp(ClockWarp(speed = 1, anchorReal = Instant.now(), anchorVirtual = anchorVirtual))
        val result = AppClock.now()
        assertEquals(0, result.nano % 1_000_000, "AppClock.now() returned $result, which still carries a sub-millisecond remainder")
    }

    /**
     * M2: the same fact, stated as the round trip that actually broke rather than as a raw nanosecond check -
     * PhoneAlarmScheduler.kt:115's `at.toEpochMilli()` followed by PhoneAlarmReceiver.kt:144's
     * `Instant.ofEpochMilli(...)`, reproduced here verbatim. Before M2 this assertion fails exactly the way the
     * night log shows: the armed instant (`01:59:56.443955Z`-shaped) and the instant read back off the fired
     * alarm's own intent (`01:59:56.443Z`-shaped) are different values, so every exact-equality "did this already
     * fire" comparison downstream (WakeAlarm.kt's `morningAlarmAlreadyRang`/`asleepNapTarget`,
     * PhoneAlarmReceiver's own attribution gate, NightOrchestrator's `firedAlarmIsWakeAlarm`/`shouldArmPhoneAlarm`/
     * `shouldKeepPreviousPlan`) says the alarm never rang.
     */
    @Test fun `M2 AppClock now survives the phone alarm intent's millisecond round trip`() {
        AppClock.setWarp(ClockWarp(speed = 1, anchorReal = Instant.now(), anchorVirtual = Instant.parse("2026-09-22T01:59:56.443955Z")))
        val armedFor = AppClock.now()
        val firedFor = Instant.ofEpochMilli(armedFor.toEpochMilli())
        assertEquals(armedFor, firedFor, "the armed instant $armedFor did not survive the same millisecond round trip PhoneAlarmScheduler/PhoneAlarmReceiver perform - it came back as $firedFor")
    }

    /** M2: the truncation must hold at every offered simulation speed (SIMULATION_SPEEDS), not only at 1x - a warp anchored the same way and left running a while must still read out millisecond-clean. */
    @Test fun `M2 AppClock now stays millisecond-precision after time has advanced under a warp`() {
        val anchorVirtual = Instant.parse("2026-09-22T01:59:56.443955Z")
        AppClock.setWarp(ClockWarp(speed = 600, anchorReal = Instant.now().minus(Duration.ofSeconds(5)), anchorVirtual = anchorVirtual))
        val result = AppClock.now()
        assertTrue(result.isAfter(anchorVirtual), "the warp should have advanced virtual time by now")
        assertEquals(0, result.nano % 1_000_000, "AppClock.now() returned $result, which still carries a sub-millisecond remainder after the warp advanced")
    }
}
