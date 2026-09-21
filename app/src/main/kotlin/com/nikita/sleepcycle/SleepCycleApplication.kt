package com.nikita.sleepcycle

// File purpose: T3 - runs before anything else in the process. Application.onCreate is guaranteed to run
// before any Activity, Service, or BroadcastReceiver in this process, so whatever it does to [AppClock] here is
// in effect for every later `nowInstant()` call - including one from a receiver woken by an exact alarm while
// the app's own UI was never opened. A release build never touches this DataStore at process start at all:
// [AppClock.setWarp] itself would ignore the result anyway (the same debug-build gate resolveDebugOptions
// uses), so skipping the call avoids the read/write for nothing.
//
// V8 REVERSES T11: T11 asked for the warp to be persisted so it survives an app restart, and this file used to
// LOAD it here (`AppClock.setWarp(readClockWarp(...))`). That is actively harmful - `anchorReal` predates the
// outage, so virtual time jumps forward by `downtime * speed` the instant the warp is adopted: a 2-minute
// reboot at 600x is 20 virtual hours. BootReceiver then finds `wakeAt` hours in the virtual past
// (shouldArmPhoneAlarm refuses it) and restorePendingOutOfBedNudge drops the nudge because it is no longer in
// the future - the night comes back believing a day has passed, silently missing its own wake alarm, which is
// worse than the night simply ending. A simulated clock now lasts as long as the app process and no longer -
// the same stance T6 already takes for its own in-process tick job - by CLEARING any stored warp here instead
// of loading it: [writeClockWarp] both removes it from DataStore and sets [AppClock]'s own cache to null, so a
// stale warp from a killed process can never be read back into anything, live or freshly reloaded, from this
// point on. The warp keeps being persisted during the process's OWN life (T7's speed selector, T11's jump) -
// so the pieces that read it from DataStore mid-process (readDebugOptions, NightState's frozen snapshot) stay
// consistent with what AppClock actually runs on - it is just never ADOPTED again once the process that wrote
// it is gone.

import android.app.Application
import com.nikita.sleepcycle.night.writeClockWarp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * W19 (review finding): the clear below used to be a `runBlocking`, which put a first-touch DataStore open and
 * an `edit{}` fsync on the MAIN THREAD of every process start in a debug build - and the owner sleeps on a
 * debug build. Android cold-starts this process to deliver PhoneAlarmReceiver when the wake alarm fires at
 * 3 a.m., and Application.onCreate runs before it, so that blocking write was charged to the alarm receiver's
 * own dispatch budget on a cold, throttled filesystem: an ANR and late-alarm risk on the one path that must
 * never be late.
 *
 * Nothing needs it to be synchronous. [com.nikita.sleepcycle.night.AppClock] starts at `warp = null` in
 * memory, so every `nowInstant()` in this process is already real time from the instant the process exists;
 * this only removes the stale row from disk so nothing can read it back afterwards.
 */
private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class SleepCycleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) startupScope.launch { writeClockWarp(this@SleepCycleApplication, null) }
    }
}
