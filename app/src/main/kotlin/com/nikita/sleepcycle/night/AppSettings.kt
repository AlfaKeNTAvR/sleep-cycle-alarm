package com.nikita.sleepcycle.night

// File purpose: user-facing settings persisted in DataStore - device MAC, export file, deadline, sleep length, backup switch.

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalTime

private const val DATASTORE_NAME = "app_settings"
const val DEFAULT_PICKED_CYCLES = 5
const val DEFAULT_PHONE_BACKUP_ENABLED = false
const val DEFAULT_DEADLINE_ENABLED = false

private val Context.appSettingsStore by preferencesDataStore(name = DATASTORE_NAME)

private val KEY_DEVICE_MAC = stringPreferencesKey("device_mac")
private val KEY_EXPORT_URI = stringPreferencesKey("export_uri")
private val KEY_LAST_DEADLINE = stringPreferencesKey("last_deadline")
private val KEY_DEADLINE_ENABLED = booleanPreferencesKey("deadline_enabled")
private val KEY_PICKED_CYCLES = intPreferencesKey("picked_cycles")
private val KEY_PHONE_BACKUP_ENABLED = booleanPreferencesKey("phone_backup_enabled")
private val KEY_LAST_SETUP_CHECK_PASSED_AT = longPreferencesKey("last_setup_check_passed_at_epoch_ms")

/** The user's saved setup and last-used night settings. */
data class AppSettings(
    val deviceMac: String?,
    val exportUri: Uri?,
    val lastDeadline: LocalTime?,
    val deadlineEnabled: Boolean,
    val pickedCycles: Int,
    val phoneBackupEnabled: Boolean,
    val lastSetupCheckPassedAt: Instant?
)

/** Streams the saved app settings, with sensible defaults before anything has been picked. */
fun readAppSettings(context: Context): Flow<AppSettings> =
    context.appSettingsStore.data.map { preferences ->
        AppSettings(
            deviceMac = preferences[KEY_DEVICE_MAC],
            exportUri = preferences[KEY_EXPORT_URI]?.let(Uri::parse),
            lastDeadline = preferences[KEY_LAST_DEADLINE]?.let(LocalTime::parse),
            deadlineEnabled = preferences[KEY_DEADLINE_ENABLED] ?: DEFAULT_DEADLINE_ENABLED,
            pickedCycles = preferences[KEY_PICKED_CYCLES] ?: DEFAULT_PICKED_CYCLES,
            phoneBackupEnabled = preferences[KEY_PHONE_BACKUP_ENABLED] ?: DEFAULT_PHONE_BACKUP_ENABLED,
            lastSetupCheckPassedAt = preferences[KEY_LAST_SETUP_CHECK_PASSED_AT]?.let(Instant::ofEpochMilli)
        )
    }

/** Saves the given app settings, overwriting whatever was there before. */
suspend fun writeAppSettings(context: Context, settings: AppSettings) {
    context.appSettingsStore.edit { preferences ->
        settings.deviceMac?.let { preferences[KEY_DEVICE_MAC] = it } ?: preferences.remove(KEY_DEVICE_MAC)
        settings.exportUri?.let { preferences[KEY_EXPORT_URI] = it.toString() } ?: preferences.remove(KEY_EXPORT_URI)
        settings.lastDeadline?.let { preferences[KEY_LAST_DEADLINE] = it.toString() } ?: preferences.remove(KEY_LAST_DEADLINE)
        preferences[KEY_DEADLINE_ENABLED] = settings.deadlineEnabled
        preferences[KEY_PICKED_CYCLES] = settings.pickedCycles
        preferences[KEY_PHONE_BACKUP_ENABLED] = settings.phoneBackupEnabled
        settings.lastSetupCheckPassedAt?.let { preferences[KEY_LAST_SETUP_CHECK_PASSED_AT] = it.toEpochMilli() }
            ?: preferences.remove(KEY_LAST_SETUP_CHECK_PASSED_AT)
    }
}

/** [settings] with [mac] as the device MAC, normalised to upper case and trimmed before use and compare (Gadgetbridge's own address matching is case-sensitive). A passing setup check is only trustworthy for the band it checked, so changing the MAC clears it. */
fun withDeviceMac(settings: AppSettings, mac: String): AppSettings {
    val normalized = mac.trim().uppercase()
    return settings.copy(deviceMac = normalized, lastSetupCheckPassedAt = if (normalized != settings.deviceMac) null else settings.lastSetupCheckPassedAt)
}

/** [settings] with [uri] as the export file. A passing setup check is only trustworthy for the file it read, so changing it clears the check. */
fun withExportUri(settings: AppSettings, uri: Uri): AppSettings =
    settings.copy(exportUri = uri, lastSetupCheckPassedAt = if (uri != settings.exportUri) null else settings.lastSetupCheckPassedAt)
