package com.nikita.sleepcycle.night

// File purpose: T2 - the process-wide read cache of the current [ClockWarp]. DataStore (readClockWarp/
// writeClockWarp in DebugSettingsStore.kt) is the source of truth; this is just a fast, synchronous cache of
// it for every non-suspend call site (receivers, services, alarm scheduling) that needs "what time is it"
// without a coroutine. Loaded once at process start by SleepCycleApplication.onCreate (T3), and kept current
// after that by whatever changes the warp (the Debug screen's speed selector, T7).
//
// `setWarp` silently ignoring its argument outside a debug build is the same safety seam [resolveDebugOptions]
// uses for the rest of the debug/simulation machinery: a release build can never run on a warped clock, no
// matter what a debug build once left in DataStore on the same device.

import com.nikita.sleepcycle.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.temporal.ChronoUnit

/** The process-wide simulated-clock holder every `nowInstant()` call site reads through. */
object AppClock {
    @Volatile
    private var warp: ClockWarp? = null

    private val warpFlow = MutableStateFlow<ClockWarp?>(null)

    /**
     * W4: the live warp as a flow, for anything that must react the moment the clock changes rather than when
     * the change reaches disk. The UI's own ticker watches this instead of the DataStore mirror: the mirror
     * publishes the new speed while this holder is still on the old warp, so a screen keyed to it re-sampled a
     * clock that had not moved yet and then sat on that stale reading until its next tick - up to 30 s after
     * "Reset to real time" was tapped, which read as the button having done nothing.
     */
    val currentWarp: StateFlow<ClockWarp?> = warpFlow.asStateFlow()

    /**
     * The current virtual instant - the real wall clock, warped by whatever [ClockWarp] is currently loaded.
     *
     * M2 (owner-reported, 2026-09-21): truncated to whole MILLISECONDS before it is ever returned. Every alarm
     * this app arms is round-tripped through [AlarmManager]'s own epoch-milli granularity
     * (PhoneAlarmScheduler.kt's `at.toEpochMilli()`, read back by PhoneAlarmReceiver.kt's own
     * `Instant.ofEpochMilli(...)`), and `Instant.now()` on the JVM this app runs on routinely carries
     * microsecond precision - so an instant derived from [now] without this truncation (most directly, a
     * PROJECTED onset, `now + fallAsleepEstimate` in engine/PlanSteps.kt's `findReferenceOnset`, since ASLEEP's
     * own real onset already comes from whole-second band timestamps) never round-trips back to itself. The
     * exact-equality comparisons that key on "did the instant I just armed already fire" -
     * WakeAlarm.kt's `morningAlarmAlreadyRang`/`asleepNapTarget`, PhoneAlarmReceiver's own attribution gate,
     * NightOrchestrator's `firedAlarmIsWakeAlarm`/`shouldArmPhoneAlarm`/`shouldKeepPreviousPlan` - then never
     * match, so the app concludes the alarm never rang and arms a second one a few minutes later. Truncating
     * HERE, at the one seam nearly every instant in this app is built from, closes the hole at its source rather
     * than at each of those comparisons individually: [virtualNow]'s own arithmetic only ever ADDS whole
     * milliseconds to [ClockWarp.anchorVirtual] (see SimulatedClock.kt's `plusMillisSaturating`), so truncating
     * the RESULT here is exactly as correct as truncating every input would have been, including when
     * [anchorVirtual] itself was seeded from an untruncated `Instant.now()` (DebugScreenController.setSpeed's
     * first-ever call, when the previous warp was null) - any sub-millisecond remainder baked into the anchor
     * survives every later addition unchanged and is dropped here regardless of how much virtual time has
     * elapsed since. See docs/decisions.md's M2 record for the night this was reproduced on.
     */
    fun now(): Instant = virtualNow(warp, Instant.now()).truncatedTo(ChronoUnit.MILLIS)

    /** T5: the real instant AlarmManager must be armed at for something meant to happen at the virtual instant [virtualAt]. */
    fun toRealInstant(virtualAt: Instant): Instant = realInstantFor(warp, virtualAt)

    /** The currently loaded warp, or null when the clock is running at real time. */
    fun warp(): ClockWarp? = warp

    /** Sets the loaded warp - a no-op outside a debug build, whatever [warp] is, so a release build can never run on a warped clock. Publishes to [currentWarp] in the same call, so no observer can see the two disagree. */
    fun setWarp(warp: ClockWarp?) {
        if (!BuildConfig.DEBUG) return
        this.warp = warp
        warpFlow.value = warp
    }
}

/** Shorthand for [AppClock.now] - the one function call sites use in place of `Instant.now()` everywhere except the small, named real-time exceptions (T4). */
fun nowInstant(): Instant = AppClock.now()
