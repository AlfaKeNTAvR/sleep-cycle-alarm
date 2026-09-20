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
import kotlinx.coroutines.runBlocking

class SleepCycleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) runBlocking { writeClockWarp(this@SleepCycleApplication, null) }
    }
}
