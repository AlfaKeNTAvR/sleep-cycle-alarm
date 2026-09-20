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
import java.time.Instant

/** The process-wide simulated-clock holder every `nowInstant()` call site reads through. */
object AppClock {
    @Volatile
    private var warp: ClockWarp? = null

    /** The current virtual instant - the real wall clock, warped by whatever [ClockWarp] is currently loaded. */
    fun now(): Instant = virtualNow(warp, Instant.now())

    /** T5: the real instant AlarmManager must be armed at for something meant to happen at the virtual instant [virtualAt]. */
    fun toRealInstant(virtualAt: Instant): Instant = realInstantFor(warp, virtualAt)

    /** The currently loaded warp, or null when the clock is running at real time. */
    fun warp(): ClockWarp? = warp

    /** Sets the loaded warp - a no-op outside a debug build, whatever [warp] is, so a release build can never run on a warped clock. */
    fun setWarp(warp: ClockWarp?) {
        if (BuildConfig.DEBUG) this.warp = warp
    }
}

/** Shorthand for [AppClock.now] - the one function call sites use in place of `Instant.now()` everywhere except the small, named real-time exceptions (T4). */
fun nowInstant(): Instant = AppClock.now()
