package com.nikita.sleepcycle.night

// File purpose: DataStore persistence for the Debug screen - the three switches (DebugOptions.kt) and the
// simulated sleep event timeline (BandDataSimulator.kt). Separate store from AppSettings.kt: debug state is
// never part of a real night's settings and is only ever read after passing through resolveDebugOptions.

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

private const val DATASTORE_NAME = "debug_settings"

private val Context.debugSettingsStore by preferencesDataStore(name = DATASTORE_NAME)

private val KEY_SIMULATED_BAND_DATA = booleanPreferencesKey("simulated_band_data")
private val KEY_FAST_NIGHT = booleanPreferencesKey("fast_night")
private val KEY_BAND_COMMAND_MODE = stringPreferencesKey("band_command_mode")
private val KEY_SIMULATED_EVENTS = stringPreferencesKey("simulated_sleep_events")
private val KEY_LAST_CHANGED_AT = stringPreferencesKey("debug_options_last_changed_at")

/** Streams the persisted [DebugOptions], defaulting to all-off. Callers must pass this through [resolveDebugOptions] before acting on it. */
fun readDebugOptions(context: Context): Flow<DebugOptions> =
    context.debugSettingsStore.data.map { preferences ->
        DebugOptions(
            simulatedBandData = preferences[KEY_SIMULATED_BAND_DATA] ?: false,
            fastNight = preferences[KEY_FAST_NIGHT] ?: false,
            bandCommandMode = preferences[KEY_BAND_COMMAND_MODE]?.let(::parseBandCommandMode) ?: BandCommandMode.SEND_TO_BAND
        )
    }

/** A1: when the switches were last changed by [writeDebugOptions], for [shouldAutoResetDebugOptions]. Null before the first change. */
fun readDebugOptionsLastChangedAt(context: Context): Flow<Instant?> =
    context.debugSettingsStore.data.map { preferences -> preferences[KEY_LAST_CHANGED_AT]?.let(Instant::parse) }

/** Saves [options], overwriting whatever was there before, and records [changedAt] for the idle auto-reset (A1). */
suspend fun writeDebugOptions(context: Context, options: DebugOptions, changedAt: Instant = Instant.now()) {
    context.debugSettingsStore.edit { preferences ->
        preferences[KEY_SIMULATED_BAND_DATA] = options.simulatedBandData
        preferences[KEY_FAST_NIGHT] = options.fastNight
        preferences[KEY_BAND_COMMAND_MODE] = options.bandCommandMode.name
        preferences[KEY_LAST_CHANGED_AT] = changedAt.toString()
    }
}

/** Streams the persisted simulated sleep event timeline, oldest first. A corrupt stored value decodes as empty rather than failing the read. */
fun readSimulatedSleepEvents(context: Context): Flow<List<SimulatedSleepEvent>> =
    context.debugSettingsStore.data.map { preferences ->
        preferences[KEY_SIMULATED_EVENTS]?.let(::decodeSimulatedSleepEventsTolerant) ?: emptyList()
    }

/** Saves the simulated sleep event timeline, overwriting whatever was there before. */
suspend fun writeSimulatedSleepEvents(context: Context, events: List<SimulatedSleepEvent>) {
    context.debugSettingsStore.edit { preferences ->
        preferences[KEY_SIMULATED_EVENTS] = encodeSimulatedSleepEvents(events)
    }
}

private fun parseBandCommandMode(text: String): BandCommandMode =
    try {
        BandCommandMode.valueOf(text)
    } catch (error: IllegalArgumentException) {
        BandCommandMode.SEND_TO_BAND
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
                SimulatedSleepEvent(kind = SimulatedSleepEventKind.valueOf(item.getString("kind")), at = Instant.parse(item.getString("at")))
            } catch (error: Exception) {
                null
            }
        }
    } catch (error: Exception) {
        emptyList()
    }
