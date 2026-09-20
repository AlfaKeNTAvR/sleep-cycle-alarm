package com.nikita.sleepcycle.night

// File purpose: the debug/simulation mode's two independent switches, plus the ONE place that decides
// whether they can ever take effect. `resolveDebugOptions` is the sole gate between "what is persisted in
// DataStore" and "what the rest of the app is allowed to act on" - every other file reads debug options only
// through its return value, never through the raw stored value, so a release build can never run a simulated
// night no matter what got left in DataStore from a debug build. Also (A1) which switches are currently live,
// for the on-screen warning banner, and the idle-reset rule that turns every switch off again on its own.
//
// D2: the "band commands not sent" dry-run switch is gone along with the rest of the band alarm machinery -
// there is no longer any band alarm command to send or withhold.

import java.time.Duration
import java.time.Instant

/** The debug screen's two independent switches. Persisted as-is in DataStore; only meaningful after passing through [resolveDebugOptions]. */
data class DebugOptions(
    val simulatedBandData: Boolean = false,
    val fastNight: Boolean = false,
) {
    /** True when any switch would change the app's behaviour from a normal night - used to gate the "this is a simulated night" confirmation and the night log's "-sim-" file naming. */
    val isAnyEnabled: Boolean
        get() = simulatedBandData || fastNight
}

/**
 * The single seam between "what is stored" and "what the app may act on". Returns [stored] unchanged in a
 * debug build, or every switch forced off in a release build - so the debug entry point being merely absent
 * from the release UI is never the only thing standing between a release build and a simulated night.
 */
fun resolveDebugOptions(isDebugBuild: Boolean, stored: DebugOptions): DebugOptions =
    if (isDebugBuild) stored else DebugOptions()

/** A1: one debug switch that is currently live, named in plain words for the warning banner. Order matches the Debug screen's own switch order. */
enum class ActiveDebugSwitch { SIMULATED_SLEEP_DATA, FAST_NIGHT }

/**
 * A1: every switch in [options] that is currently live, so the warning banner can name them all rather than
 * being keyed to `fastNight` alone - a simulated-data-only night must warn just as loudly as a fast one.
 * Empty exactly when [DebugOptions.isAnyEnabled] is false.
 */
fun activeDebugSwitches(options: DebugOptions): List<ActiveDebugSwitch> = buildList {
    if (options.simulatedBandData) add(ActiveDebugSwitch.SIMULATED_SLEEP_DATA)
    if (options.fastNight) add(ActiveDebugSwitch.FAST_NIGHT)
}

/** A1: how long the debug switches may sit unchanged, with the app unopened, before they reset themselves to off. */
val DEBUG_OPTIONS_IDLE_RESET: Duration = Duration.ofHours(2)

/**
 * A1: true once [DEBUG_OPTIONS_IDLE_RESET] has passed since the switches were last changed at [lastChangedAt],
 * without the app having been reopened in between (the caller only gets a chance to check this on open/resume -
 * see DebugScreenController.resetIfIdle) - so a desk test left on in the afternoon cannot leak into bedtime.
 * [lastChangedAt] null (never changed, or already reset) never triggers a reset.
 */
fun shouldAutoResetDebugOptions(lastChangedAt: Instant?, now: Instant): Boolean =
    lastChangedAt != null && !now.isBefore(lastChangedAt.plus(DEBUG_OPTIONS_IDLE_RESET))
