package com.nikita.sleepcycle.night

// File purpose: user-facing settings persisted in DataStore - device MAC, export file, deadline, sleep length,
// and the Settings screen's own choices (UserSettings.kt).

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
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
const val DEFAULT_DEADLINE_ENABLED = false

private val Context.appSettingsStore by preferencesDataStore(name = DATASTORE_NAME)

private val KEY_DEVICE_MAC = stringPreferencesKey("device_mac")
private val KEY_EXPORT_URI = stringPreferencesKey("export_uri")
private val KEY_LAST_DEADLINE = stringPreferencesKey("last_deadline")
private val KEY_DEADLINE_ENABLED = booleanPreferencesKey("deadline_enabled")
private val KEY_PICKED_CYCLES = intPreferencesKey("picked_cycles")
private val KEY_NO_ALARM = booleanPreferencesKey("no_alarm")
private val KEY_LAST_SETUP_CHECK_PASSED_AT = longPreferencesKey("last_setup_check_passed_at_epoch_ms")
private val KEY_NUDGE_MINUTES = intPreferencesKey("nudge_minutes")
private val KEY_NAP_MINUTES = intPreferencesKey("nap_minutes")
private val KEY_FADE_ENABLED = booleanPreferencesKey("fade_enabled")
/** The single starting and ending volume of builds before PER-135: read only, as both outputs' value until each is saved. */
private val KEY_LEGACY_FADE_START_PERCENT = intPreferencesKey("fade_start_percent")
private val KEY_LEGACY_FADE_END_PERCENT = intPreferencesKey("fade_end_percent")
private val KEY_FADE_PHONE_START_PERCENT = intPreferencesKey("fade_phone_speaker_start_percent")
private val KEY_FADE_PHONE_END_PERCENT = intPreferencesKey("fade_phone_speaker_end_percent")
private val KEY_FADE_BLUETOOTH_START_PERCENT = intPreferencesKey("fade_bluetooth_start_percent")
private val KEY_FADE_BLUETOOTH_END_PERCENT = intPreferencesKey("fade_bluetooth_end_percent")
private val KEY_FADE_DURATION_MINUTES = intPreferencesKey("fade_duration_minutes")
private val KEY_PAUSE_WHEN_ASLEEP = booleanPreferencesKey("pause_when_asleep")
private val KEY_RATING_ENABLED = booleanPreferencesKey("rating_enabled")
private val KEY_RATING_ASK_AGAIN_LATER = booleanPreferencesKey("rating_ask_again_later")
private val KEY_RATING_ASK_AT = stringPreferencesKey("rating_ask_at")

/** The user's saved setup and last-used night settings. */
data class AppSettings(
    val deviceMac: String?,
    val exportUri: Uri?,
    val lastDeadline: LocalTime?,
    val deadlineEnabled: Boolean,
    val pickedCycles: Int,
    /** PER-150: the "No alarm" chip is picked instead of a cycle chip; [pickedCycles] is kept for when a chip is picked again. */
    val noAlarm: Boolean = false,
    val lastSetupCheckPassedAt: Instant?,
    /** The Settings screen's own choices (UserSettings.kt), each at its default until changed there. */
    val afterAlarm: AfterAlarmSettings = AfterAlarmSettings(),
    val bedtimeAudio: BedtimeAudioSettings = BedtimeAudioSettings(),
    val sleepRating: SleepRatingSettings = SleepRatingSettings(),
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
            noAlarm = preferences[KEY_NO_ALARM] ?: false,
            lastSetupCheckPassedAt = preferences[KEY_LAST_SETUP_CHECK_PASSED_AT]?.let(Instant::ofEpochMilli),
            afterAlarm = readAfterAlarmSettings(preferences),
            bedtimeAudio = readBedtimeAudioSettings(preferences),
            sleepRating = readSleepRatingSettings(preferences),
        )
    }

/** A stored number outside its stepper's limits (an older build, a bad write) reads back inside them. */
private fun readAfterAlarmSettings(preferences: Preferences): AfterAlarmSettings {
    val defaults = AfterAlarmSettings()
    return AfterAlarmSettings(
        nudgeMinutes = clampSetting(SettingStepper.NUDGE_MINUTES, preferences[KEY_NUDGE_MINUTES] ?: defaults.nudgeMinutes),
        napMinutes = clampSetting(SettingStepper.NAP_MINUTES, preferences[KEY_NAP_MINUTES] ?: defaults.napMinutes),
    )
}

/**
 * Owner spec, 2026-10-22 (PER-135): each output's pair falls back to the single pair older builds stored, so both
 * outputs start from the owner's existing choice. A percent outside its picker's range reads back at the range's
 * end; snapping to the phone's own steps happens where the phone's step count is known ([snapFadeVolumePercent]).
 */
internal fun readBedtimeAudioSettings(preferences: Preferences): BedtimeAudioSettings {
    val defaults = BedtimeAudioSettings()
    val legacyStart = preferences[KEY_LEGACY_FADE_START_PERCENT] ?: defaults.phoneSpeakerVolumes.startPercent
    val legacyEnd = preferences[KEY_LEGACY_FADE_END_PERCENT] ?: defaults.phoneSpeakerVolumes.endPercent
    fun volumes(startKey: Preferences.Key<Int>, endKey: Preferences.Key<Int>) = FadeVolumes(
        startPercent = (preferences[startKey] ?: legacyStart).coerceIn(FadeVolumeKind.START.percentRange),
        endPercent = (preferences[endKey] ?: legacyEnd).coerceIn(FadeVolumeKind.END.percentRange),
    )
    return BedtimeAudioSettings(
        fadeEnabled = preferences[KEY_FADE_ENABLED] ?: defaults.fadeEnabled,
        phoneSpeakerVolumes = volumes(KEY_FADE_PHONE_START_PERCENT, KEY_FADE_PHONE_END_PERCENT),
        bluetoothVolumes = volumes(KEY_FADE_BLUETOOTH_START_PERCENT, KEY_FADE_BLUETOOTH_END_PERCENT),
        fadeDurationMinutes = clampSetting(SettingStepper.FADE_DURATION_MINUTES, preferences[KEY_FADE_DURATION_MINUTES] ?: defaults.fadeDurationMinutes),
        pauseWhenAsleep = preferences[KEY_PAUSE_WHEN_ASLEEP] ?: defaults.pauseWhenAsleep,
    )
}

/** An unparseable stored ask time reads back as the default rather than failing the whole settings read. */
private fun readSleepRatingSettings(preferences: Preferences): SleepRatingSettings {
    val defaults = SleepRatingSettings()
    val askAt = try {
        preferences[KEY_RATING_ASK_AT]?.let(LocalTime::parse)
    } catch (error: Exception) {
        null
    }
    return SleepRatingSettings(
        enabled = preferences[KEY_RATING_ENABLED] ?: defaults.enabled,
        askAgainLater = preferences[KEY_RATING_ASK_AGAIN_LATER] ?: defaults.askAgainLater,
        askAt = askAt ?: defaults.askAt,
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
        preferences[KEY_NO_ALARM] = settings.noAlarm
        settings.lastSetupCheckPassedAt?.let { preferences[KEY_LAST_SETUP_CHECK_PASSED_AT] = it.toEpochMilli() }
            ?: preferences.remove(KEY_LAST_SETUP_CHECK_PASSED_AT)
        preferences[KEY_NUDGE_MINUTES] = settings.afterAlarm.nudgeMinutes
        preferences[KEY_NAP_MINUTES] = settings.afterAlarm.napMinutes
        preferences[KEY_FADE_ENABLED] = settings.bedtimeAudio.fadeEnabled
        preferences[KEY_FADE_PHONE_START_PERCENT] = settings.bedtimeAudio.phoneSpeakerVolumes.startPercent
        preferences[KEY_FADE_PHONE_END_PERCENT] = settings.bedtimeAudio.phoneSpeakerVolumes.endPercent
        preferences[KEY_FADE_BLUETOOTH_START_PERCENT] = settings.bedtimeAudio.bluetoothVolumes.startPercent
        preferences[KEY_FADE_BLUETOOTH_END_PERCENT] = settings.bedtimeAudio.bluetoothVolumes.endPercent
        preferences[KEY_FADE_DURATION_MINUTES] = settings.bedtimeAudio.fadeDurationMinutes
        preferences[KEY_PAUSE_WHEN_ASLEEP] = settings.bedtimeAudio.pauseWhenAsleep
        preferences[KEY_RATING_ENABLED] = settings.sleepRating.enabled
        preferences[KEY_RATING_ASK_AGAIN_LATER] = settings.sleepRating.askAgainLater
        preferences[KEY_RATING_ASK_AT] = settings.sleepRating.askAt.toString()
    }
}

/** [settings] with [mac] as the device MAC, normalised to upper case and trimmed before use and compare (Gadgetbridge's own address matching is case-sensitive). A passing setup check is only trustworthy for the band it checked, so changing the MAC clears it. */
fun withDeviceMac(settings: AppSettings, mac: String): AppSettings {
    val normalized = mac.trim().uppercase()
    if (normalized == settings.deviceMac) return settings.copy(deviceMac = normalized)
    return settings.copy(deviceMac = normalized, lastSetupCheckPassedAt = null)
}

/** [settings] with [uri] as the export file. A passing setup check is only trustworthy for the file it read, so changing it clears the check. */
fun withExportUri(settings: AppSettings, uri: Uri): AppSettings {
    if (uri == settings.exportUri) return settings
    return settings.copy(exportUri = uri, lastSetupCheckPassedAt = null)
}
