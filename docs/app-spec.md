# App spec (`:app`)

Android layer around the pure `:engine`. Package root `com.nikita.sleepcycle`. Read [decisions.md](decisions.md), [design.md](design.md), [findings.md](findings.md), [engine-spec.md](engine-spec.md) and `AUTONOMOUS_DECISIONS_09_17_2026.md` first.

Personal sideloaded app, one user, Pixel 10, Android 16, minSdk 31. No DI framework, no Room, no network. Allowed extra libraries: `kotlinx-coroutines-android`, `androidx.lifecycle:lifecycle-viewmodel-compose`, `androidx.lifecycle:lifecycle-runtime-compose`, `androidx.datastore:datastore-preferences`. JSON via the built-in `org.json`.

Style: procedural. Top-level functions and immutable data classes; a class only where Android forces one (Service, Activity, BroadcastReceiver, ViewModel). Those classes stay thin and call functions. No magic numbers: named constants. Errors are never swallowed: every failure is returned as a value or logged to the night log with its cause. Anything that touches time takes `now` / a `Clock` as a parameter.

## Packages and the contracts between them

### `bridge` : talking to Gadgetbridge (no engine logic)

Constants: package `nodomain.freeyourgadget.gadgetbridge`, actions `...command.ACTIVITY_SYNC`, `...command.SET_ALARM`, `...command.DISMISS_ALARM`, `...command.TRIGGER_DATABASE_EXPORT`, results `...action.ACTIVITY_SYNC_FINISH`, `...action.DATABASE_EXPORT_SUCCESS`, `...action.DATABASE_EXPORT_FAIL`. Every command is an explicit broadcast (`setPackage`). Manifest gets `<queries><package android:name="nodomain.freeyourgadget.gadgetbridge"/></queries>`.

```kotlin
fun isGadgetbridgeInstalled(context: Context): Boolean
fun sendActivitySync(context: Context)
fun sendDatabaseExport(context: Context)
fun sendSetBandAlarm(context: Context, deviceMac: String, time: LocalTime, title: String)   // extras: device, hour, minutes, title
fun sendDismissBandAlarm(context: Context, deviceMac: String, title: String)                 // extras: device, mode="title", title

/** Registers the receiver FIRST, then runs [send], then waits. Returns the action received, or null on timeout. */
suspend fun awaitGadgetbridgeBroadcast(context: Context, actions: Set<String>, timeout: Duration, send: () -> Unit): String?

sealed interface BandDataResult {
    data class Success(val segments: List<SleepSegment>, val newestSampleAt: Instant?) : BandDataResult
    data class Failure(val step: String, val cause: String) : BandDataResult
}
/** Copies the exported database (SAF uri) into cache, runs PRAGMA quick_check, reads sleep segments at or after [since]. */
fun readBandData(context: Context, exportUri: Uri, deviceMac: String, since: Instant): BandDataResult

/** sync -> wait finish (timeout 60 s) -> export -> wait success/fail (timeout 60 s) -> read. Each step's duration and outcome is logged. */
suspend fun syncAndReadBandData(context: Context, exportUri: Uri, deviceMac: String, since: Instant, log: (NightLogEvent) -> Unit): BandDataResult
```

Database facts (verified on a real export): table `HUAWEI_ACTIVITY_SAMPLE`, unix seconds. Sleep rows have `SOURCE = 13` and come in mirrored pairs: `(TIMESTAMP = start, OTHER_TIMESTAMP = end)` and the reverse. Keep only rows with `TIMESTAMP < OTHER_TIMESTAMP`. `RAW_KIND`: 6 light, 7 deep, 8 awake; ignore any other kind. Filter by `DEVICE_ID` = `DEVICE._id` where `DEVICE.IDENTIFIER` = the MAC. `newestSampleAt` = max `TIMESTAMP` of `SOURCE = 11` rows (per-minute heart rate), used to tell stale data from fresh. Open the cache copy with `SQLiteDatabase.OPEN_READONLY`, always close it, delete the copy afterwards.

Only one sync may run at a time (a `Mutex`).

### `night` : state, log, orchestration

```kotlin
data class NightState(              // persisted as JSON in filesDir/night_state.json, written atomically (temp file + rename)
    val startedAt: Instant, val settings: NightSettings,
    val lastPlan: AlarmPlan?, val activeBandAlarmTitle: String?,   // always BAND_ALARM_TITLE, one title all night
    val lastSyncAt: Instant?, val lastSyncOk: Boolean?, val lastSegments: List<SleepSegment>
)
fun loadNightState(context): NightState?      fun saveNightState(context, state)      fun clearNightState(context)

data class AppSettings(val deviceMac: String?, val exportUri: Uri?, val lastDeadline: LocalTime?, val deadlineEnabled: Boolean, val pickedCycles: Int, val phoneBackupEnabled: Boolean)   // DataStore

data class NightLogEvent(val at: Instant, val type: String, val fields: Map<String, String>)
fun appendNightLog(context, startedAt, event)    // one JSON object per line, filesDir/nightlogs/night-<yyyyMMdd-HHmm>.jsonl
fun listNightLogs(context): List<File>
```

Log event types: `night_start`, `tick` (scheduled time vs actual time), `sync` (ok, duration ms, step that failed, cause), `data` (segment count, newest sample time, sleep state), `plan` (mode, band alarm, phone alarm, cycles, the engine's reason), `band_alarm_command`, `band_alarm_table`, `band_alarm_tick` (blind or not, re-sends used), `band_alarm_held` (the plan moved but the band kept its own time), `band_alarm_slot_risk`, `band_alarm_dismissed`, `band_alarm_leftover` (what the band is still armed at once the night ends), `phone_alarm_set`, `phone_alarm_fired`, `error`, `night_end` (with the night summary).

`night_end` carries the whole summary as structured fields, so a past night can be redrawn from its log alone (`PastNightLog.kt`): `summary` (the human-readable `total=PT6H50M stretches=5` line, unchanged), `totalSleep` (ISO-8601 duration), `stretches` (a JSON array of `{onset, end, cycles}`, cycles to one decimal), `deadline` (ISO instant or `none`) and `pickedCycles`. `parsePastNightLog` reads a saved log back into a `PastNightLog`: `PastNightSummary.Detailed` when those fields are present, `PastNightSummary.TotalOnly` for a night logged before they existed (only `summary` was written, so only the total and the stretch count are known), and `PastNightSummary.Missing` when the log has no `night_end` at all. The deadline and picked length are read from `night_end` when it has them and from `night_start` otherwise, which is what lets an older night still show what it ran under. Unparseable lines are skipped, so a log truncated mid-write still reads back.

`runNightTick(context, now)`: the one function that does a whole cycle, in this order: load state -> `syncAndReadBandData` -> on failure keep the previous segments and log it -> `computeAlarmPlan` -> `resolveDesiredBandAlarm` (the band-write filter, below) -> if the band alarm minute changed: DISMISS our title then SET the new time under the SAME title, in that order, in one tick -> (re)arm the phone alarm if it changed -> save state -> schedule the next tick with `nextSyncDelay`. A failed sync never removes an existing alarm. If the band alarm time is already in the past, do not set it; log it; the phone alarm is the safety net.

**One title, one slot, all night** (decided 2026-09-18 after night 1, replacing decision 11's alternating titles). DISMISS_ALARM only edits Gadgetbridge's database; the band itself keeps whatever was LAST WRITTEN to a slot and nothing but a SET into that same slot replaces it. Two titles therefore meant two slots armed at two different times, and on night 1 both rang (07:04 from a five-hour-stale target, and the intended 08:17). Because a DISMISS cannot disarm, the dismiss-then-set order is safe: between the two commands the band still holds the OLD time, never nothing. A SET the next tick's table does not show is re-sent at once, bounded by `MAX_SINGLE_SLOT_RESENDS_PER_NIGHT` per night. Readiness needs exactly one usable slot; a second buys nothing and the setup check no longer asks for one. When a free slot sits ABOVE ours in the table, Gadgetbridge's picker would claim that one on the next move and abandon our slot still armed - reported every tick as `band_alarm_slot_risk`, never fixable from our side.

**The band is never retargeted while the sleeper is awake** (`BandAlarmRetargeting.kt`, decided 2026-09-18 after night 1). Whenever the band marks a short awakening the engine goes back to a PROJECTED onset (`now + fallAsleepEstimate`) that slides forward every tick, which moved the band alarm 14 times across a 77 min spread on night 1. So: while `sleepState` is not `ASLEEP`, an existing band alarm commitment is HELD, not moved; the band keeps the minute it already has. A brand-new commitment (nothing requested or confirmed yet) is still sent off a projected onset, or the first alarm of the night would never exist. `NAP` while awake is exempt - its target is `now + napLength`, deliberately kept ahead of the clock and locked once sleep is detected (rule 7), and freezing it would put the nap alarm in the past by the time the owner fell back asleep. A held minute whose next occurrence is further out than the longest night the engine can plan has wrapped to tomorrow and is released. This filter is in the app, not the engine: the same plan still drives the phone alarm and the whole Night screen, both of which should keep following the projected onset. Logged as `band_alarm_held`.

**Night end cannot disarm the band.** `endNight` sends the DISMISS for our title (which frees Gadgetbridge's row, so the next night's first SET lands in that same slot and overwrites the stale value) and then records what is still armed: `band_alarm_leftover` in the night log, and a line in the morning report naming the time. Nothing in Gadgetbridge's Intent API can switch a band alarm off - `AlarmsRequest.addEventAlarm` skips only alarms marked `unused`, a state reachable solely by long-pressing the alarm in Gadgetbridge's own list - so the app says so instead of pretending.

`NightService`: foreground service, type `specialUse` (manifest property `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` = "Overnight sleep tracking bridge that moves a wake-up alarm"), low-importance ongoing notification showing the planned wake time, started from the visible UI by "Start night". Ticks are woken by `AlarmManager.setExactAndAllowWhileIdle` to a `TickReceiver`, which asks the service to run `runNightTick` under a partial wake lock held at most 3 min. `BootReceiver` (BOOT_COMPLETED): if a night state exists and is still relevant, re-arm the phone alarm and restart the service.

`startNight(context, settings, now)` and `endNight(context, now)`: end dismisses our band alarm title, records the leftover the band is still armed at, cancels phone alarm and ticks, writes `night_end`, clears state, stops the service.

### `alarm` : the phone alarm

`schedulePhoneAlarm(context, at)` / `cancelPhoneAlarm(context)` via `AlarmManager.setAlarmClock` (permission `USE_EXACT_ALARM`). `PhoneAlarmReceiver` starts `AlarmRingService` (foreground, type `mediaPlayback`... use `specialUse` if mediaPlayback prerequisites are not met), which loops the default alarm sound with `AudioAttributes.USAGE_ALARM`, vibrates, and posts a high-importance `CATEGORY_ALARM` notification with a full-screen intent to `AlarmActivity` (`setShowWhenLocked`, `setTurnScreenOn`, one big "Stop" button, no snooze in v1). If the default alarm sound URI is unavailable fall back to the ringtone, then to vibration only, and log which one was used. Auto-stop after 10 min.

Permissions: `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`, `USE_EXACT_ALARM`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`, `VIBRATE`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. `android:allowBackup="false"` (health data).

### `ui` : Compose

One `NightViewModel` exposing a single immutable `UiState` as `StateFlow`. Screens per [design.md](design.md): Setup checklist, Before bed, Night (states A, B, C, D), plus a small Logs screen.

- **Setup checklist** (shown until everything is green, reachable later from a gear icon): Gadgetbridge installed; band MAC (text field, empty until the owner types it); export file picked (`ACTION_OPEN_DOCUMENT` + `takePersistableUriPermission`); notifications allowed; full-screen alarms allowed; battery optimisation exemption; a plain list of the Gadgetbridge settings to switch on (from findings.md plus: auto export with a location, Intent API "allow database export" and "broadcast on export"); a "Test connection" button that runs one `syncAndReadBandData` and shows the outcome in plain words.
- **Before bed**: deadline switch + time picker, "Sleep up to" 4.5 / 6 / 7.5 / 9 h where unavailable lengths (engine `isSleepLengthAvailable`) are disabled and covered with thin "/" hatch lines at 45 degrees about 1 mm apart, phone backup switch (only without a deadline), Start night. If the picked length becomes unavailable, fall back to the longest available one.
- **Night**: opening it triggers a tick. The morning report names the band alarm still armed after the night ends, since nothing can switch it off. States from the engine: A `NOT_YET_ASLEEP` with no stretch, B awake with `FULL_CYCLES`, C awake with `NAP`, D after "I'm up, end night" (morning report from `summarizeNight`: total, each stretch with from-to times, length, cycles to one decimal). While asleep the screen shows state A's layout with the real onset. Hours, never cycles, on A, B, C. Always show when the last sync happened and whether it worked.
- **Logs**: the night history. A list of saved night logs, newest first. Tapping a row reopens that night as a **Past night** screen; each row's three-dot button opens a menu with Share (the share sheet, `FileProvider`) and Delete (confirmed once, then the file is removed). The three-dot button was chosen over long-press and over swipe-to-delete because it is discoverable.
- **Past night**: one saved night, drawn from its log with the same `MorningReportBody` composable state D uses - total slept, each stretch with its from-to times, length and cycles to one decimal - plus that night's deadline and picked length. A night whose log kept only the total (`PastNightSummary.TotalOnly`) shows the real total and says outright that the stretch times were not recorded; one with no `night_end` at all says no summary was recorded. Nothing is ever fabricated or zeroed to fill a gap.

Look: dark, low glare, warm amber accent (`#E8A33D` range), large serif numerals for times (use the platform serif family; do not bundle fonts in v1), sans for text.

## Tests

JVM unit tests (JUnit 5, no emulator) for everything that is pure: night state JSON round trip, log line formatting and parsing, the `night_end` summary round trip and its old-format fallback, the Logs list-row derivation and the past-night derivation, the SQL row to `SleepSegment` mapping (extract the mapping into a pure function so it is testable), the band alarm decision and its move protocol, the awake-hold filter (`BandAlarmRetargeting.kt`), the leftover the band stays armed at, UI state derivation from `NightState` + engine output. Android-only glue is not unit tested in v1; it is checked on the phone.
