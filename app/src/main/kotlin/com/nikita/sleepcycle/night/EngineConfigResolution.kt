package com.nikita.sleepcycle.night

// File purpose: the ONE function that supplies the EngineConfig for every engine call site in the app - the
// orchestrator and the UI support functions alike - so the screens and the service can never disagree about
// which timing is in effect.
//
// T7: this used to also shrink every duration for a "fast night" debug switch (a 5-minute cycle length and so
// on, each a named constant here). That switch is gone, replaced by a real simulated clock (SimulatedClock.kt/
// AppClock.kt/DebugOptions.speed) that warps the whole app's notion of "now" instead - shrinking EngineConfig
// AS WELL as warping the clock would multiply the two effects together (a 5-minute cycle at 600x would be half
// a second), so [resolveEngineConfig] never changes a duration for debug options. [debugOptions] is still
// accepted, for source compatibility with every existing call site, but no longer changes the answer.
//
// Owner spec, 2026-10-02: the owner's own "After the alarm" settings DO change it - the out-of-bed nudge and
// the nap length come from [AfterAlarmSettings], captured by each night at Start night.

import com.nikita.sleepcycle.engine.EngineConfig
import java.time.Duration

/**
 * The EngineConfig every engine call site in the app must use - the orchestrator's tick, the before-bed
 * picker, and the night screen alike: the real [EngineConfig], with the nudge and nap length taken from
 * [afterAlarm]. T7: a fast debug night comes from warping AppClock, never from shrinking these durations.
 */
fun resolveEngineConfig(debugOptions: DebugOptions, afterAlarm: AfterAlarmSettings): EngineConfig = EngineConfig(
    outOfBedDelay = Duration.ofMinutes(afterAlarm.nudgeMinutes.toLong()),
    napLength = Duration.ofMinutes(afterAlarm.napMinutes.toLong()),
)

/** [resolveEngineConfig] for a running night: the debug options and after-alarm settings it was started with. */
fun resolveEngineConfig(state: NightState): EngineConfig = resolveEngineConfig(state.debugOptions, state.afterAlarm)
