package com.nikita.sleepcycle.night

// File purpose: the one UI-facing seam into the engine besides NightOrchestrator.kt. Wraps the engine's
// pure helper functions so the UI, the morning report and the night log never import :engine directly.

import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.engine.NightSummary
import com.nikita.sleepcycle.engine.SleepState
import com.nikita.sleepcycle.engine.WakeOption
import com.nikita.sleepcycle.engine.buildSleepStretches
import com.nikita.sleepcycle.engine.detectSleepState
import com.nikita.sleepcycle.engine.isSleepLengthAvailable
import com.nikita.sleepcycle.engine.listWakeOptions
import com.nikita.sleepcycle.engine.normalizeSegments
import com.nikita.sleepcycle.engine.summarizeNight
import java.time.Duration
import java.time.Instant

/** Everything the UI needs to render the Night and Logs screens, derived from the persisted state plus the engine's pure helpers. */
data class NightEngineView(
    val sleepState: SleepState,
    val summary: NightSummary,
    val wakeOptions: List<WakeOption>
)

/**
 * Builds the UI-facing view of a night: sleep state, per-stretch summary, and the wake-option timeline, all
 * from pure engine functions, using the EngineConfig [resolveEngineConfig] derives from [state]'s own debug
 * options - so a fast debug night is shown with fast-night timing from its very first tick onward, and the
 * night screen and the orchestrator (NightOrchestrator.kt) can never disagree about which config applies.
 *
 * The timeline is listed up to what the last plan still owes ([AlarmPlan.cycles]), never the whole picked
 * length: after an awakening the night's total is mostly spent, so offering a fresh full night there was
 * offering something the engine had already decided against (waking at 05:30 of a 7.5 h night listed options
 * out to 13 h while the plan was one cycle). Before the first plan exists there is nothing slept yet, so the
 * picked length is exactly what is owed.
 */
fun buildNightEngineView(state: NightState, now: Instant): NightEngineView {
    val config = resolveEngineConfig(state.debugOptions)
    val timeline = normalizeSegments(state.lastSegments, now, config)
    val stretches = buildSleepStretches(timeline)
    val sleepState = detectSleepState(timeline)
    val summary = summarizeNight(stretches, config)
    val plan = state.lastPlan
    val referenceOnset = plan?.referenceOnset ?: now
    val wakeOptions = listWakeOptions(referenceOnset, state.settings, config, upToCycles = plan?.cycles ?: state.settings.pickedCycles)
    return NightEngineView(sleepState, summary, wakeOptions)
}

/**
 * True when a given sleep length can still fit before the deadline, for the "sleep up to" picker's hatched
 * options. [debugOptions] is the currently-stored debug options (there is no NightState yet before a night
 * starts), resolved into an EngineConfig the same way as everywhere else via [resolveEngineConfig].
 */
fun sleepLengthIsAvailable(cycles: Int, now: Instant, deadline: Instant?, debugOptions: DebugOptions = DebugOptions()): Boolean =
    isSleepLengthAvailable(cycles, now, deadline, resolveEngineConfig(debugOptions))

/** The "Sleep up to" picker's cycle-count options, ascending. The same set (3/4/5/6) applies fast or real - only what each one means in wall-clock minutes changes (see [sleepLengthFor]). */
val sleepLengthCycleOptions: List<Int> = EngineConfig().allowedCycleCounts.sorted()

/** The wall-clock length of [cycles] sleep cycles, for picker labels like "7.5 h" (or, in a fast debug night, "9 min"). */
fun sleepLengthFor(cycles: Int, debugOptions: DebugOptions = DebugOptions()): Duration =
    resolveEngineConfig(debugOptions).cycleLength.multipliedBy(cycles.toLong())

/** The nap length (rule 7), for the nap card's "Nap: 20 min" label (or the fast debug night's shorter one). */
fun napLengthFor(debugOptions: DebugOptions = DebugOptions()): Duration = resolveEngineConfig(debugOptions).napLength
