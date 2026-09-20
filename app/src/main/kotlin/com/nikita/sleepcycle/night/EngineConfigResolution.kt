package com.nikita.sleepcycle.night

// File purpose: the ONE function that supplies the EngineConfig for every engine call site in the app - the
// orchestrator and the UI support functions alike - so the screens and the service can never disagree about
// which timing is in effect.
//
// T7: this used to also shrink every duration for a "fast night" debug switch (a 5-minute cycle length and so
// on, each a named constant here). That switch is gone, replaced by a real simulated clock (SimulatedClock.kt/
// AppClock.kt/DebugOptions.speed) that warps the whole app's notion of "now" instead - shrinking EngineConfig
// AS WELL as warping the clock would multiply the two effects together (a 5-minute cycle at 600x would be half
// a second), so [resolveEngineConfig] now always returns the real [EngineConfig], regardless of debug options.
// [debugOptions] is still accepted, for source compatibility with every existing call site, but no longer
// changes the answer.

import com.nikita.sleepcycle.engine.EngineConfig

/**
 * The EngineConfig every engine call site in the app must use - the orchestrator's tick, the before-bed
 * picker, and the night screen alike. T7: always the real [EngineConfig] now; a fast debug night comes from
 * warping AppClock, never from shrinking these durations.
 */
fun resolveEngineConfig(debugOptions: DebugOptions): EngineConfig = EngineConfig()
