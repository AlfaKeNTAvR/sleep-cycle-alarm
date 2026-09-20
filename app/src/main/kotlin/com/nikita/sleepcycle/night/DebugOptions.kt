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
//
// T7: `fastNight: Boolean` is gone, replaced by `speed: Int` - see SimulatedClock.kt/AppClock.kt. A fast debug
// night no longer comes from shrinking EngineConfig's own durations (EngineConfigResolution.kt always returns
// the real EngineConfig now); it comes from warping AppClock so the whole app's notion of "now" moves faster
// than the real wall clock.
//
// V7 REVERSES T7's own storage choice: `speed` used to be its own persisted field, a SECOND record alongside
// [ClockWarp] (in DebugSettingsStore.kt's readClockWarp/writeClockWarp), kept in sync by
// DebugScreenController.setSpeed writing both in two separate, un-atomic coroutines. Process death between the
// two writes could leave `speed = 60, warp = null` on disk: the 60x chip would show selected while the clock
// actually ran at 1x, and the next jump would silently re-apply the stale 60x. [speed] is now DERIVED from
// [warp] - the warp is the only place a speed lives, so the two can no longer disagree by construction.
//
// U3: [DebugOptions.warp] mirrors AppClock's own [ClockWarp] cache - always read from the same DataStore keys
// (DebugSettingsStore.kt's readDebugOptions/readClockWarp both read a [ClockWarp] out of the same preferences
// snapshot), never written independently. That is what keeps "is the clock warped" a single source of truth: a
// second boolean (the `timeJumped` TODO the part 1 agent left) would have been a second place that fact could
// drift out of sync with the warp AppClock actually runs on. Carrying [warp] on the data class itself, rather
// than reading the live global AppClock.warp() straight from [DebugOptions.isAnyEnabled], matters for one
// concrete reason: [NightState.debugOptions] is a FROZEN snapshot captured once at night start (see
// NightState.kt's own doc) - every tick's log line and the night notification read
// `state.debugOptions.isAnyEnabled` expecting the value that applied when the night began, not whatever the
// live clock happens to be warped to right now. V7: `night_start`'s own `speed` field keeps working from this
// same frozen [warp], since [speed] is now derived from it rather than a separate captured field.

import java.time.Duration
import java.time.Instant

/** The debug screen's two independent switches, plus the currently active clock warp (U3). Persisted as-is in DataStore; only meaningful after passing through [resolveDebugOptions]. */
data class DebugOptions(
    val simulatedBandData: Boolean = false,
    /** U3: the currently active [ClockWarp], or null when the clock is running at real time - see this file's own header for why this lives here rather than [isAnyEnabled] reading AppClock directly. */
    val warp: ClockWarp? = null,
) {
    /** V7: derived, never its own persisted field - see this file's own header for why. 1 means real time, one of [SIMULATION_SPEEDS] otherwise. */
    val speed: Int
        get() = warp?.speed ?: 1

    /**
     * True when any switch would change the app's behaviour from a normal night - used to gate the "this is a
     * simulated night" confirmation and the night log's "-sim-" file naming.
     *
     * U3 SUPERSEDES T7's own TODO: a jump alone (speed left at 1) now counts too, since [warp] is non-null
     * whenever a [ClockWarp] would exist at all (see [normalizedWarp]) - not just when [speed] happens to
     * differ from 1.
     */
    val isAnyEnabled: Boolean
        get() = simulatedBandData || warp != null
}

/**
 * U1: the speed selector requires simulated band data - a warped clock together with REAL band data is
 * nonsense (the band's samples carry real timestamps, so an engine reading virtual `now` against them would
 * see data that looks hours stale and conclude the owner never slept). Pure so the Debug screen's enablement
 * and [com.nikita.sleepcycle.ui.DebugScreenController.setSpeed]'s own second guard can never disagree.
 */
fun isSpeedSelectorAllowed(simulatedBandData: Boolean): Boolean = simulatedBandData

/**
 * U1 + T11: the jump-to-time action requires simulated band data (same reasoning as [isSpeedSelectorAllowed])
 * AND no active night - a jump mid-night would move time under alarms already armed at T5-converted real
 * instants, and under an engine that has already made decisions against the pre-jump timeline; not worth
 * supporting.
 */
fun isJumpToTimeAllowed(simulatedBandData: Boolean, nightActive: Boolean): Boolean = simulatedBandData && !nightActive

/**
 * V4: turning simulated band data OFF requires no active night, same disabled-with-reason pattern as
 * [isJumpToTimeAllowed]. T11's own reasoning ("moving time under already-armed alarms is not worth supporting")
 * applies to every warp mutation, not just the jump - turning this switch off calls the same
 * [com.nikita.sleepcycle.ui.DebugScreenController]'s clearClockWarp the jump's own "Reset to real time" does
 * (U1), so mid-night it would clear the warp out from under a running night's own FROZEN [NightState.debugOptions]
 * snapshot: `nowInstant()` snaps back to real wall time while the frozen snapshot still routes ticks to the
 * simulator, extending the open simulated segment from (say) virtual 03:00 to real 14:30 - an 11-hour sleep
 * that never happened. Turning the switch ON is never refused: it has no effect on an ALREADY-frozen night's
 * own snapshot either way, so there is nothing unsafe about it - but the whole toggle is disabled mid-night
 * anyway (see [com.nikita.sleepcycle.ui.state.DebugUiState]) for the same reason the jump's own control is:
 * a switch that would do nothing to the running night, and something actively harmful in the one direction
 * that matters, is simplest disabled outright rather than half-guarded.
 */
fun isSimulatedBandDataToggleAllowed(nightActive: Boolean): Boolean = !nightActive

/**
 * W2 (owner decision, 2026-09-20): "Reset to real time" does NOT require simulated band data, unlike the
 * speed selector and the jump. U1's gate exists to stop a warp and real band data being live together; this
 * action is how a warp STOPS being live, so gating it on the same switch only traps the owner with a clock
 * they cannot put back - exactly the state left behind by turning that switch off first. A gate belongs on
 * the actions that create state, not on the ones that clear it. The same reasoning applies to clearing the
 * simulated sleep timeline (see [isSimulatedSleepControlAllowed]'s own note).
 *
 * It does still require no active night, which V4 missed here while guarding every other path that clears
 * the warp: clearing it mid-night snaps `nowInstant()` back to real wall time while the night's own frozen
 * options keep routing ticks to the simulator, so the open simulated segment is stretched from the virtual
 * hour to the real one - hours of "sleep" that never happened.
 */
fun isResetToRealTimeAllowed(nightActive: Boolean): Boolean = !nightActive

/**
 * The single seam between "what is stored" and "what the app may act on". Returns [stored] unchanged in a
 * debug build, or every switch forced off in a release build - so the debug entry point being merely absent
 * from the release UI is never the only thing standing between a release build and a simulated night.
 */
fun resolveDebugOptions(isDebugBuild: Boolean, stored: DebugOptions): DebugOptions =
    if (isDebugBuild) stored else DebugOptions()

/** A1: one debug switch that is currently live, named in plain words for the warning banner. Order matches the Debug screen's own switch order. U3: SIMULATED_TIME is live whenever [DebugOptions.warp] != null, not [DebugOptions.speed] != 1 - a jump at speed 1 is simulated time too (see DebugOptions.isAnyEnabled's own doc). */
enum class ActiveDebugSwitch { SIMULATED_SLEEP_DATA, SIMULATED_TIME }

/**
 * A1: every switch in [options] that is currently live, so the warning banner can name them all rather than
 * being keyed to one alone - a simulated-data-only night must warn just as loudly as a simulated-time one.
 * Empty exactly when [DebugOptions.isAnyEnabled] is false.
 */
fun activeDebugSwitches(options: DebugOptions): List<ActiveDebugSwitch> = buildList {
    if (options.simulatedBandData) add(ActiveDebugSwitch.SIMULATED_SLEEP_DATA)
    if (options.warp != null) add(ActiveDebugSwitch.SIMULATED_TIME)
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
