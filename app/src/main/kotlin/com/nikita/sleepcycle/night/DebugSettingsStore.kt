package com.nikita.sleepcycle.night

// File purpose: DataStore persistence for the Debug screen - the two switches (DebugOptions.kt), the
// simulated sleep event timeline (BandDataSimulator.kt), and T3's simulated clock warp (SimulatedClock.kt).
// Separate store from AppSettings.kt: debug state is never part of a real night's settings and is only ever
// read after passing through resolveDebugOptions (the switches) or AppClock.setWarp's own debug-build gate
// (the clock warp).
//
// V6: [updateDebugOptions] fixes the CLASS of bug, not one instance of it - every write to the persisted
// [DebugOptions] applies its transform INSIDE DataStore's own `edit {}` transaction, so it always sees the
// CURRENTLY persisted value, never a stale snapshot (a StateFlow's own `.value`) read before the call started.
// The old `writeDebugOptions(context, options, changedAt)` took a fully-computed [DebugOptions] built OUTSIDE
// the transaction from exactly such a stale snapshot - two calls launched close together
// (DebugScreenController.setSimulatedBandData(false) used to launch one for itself and one from
// clearClockWarp's own speed reset) could each transform the SAME stale value, and whichever committed second
// silently discarded the first's change. V7 already removes `speed` from what this store writes at all (it is
// derived from the warp - see DebugOptions.kt), which was the field that bug actually clobbered; this fix
// still generalizes the write shape itself, so a future field added here cannot reintroduce the same class of
// race.

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nikita.sleepcycle.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

private const val DATASTORE_NAME = "debug_settings"

private val Context.debugSettingsStore by preferencesDataStore(name = DATASTORE_NAME)

private val KEY_SIMULATED_BAND_DATA = booleanPreferencesKey("simulated_band_data")
// V7: "speed" (intPreferencesKey) is gone - it is derived from the warp now (see DebugOptions.kt) and never
// written or read as its own key. Deliberately not left as an unused val: nothing in this file references it
// any more, so a stray "speed" entry an old build left on a real device just sits there, inert, never decoded.
private val KEY_SIMULATED_EVENTS = stringPreferencesKey("simulated_sleep_events")
private val KEY_LAST_CHANGED_AT = stringPreferencesKey("debug_options_last_changed_at")
private val KEY_CLOCK_WARP_SPEED = intPreferencesKey("clock_warp_speed")
private val KEY_CLOCK_WARP_ANCHOR_REAL = stringPreferencesKey("clock_warp_anchor_real")
private val KEY_CLOCK_WARP_ANCHOR_VIRTUAL = stringPreferencesKey("clock_warp_anchor_virtual")

/**
 * Streams the persisted [DebugOptions], defaulting to all-off/speed 1/no warp. Callers must pass this through
 * [resolveDebugOptions] before acting on it. U3: [DebugOptions.warp] is read from the SAME preferences snapshot
 * as [writeClockWarp]/[readClockWarpFromPreferences] - never a second, independently-updated copy.
 *
 * V11: outside a debug build this never even touches the DataStore Flow, let alone decodes a [ClockWarp] out of
 * it - [com.nikita.sleepcycle.ui.DebugScreenController] collects this eagerly for the whole app's lifetime
 * (`storedOptions`), release build included, so without this gate a release build would keep decoding a warp
 * nothing ever acts on (resolveDebugOptions drops it, AppClock.setWarp is a no-op) for as long as the app runs.
 * Hygiene, not a defect on its own - but it is what makes [resolveDebugOptions]'s own "a release build can
 * never run on a warped clock" claim true of the READ path too, not just the act-on-it path.
 */
fun readDebugOptions(context: Context): Flow<DebugOptions> =
    if (!BuildConfig.DEBUG) flowOf(DebugOptions())
    else context.debugSettingsStore.data.map { preferences -> debugOptionsFromPreferences(preferences) }

/** V6/V7: the one place that decodes [DebugOptions] (minus its derived `speed`) out of a preferences snapshot - shared by [readDebugOptions] and [updateDebugOptions]'s own read-modify-write, so both always see the SAME shape. */
private fun debugOptionsFromPreferences(preferences: Preferences): DebugOptions = DebugOptions(
    simulatedBandData = preferences[KEY_SIMULATED_BAND_DATA] ?: false,
    warp = readClockWarpFromPreferences(preferences)
)

/** A1: when the switches were last changed by [updateDebugOptions], for [shouldAutoResetDebugOptions]. Null before the first change. */
fun readDebugOptionsLastChangedAt(context: Context): Flow<Instant?> =
    context.debugSettingsStore.data.map { preferences -> preferences[KEY_LAST_CHANGED_AT]?.let(Instant::parse) }

/**
 * V6: applies [transform] to the CURRENTLY persisted [DebugOptions], inside DataStore's own `edit {}`
 * transaction - see this file's own header for the class of bug this fixes. [transform] receives [DebugOptions]
 * (including its live `warp`) so a caller reasoning about the whole value can, but only `simulatedBandData` is
 * ever written back here: `warp` is [writeClockWarp]'s own separate keys (different keys, no conflict - a
 * caller that also wants to change the warp calls that too, same coroutine, per V5's own ordering rule), and
 * `speed` is derived and was never a real key to write (V7). Records [changedAt] for the idle auto-reset (A1)
 * on every call, same as the write it replaces did.
 */
suspend fun updateDebugOptions(context: Context, changedAt: Instant = Instant.now(), transform: (DebugOptions) -> DebugOptions) {
    context.debugSettingsStore.edit { preferences ->
        val updated = transform(debugOptionsFromPreferences(preferences))
        preferences[KEY_SIMULATED_BAND_DATA] = updated.simulatedBandData
        preferences[KEY_LAST_CHANGED_AT] = changedAt.toString()
    }
}

/**
 * U3/V8: the one place that decodes a [ClockWarp] out of a preferences snapshot - shared by
 * [debugOptionsFromPreferences] (so [DebugOptions.warp] is always the same fact, never a second copy that
 * could drift) and [writeClockWarp]'s own callers reading it back. V8 REVERSES T11's own `readClockWarp`, the
 * single read `SleepCycleApplication.onCreate` used to make at process start to ADOPT a stored warp - that read
 * is gone; a fresh process clears any stored warp instead (see SleepCycleApplication.kt's own doc) rather than
 * loading one.
 */
private fun readClockWarpFromPreferences(preferences: Preferences): ClockWarp? {
    val speed = preferences[KEY_CLOCK_WARP_SPEED] ?: return null
    val anchorReal = preferences[KEY_CLOCK_WARP_ANCHOR_REAL]?.let(Instant::parse) ?: return null
    val anchorVirtual = preferences[KEY_CLOCK_WARP_ANCHOR_VIRTUAL]?.let(Instant::parse) ?: return null
    return ClockWarp(speed, anchorReal, anchorVirtual)
}

/**
 * Persists [warp] (T7's speed selector; T11's jump-to-time), or clears it when null (back to real time), and
 * applies it to [AppClock] immediately - so a speed change or a jump takes effect right away rather than only
 * after the next process start, while still surviving one via [readClockWarp].
 */
suspend fun writeClockWarp(context: Context, warp: ClockWarp?) {
    // W4 (owner-diagnosed, 2026-09-20): the live clock MUST change before the persisted value, never after.
    // Persisting first publishes the new speed to everything watching the store - including the UI's own
    // ticker, which restarts and immediately re-samples the clock - while AppClock is still running on the
    // OLD warp. Tapping "Reset to real time" off 60x therefore showed the speed drop to 1x at once, sampled
    // the still-warped clock for the readout, and only corrected a full 30 s later on the next 1x tick, so
    // the reset looked like it had hung. Nothing reads this back off disk between the two statements, and V8
    // clears any stored warp at process start, so persisting second costs nothing even if the process dies
    // in between.
    AppClock.setWarp(warp)
    context.debugSettingsStore.edit { preferences ->
        if (warp == null) {
            preferences.remove(KEY_CLOCK_WARP_SPEED)
            preferences.remove(KEY_CLOCK_WARP_ANCHOR_REAL)
            preferences.remove(KEY_CLOCK_WARP_ANCHOR_VIRTUAL)
        } else {
            preferences[KEY_CLOCK_WARP_SPEED] = warp.speed
            preferences[KEY_CLOCK_WARP_ANCHOR_REAL] = warp.anchorReal.toString()
            preferences[KEY_CLOCK_WARP_ANCHOR_VIRTUAL] = warp.anchorVirtual.toString()
        }
    }
}

/**
 * A1/T7: resets every debug switch to off AND clears any active clock warp, in one call. Every "every debug
 * switch resets to off" call site (a night ending, either path - NightController.endNight/finishNightIfNeeded
 * - and the idle timeout, DebugScreenController.resetIfIdle) must go through this rather than
 * [updateDebugOptions] alone: without also clearing the warp, [AppClock]'s own loaded warp would keep running
 * fast while every switch reads off.
 */
suspend fun resetDebugOptionsAndClock(context: Context, changedAt: Instant) {
    updateDebugOptions(context, changedAt) { DebugOptions() }
    writeClockWarp(context, null)
}

/** Streams the persisted simulated sleep event timeline, oldest first. A corrupt stored value decodes as empty rather than failing the read. */
fun readSimulatedSleepEvents(context: Context): Flow<List<SimulatedSleepEvent>> =
    context.debugSettingsStore.data.map { preferences ->
        preferences[KEY_SIMULATED_EVENTS]?.let(::decodeSimulatedSleepEventsTolerant) ?: emptyList()
    }

/** Saves the simulated sleep event timeline, overwriting whatever was there before. FIX3: still used directly by [clearedSimulatedSleepEvents]'s own callers (clearing has no prior value that a stale snapshot could clobber) and, going forward, for any write that is a genuine unconditional replacement rather than an append - see [updateSimulatedSleepEvents] for the read-modify-write case. */
suspend fun writeSimulatedSleepEvents(context: Context, events: List<SimulatedSleepEvent>) {
    context.debugSettingsStore.edit { preferences ->
        preferences[KEY_SIMULATED_EVENTS] = encodeSimulatedSleepEvents(events)
    }
}

/**
 * FIX3 (owner-reported, 2026-09-21): applies [transform] to the CURRENTLY persisted simulated sleep event
 * timeline, inside DataStore's own `edit {}` transaction - the same V6 pattern [updateDebugOptions] already
 * uses, for the same class of bug. [ui.DebugScreenController.recordEvent] used to append to
 * `simulatedSleepEvents.value` - a StateFlow snapshot of this same timeline that can lag a just-committed
 * write - and write the result back unconditionally; [appendSimulatedSleepEvent] returns its input unchanged
 * when the transition is not allowed (see its own doc), so two quick taps of the Asleep toggle could compute
 * the second append against a snapshot still missing the first tap's own event, then write that stale snapshot
 * back and permanently erase it (the seed value is `emptyList()`, so a tap before the first DataStore emission
 * could wipe the whole timeline the same way). Routing the append through this function instead means
 * [transform] always sees what is actually on disk at write time, never a stale snapshot taken before the call
 * started.
 */
suspend fun updateSimulatedSleepEvents(context: Context, transform: (List<SimulatedSleepEvent>) -> List<SimulatedSleepEvent>) {
    context.debugSettingsStore.edit { preferences ->
        val stored = preferences[KEY_SIMULATED_EVENTS]?.let(::decodeSimulatedSleepEventsTolerant) ?: emptyList()
        preferences[KEY_SIMULATED_EVENTS] = encodeSimulatedSleepEvents(transform(stored))
    }
}

/** JSON encoding of a simulated event list, the inverse of [decodeSimulatedSleepEventsTolerant]. */
fun encodeSimulatedSleepEvents(events: List<SimulatedSleepEvent>): String {
    val array = JSONArray()
    events.forEach { event ->
        array.put(JSONObject().apply { put("kind", event.kind.name); put("at", event.at.toString()) })
    }
    return array.toString()
}

/** Parses a simulated event list from JSON text. A malformed entry is dropped rather than failing the whole read. */
fun decodeSimulatedSleepEventsTolerant(text: String): List<SimulatedSleepEvent> =
    try {
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { index ->
            try {
                val item = array.getJSONObject(index)
                SimulatedSleepEvent(kind = SimulatedSleepEventKind.valueOf(migrateLegacySimulatedSleepEventKindName(item.getString("kind"))), at = Instant.parse(item.getString("at")))
            } catch (error: Exception) {
                null
            }
        }
    } catch (error: Exception) {
        emptyList()
    }

/** T9: an event persisted by the old three-button design reads back under the new two-state [SimulatedSleepEventKind] - FELL_ASLEEP and FELL_BACK_ASLEEP both map onto ASLEEP, WOKE_UP onto AWAKE - so upgrading over an existing install never crashes on a name the current enum no longer has. A name that is already current (or unrecognized) passes through unchanged, letting the caller's own try/catch drop a genuinely malformed one. */
private fun migrateLegacySimulatedSleepEventKindName(name: String): String = when (name) {
    "FELL_ASLEEP", "FELL_BACK_ASLEEP" -> SimulatedSleepEventKind.ASLEEP.name
    "WOKE_UP" -> SimulatedSleepEventKind.AWAKE.name
    else -> name
}
