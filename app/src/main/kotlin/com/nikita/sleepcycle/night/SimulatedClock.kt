package com.nikita.sleepcycle.night

// File purpose: T1 - the pure math of the debug simulated clock. A [ClockWarp] anchors one real instant to
// one virtual instant and a speed multiplier; [virtualNow] and [realInstantFor] are the two directions of the
// affine transform between them. Deliberately pure and JVM-testable, with no Android or Context dependency -
// the process-wide holder that owns "the current warp" and reads it from DataStore lives in AppClock.kt (T2),
// one layer up. `warp == null` is the identity transform in both directions: the app is running at real time,
// exactly as it always has, and every caller that goes through AppClock rather than these functions directly
// never has to special-case that case itself.
//
// Every arithmetic step below saturates to Instant.MIN/MAX (or Long.MIN_VALUE/MAX_VALUE for an intermediate
// millis value) rather than throwing on overflow - a 600x warp anchored a long time ago, or one left running
// for a long time, must never crash the app; it should just clamp at the edge of what an Instant can represent.
//
// U2 adds [normalizedWarp]: the one place that decides whether a (speed, anchorReal, anchorVirtual) triple is
// actually a live warp or the identity in disguise, so every writer of a [ClockWarp] (DebugScreenController's
// setSpeed/applyClockJump) can route through it and never hand-roll the null-vs-ClockWarp choice differently.
// T12 adds [formatSimulatedTimeValue]: the live "HH:mm[, Nx]" reading shown on the debug banner/notification.

import java.time.Duration
import java.time.Instant
import java.time.DateTimeException
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Anchors virtual time to real time: at [anchorReal] (real wall-clock time), the app's virtual clock read
 * [anchorVirtual]. From that moment on, virtual time moves [speed] times as fast as real time. [speed] must be
 * one of [SIMULATION_SPEEDS].
 */
data class ClockWarp(val speed: Int, val anchorReal: Instant, val anchorVirtual: Instant)

/**
 * The simulated-clock speed multipliers the Debug screen offers; 1 is real time.
 *
 * W13 (owner request): 10x is gone - it was slow enough to be boring and fast enough to be useless, and each
 * remaining step reads as a sentence. 60x is a simulated minute per real second, so a real minute is a
 * simulated hour. 600x is ten simulated minutes per real second, so a whole eight-hour night runs in about 48
 * real seconds. 600x is also the practical ceiling: TickScheduling's own [IN_PROCESS_TICK_MIN_DELAY] floor is
 * 250 ms, and the engine's shortest sync gap is 5 simulated minutes, which at 600x is exactly 500 real ms - go
 * much faster and that floor starts binding, and the clock quietly stops keeping the speed it advertises.
 */
val SIMULATION_SPEEDS: List<Int> = listOf(1, 60, 600)

/**
 * The current virtual instant, given the real wall-clock instant [realNow] - identity when [warp] is null.
 * `anchorVirtual + (realNow - anchorReal) * speed`: how far virtual time has moved since the warp was set is
 * [speed] times how far real time has moved since then.
 */
fun virtualNow(warp: ClockWarp?, realNow: Instant): Instant {
    if (warp == null) return realNow
    val elapsedRealMillis = millisBetweenSaturating(warp.anchorReal, realNow)
    val elapsedVirtualMillis = saturatingMultiply(elapsedRealMillis, warp.speed.toLong())
    return plusMillisSaturating(warp.anchorVirtual, elapsedVirtualMillis)
}

/**
 * The real instant that AlarmManager (which only ever fires in real time) must be armed at for something
 * meant to happen at the virtual instant [virtualAt] - identity when [warp] is null. The inverse of
 * [virtualNow]: `anchorReal + (virtualAt - anchorVirtual) / speed`.
 */
fun realInstantFor(warp: ClockWarp?, virtualAt: Instant): Instant {
    if (warp == null) return virtualAt
    val elapsedVirtualMillis = millisBetweenSaturating(warp.anchorVirtual, virtualAt)
    val elapsedRealMillis = elapsedVirtualMillis / warp.speed
    return plusMillisSaturating(warp.anchorReal, elapsedRealMillis)
}

/**
 * U2: the definitive rule for when a [ClockWarp] exists at all - whenever [speed] != 1 OR [anchorVirtual] !=
 * [anchorReal]. The identity case (real speed, no accumulated drift between the two anchors) collapses to null
 * rather than an equivalent-but-redundant [ClockWarp] object, so `warp != null` stays the single source of
 * truth for "is the clock warped" (see [DebugOptions.isAnyEnabled]) - a caller that always re-anchors through
 * this function (T7's speed selector, T11's jump) can never leave a live-looking warp behind that is actually
 * a no-op, or vice versa drop a warp that is genuinely still live (e.g. speed reverted to 1 after virtual time
 * had already drifted ahead of real time while running fast - see WarpTransitionTest for that exact case).
 */
fun normalizedWarp(speed: Int, anchorReal: Instant, anchorVirtual: Instant): ClockWarp? =
    if (speed == 1 && anchorVirtual == anchorReal) null else ClockWarp(speed, anchorReal, anchorVirtual)

/**
 * [com.nikita.sleepcycle.ui.DebugScreenController.setSpeed]'s own computation, extracted so a JVM test can call
 * the SAME function the controller does rather than re-deriving equivalent arithmetic locally (V10's own
 * complaint about the test file this replaces). Re-anchors at the CURRENT virtual instant and [speed] in one
 * step, so changing speed never itself jumps the clock - only the rate it moves at from here on.
 */
fun computeSpeedChangeWarp(currentWarp: ClockWarp?, speed: Int, realNow: Instant): ClockWarp? =
    normalizedWarp(speed, anchorReal = realNow, anchorVirtual = virtualNow(currentWarp, realNow))

/**
 * [com.nikita.sleepcycle.ui.DebugScreenController.applyClockJump]'s own computation, extracted for the same
 * reason as [computeSpeedChangeWarp]. Keeps the current speed, re-anchoring virtual time at [newVirtual].
 */
fun computeJumpWarp(currentWarp: ClockWarp?, newVirtual: Instant, realNow: Instant): ClockWarp? =
    normalizedWarp(currentWarp?.speed ?: 1, anchorReal = realNow, anchorVirtual = newVirtual)

/** W1: how often the UI re-samples the clock on an unwarped night - the cadence the app has always used, one sample per half minute. */
const val UI_TICKER_INTERVAL_MS: Long = 30_000L

/** W1: the UI never re-samples faster than this, however fast the clock runs - twice a second already looks continuous and the rebuild is pure computation. */
private const val UI_TICKER_MIN_INTERVAL_MS: Long = 500L

/** W1: how much SIMULATED time the readout aims to advance per sample, which is what makes a warped clock look like it is moving rather than jumping. */
private const val UI_TICKER_SIMULATED_STEP_MS: Long = 60_000L

/**
 * W1: how often the UI should re-sample the clock at [speed]. At 1x this is [UI_TICKER_INTERVAL_MS], exactly
 * as before. Above 1x the app's own 30 s cadence is far too slow to watch: at 60x the readout stands still
 * for 30 real seconds and then jumps half a simulated hour, which reads as the control having done nothing
 * at all. Scaling the interval so each sample advances about one simulated minute keeps the readout moving,
 * floored at [UI_TICKER_MIN_INTERVAL_MS] so 600x does not spin.
 */
fun uiTickerIntervalMillis(speed: Int): Long =
    if (speed <= 1) UI_TICKER_INTERVAL_MS
    else (UI_TICKER_SIMULATED_STEP_MS / speed).coerceIn(UI_TICKER_MIN_INTERVAL_MS, UI_TICKER_INTERVAL_MS)

private val SIMULATED_TIME_VALUE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * T12: the live simulated-clock reading for the debug banner and the night notification, e.g. "03:15".
 *
 * W7: the speed multiplier used to be appended here ("03:15, 60x"). It is gone: the banner now only ever
 * appears on the night screen, where the speed chips sit directly below it and already show which speed is
 * selected, so the suffix restated a control the reader can see. [warp] is still taken rather than a bare
 * instant, because whether a reading exists at all is a property of the warp.
 *
 * Callers wrap this in a localized template (`debug_switch_simulated_time` in strings.xml); this function
 * stays plain JVM/no-Android so it is directly unit-testable.
 */
fun formatSimulatedTimeValue(virtualNow: Instant, zone: ZoneId): String =
    SIMULATED_TIME_VALUE_FORMAT.withZone(zone).format(virtualNow)

/** [Duration.between] followed by [Duration.toMillis], saturating instead of throwing when the millis value would overflow a [Long]. */
private fun millisBetweenSaturating(from: Instant, to: Instant): Long {
    val duration = Duration.between(from, to)
    val secondsMillis = try {
        Math.multiplyExact(duration.seconds, 1000L)
    } catch (error: ArithmeticException) {
        return if (duration.seconds > 0) Long.MAX_VALUE else Long.MIN_VALUE
    }
    return try {
        Math.addExact(secondsMillis, duration.nano / 1_000_000L)
    } catch (error: ArithmeticException) {
        if (secondsMillis > 0) Long.MAX_VALUE else Long.MIN_VALUE
    }
}

/** `a * b`, saturating to [Long.MIN_VALUE]/[Long.MAX_VALUE] instead of throwing on overflow. */
private fun saturatingMultiply(a: Long, b: Long): Long =
    try {
        Math.multiplyExact(a, b)
    } catch (error: ArithmeticException) {
        if ((a >= 0) == (b >= 0)) Long.MAX_VALUE else Long.MIN_VALUE
    }

/** [Instant.plusMillis], saturating to [Instant.MIN]/[Instant.MAX] instead of throwing when the result would fall outside what an [Instant] can represent. */
private fun plusMillisSaturating(instant: Instant, millisToAdd: Long): Instant =
    try {
        instant.plusMillis(millisToAdd)
    } catch (error: ArithmeticException) {
        if (millisToAdd > 0) Instant.MAX else Instant.MIN
    } catch (error: DateTimeException) {
        if (millisToAdd > 0) Instant.MAX else Instant.MIN
    }
