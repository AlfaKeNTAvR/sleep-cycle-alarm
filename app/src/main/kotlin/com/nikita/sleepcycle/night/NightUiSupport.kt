package com.nikita.sleepcycle.night

// File purpose: the one UI-facing seam into the engine besides NightOrchestrator.kt. Wraps the engine's
// pure helper functions so the UI, the morning report and the night log never import :engine directly.

import com.nikita.sleepcycle.bridge.StaleReason
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
    val wakeOptions: List<WakeOption>,
    /**
     * P1 (owner-reported, 2026-09-30): true when this night has no sleep stretch at all AND its last sync did
     * not succeed (failed, or returned only stale data) - i.e. no band data reached the app, which is not the
     * same thing as the band reporting no sleep. The night of 2026-09-30 had every one of its 36 syncs time
     * out at `activity_sync` and its report said "No sleep recorded tonight". Derived from the existing
     * [NightState.lastSyncOk], not a new persisted field.
     */
    val noBandData: Boolean = false,
    /** P5: why no band data reached the app, for the report's wording; meaningful only when [noBandData] is true. */
    val noBandDataCause: NoBandDataCause = NoBandDataCause.SYNC_FAILED,
)

/**
 * P5: why a night ended with no band data, so the morning report names the right cause. [logValue] is night_end's
 * `noBandDataCause` field; a log without it (every night before P5) reads as [SYNC_FAILED], P1's wording.
 */
enum class NoBandDataCause(val logValue: String) {
    /** The last sync itself failed (P1: "the band's syncs were failing"). */
    SYNC_FAILED("sync_failed"),

    /** The last sync finished but nothing new arrived. */
    NO_NEW_SAMPLES("no_new_samples"),

    /** The last sync brought heart-rate rows, none with a real value (P4: the band was not measuring). */
    NOT_MEASURING("no_heart_rate_values"),
}

/** P5: the cause [NightState]'s last sync leaves for a no-data night. A failed sync wins; an unknown stale case reads as a failed sync, P1's wording. */
fun noBandDataCauseFor(state: NightState): NoBandDataCause = when {
    state.lastSyncFailureCause != null -> NoBandDataCause.SYNC_FAILED
    state.lastStaleReason == StaleReason.NO_HEART_RATE_VALUES -> NoBandDataCause.NOT_MEASURING
    state.lastStaleReason == StaleReason.NO_NEW_SAMPLES -> NoBandDataCause.NO_NEW_SAMPLES
    else -> NoBandDataCause.SYNC_FAILED
}

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
    val config = resolveEngineConfig(state)
    val timeline = normalizeSegments(state.lastSegments, now, config)
    val stretches = buildSleepStretches(timeline)
    val sleepState = detectSleepState(timeline)
    val summary = summarizeNight(stretches, config)
    val plan = state.lastPlan
    val referenceOnset = plan?.referenceOnset ?: now
    val wakeOptions = listWakeOptions(referenceOnset, state.settings, config, upToCycles = plan?.cycles ?: state.settings.pickedCycles)
    // P1: see NightEngineView.noBandData. lastSyncOk null (no sync has finished yet, e.g. a night ended seconds
    // after it started) keeps today's "no sleep recorded" reading - nothing has failed yet.
    val noBandData = summary.stretches.isEmpty() && state.lastSyncOk == false
    return NightEngineView(sleepState, summary, wakeOptions, noBandData, noBandDataCauseFor(state))
}

/**
 * True when a given sleep length can still fit before the deadline, for the "sleep up to" picker's hatched
 * options. [debugOptions] is the currently-stored debug options (there is no NightState yet before a night
 * starts), resolved into an EngineConfig the same way as everywhere else via [resolveEngineConfig].
 */
fun sleepLengthIsAvailable(cycles: Int, now: Instant, deadline: Instant?, debugOptions: DebugOptions = DebugOptions()): Boolean =
    isSleepLengthAvailable(cycles, now, deadline, resolveEngineConfig(debugOptions, AfterAlarmSettings()))

/** The "Sleep up to" picker's cycle-count options, ascending. The same set (3/4/5/6) applies fast or real - only what each one means in wall-clock minutes changes (see [sleepLengthFor]). */
val sleepLengthCycleOptions: List<Int> = EngineConfig().allowedCycleCounts.sorted()

/** The wall-clock length of [cycles] sleep cycles, for picker labels like "7.5 h" (or, in a fast debug night, "9 min"). */
fun sleepLengthFor(cycles: Int, debugOptions: DebugOptions = DebugOptions()): Duration =
    resolveEngineConfig(debugOptions, AfterAlarmSettings()).cycleLength.multipliedBy(cycles.toLong())

