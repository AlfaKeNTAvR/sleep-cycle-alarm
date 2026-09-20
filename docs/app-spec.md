# App spec (`:app`)

Android layer around the pure `:engine`. Package root `com.nikita.sleepcycle`. Read [decisions.md](decisions.md), [design.md](design.md), [findings.md](findings.md), [engine-spec.md](engine-spec.md) and `AUTONOMOUS_DECISIONS_09_17_2026.md` first.

Personal sideloaded app, one user, Pixel 10, Android 16, minSdk 31. No DI framework, no Room, no network. Allowed extra libraries: `kotlinx-coroutines-android`, `androidx.lifecycle:lifecycle-viewmodel-compose`, `androidx.lifecycle:lifecycle-runtime-compose`, `androidx.datastore:datastore-preferences`. JSON via the built-in `org.json`.

Style: procedural. Top-level functions and immutable data classes; a class only where Android forces one (Service, Activity, BroadcastReceiver, ViewModel). Those classes stay thin and call functions. No magic numbers: named constants. Errors are never swallowed: every failure is returned as a value or logged to the night log with its cause. Anything that touches time takes `now` / a `Clock` as a parameter.

## Packages and the contracts between them

### `bridge` : talking to Gadgetbridge (no engine logic)

Constants: package `nodomain.freeyourgadget.gadgetbridge`, actions `...command.ACTIVITY_SYNC`, `...command.TRIGGER_DATABASE_EXPORT`, results `...action.ACTIVITY_SYNC_FINISH`, `...action.DATABASE_EXPORT_SUCCESS`, `...action.DATABASE_EXPORT_FAIL`. Every command is an explicit broadcast (`setPackage`). Manifest gets `<queries><package android:name="nodomain.freeyourgadget.gadgetbridge"/></queries>`.

D2: the band is a sensor only. `SET_ALARM` and `DISMISS_ALARM` are gone along with every function that sent them (`sendSetBandAlarm`, `sendDismissBandAlarm`) - there is no longer any alarm command this app sends the band, so nothing here can arm or disarm one.

```kotlin
fun isGadgetbridgeInstalled(context: Context): Boolean
fun sendActivitySync(context: Context)
fun sendDatabaseExport(context: Context)

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
    val lastPlan: AlarmPlan?,
    val lastSyncAt: Instant?, val lastSyncOk: Boolean?, val lastSegments: List<SleepSegment>,
    val lastExportFileModifiedAt: Instant?, val lastSyncFailureCause: String?,
    val phoneAlarmFiredFor: Instant?,   // D1: the last wake or nap alarm instant that has fired (never the out-of-bed nudge's own firing) - armPhoneAlarmIfNeeded's re-arm guard only, never the engine's wakeAlarmFiredAt input
    val debugOptions: DebugOptions = DebugOptions(),
    val awakeConfirmedAt: Instant? = null,   // D3/D6: set when the owner taps "I'm awake"; null for every other ending
    val napAlarmsUsed: Int = 0,              // D5: naps that have actually FIRED since the wake alarm first fired, not naps armed, capped at MAX_NAP_ALARMS
    val wakeAlarmFiredAt: Instant? = null    // D5: the MAIN wake alarm's own fired instant, and only that alarm - a field of its own, distinct from phoneAlarmFiredFor above. This is the engine's wakeAlarmFiredAt input; a mid-night rule 7 nap firing never sets it
)
fun loadNightState(context): NightState?      fun saveNightState(context, state)      fun clearNightState(context)
```

`phoneAlarmFiredFor`, `wakeAlarmFiredAt` and `napAlarmsUsed` are written by `PhoneAlarmReceiver` the instant a real alarm fires, each into its own tiny file (`PhoneAlarmFiredStore.kt`) rather than through a read-modify-write of the whole `NightState` blob - the write used to race a concurrent tick's own save under the night lock, where whichever wrote last silently discarded the other's changes. `loadNightState` always merges the freshest values from these three files in on every load, so they are current regardless of what stale copy the blob itself carries; `runNightTick` only ever reads them back unchanged, it never writes them.

```kotlin
data class AppSettings(val deviceMac: String?, val exportUri: Uri?, val lastDeadline: LocalTime?, val deadlineEnabled: Boolean, val pickedCycles: Int, val lastSetupCheckPassedAt: Instant?)   // DataStore. D7: phoneBackupEnabled is gone - there is always exactly one alarm, the phone's.

data class NightLogEvent(val at: Instant, val type: String, val fields: Map<String, String>)
fun appendNightLog(context, startedAt, event)    // one JSON object per line, filesDir/nightlogs/night-<yyyyMMdd-HHmm>.jsonl
fun listNightLogs(context): List<File>
```

Log event types: `night_start`, `tick` (scheduled time vs actual time), `sync` (ok, duration ms, step that failed, cause), `data` (segment count, newest sample time, sleep state), `plan` (mode, wake alarm, cycles, the engine's reason), `stale_data`, `clock_changed`, `alarm_readiness` (full-screen intent / notification / channel state, logged once at night start), `phone_alarm_set`, `phone_alarm_fired`, `out_of_bed_alarm_fired` (D4), `alarm_ring_started`, `alarm_sound_chosen`, `alarm_stopped` (with the stop reason: the ring screen's own Stop button, the auto-stop timeout, or the night ending), `error`, `night_end` (with the night summary). D2: every `band_alarm_*` event type is gone along with the band alarm machinery that wrote it - the band is a sensor only, so nothing about its alarm table is logged any more.

`night_end` carries the whole summary as structured fields, so a past night can be redrawn from its log alone (`PastNightLog.kt`): `summary` (the human-readable `total=PT6H50M stretches=5` line, unchanged), `totalSleep` (ISO-8601 duration), `stretches` (a JSON array of `{onset, end, cycles}`, cycles to one decimal), `deadline` (ISO instant or `none`) and `pickedCycles`. `parsePastNightLog` reads a saved log back into a `PastNightLog`: `PastNightSummary.Detailed` when those fields are present, `PastNightSummary.TotalOnly` for a night logged before they existed (only `summary` was written, so only the total and the stretch count are known), and `PastNightSummary.Missing` when the log has no `night_end` at all. The deadline and picked length are read from `night_end` when it has them and from `night_start` otherwise, which is what lets an older night still show what it ran under. Unparseable lines are skipped, so a log truncated mid-write still reads back.

`runNightTick(context, now)`: the one function that does a whole cycle, in this order: load state (merging in the freshest `phoneAlarmFiredFor`, `wakeAlarmFiredAt` and `napAlarmsUsed`) -> `syncAndReadBandData` -> on failure keep the previous segments and log it -> `computeAlarmPlan` (fed `wakeAlarmFiredAt` and `napAlarmsUsed` from state) -> `armPhoneAlarmIfNeeded` re-arms the phone's `AlarmManager` alarm-clock alarm at `plan.wakeAt` if it changed -> save state -> schedule the next tick with `nextSyncDelay`. A failed sync never removes an existing alarm. `armPhoneAlarmIfNeeded` no longer touches `napAlarmsUsed` at all: that counter is `PhoneAlarmReceiver`'s own bookkeeping now, incremented only when a nap alarm actually FIRES (see the `alarm` section below), never at arm time. D1/D2: the band is never sent a command here or anywhere else - the wake alarm is the phone's alone, and there is no band-side write, dismiss, retarget or leftover to reason about any more.

**The band alarm machinery this section used to describe is gone** (D1, D2, decided 2026-09-20 after the band alarm still fired at 10:47 on the night of 2026-09-20 despite a DISMISS having been sent - see decisions.md's "Phone-only alarms"). There is no title, no slot, no dismiss-then-set protocol, no awake-hold retargeting filter, and no leftover band alarm to report once the night ends: the band is read for sleep data only, and every alarm command this app used to send it has been deleted along with the code that sent it.

`NightService`: foreground service, type `specialUse` (manifest property `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` = "Overnight sleep tracking bridge that moves a wake-up alarm"), low-importance ongoing notification showing the planned wake time, started from the visible UI by "Start night". Ticks are woken by `AlarmManager.setExactAndAllowWhileIdle` to a `TickReceiver`, which asks the service to run `runNightTick` under a partial wake lock held at most 3 min. `BootReceiver` (BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIME_CHANGED, TIMEZONE_CHANGED): whenever a night state exists at all, tracking resumes unconditionally - the service is restarted for a fresh tick regardless of the alarm's own state. The ONLY thing that stays conditional is the phone alarm's own (re)arming, gated by `shouldArmPhoneAlarm` (never a null, past, or already-fired `wakeAt`), the same guard `armPhoneAlarmIfNeeded` and `startNight` use. This matters for two real cases: the phone was off past the alarm time (the very first tick pulls a stale `wakeAt` forward to `now + minAlarmLead`, D8, exactly as intended), and a reboot moments after the owner tapped "Stop" with the night meant to continue (D6) - nap detection, the nudge, and ever reaching `FINISHED` must not die with it either.

`startNight(context, settings, now)`: computes the initial plan from empty segments, arms the phone alarm from it, saves state and logs readiness, all inside one lock acquisition, before the tracking service's first real tick runs.

`endNight(context, now, awakeConfirmedAt)` (D3/D6): stops the ringing alarm if one is ringing, cancels the phone alarm, the out-of-bed nudge (D4) and future ticks, records `awakeConfirmedAt` on the state when the owner tapped "I'm awake" (null for every other ending - the deadline, D5's nap cap, or "Stop night" from the Night screen), writes `night_end`, snapshots the morning report, clears state, stops the service. Idempotent: a call made while a previous one is still running joins that run's result instead of starting a second one (two real nights were double-tapped before the button itself was disabled mid-call).

### `alarm` : the phone alarm

D1: this is the only alarm mechanism in the app now - the band never receives an alarm command.

`schedulePhoneAlarm(context, at)` / `cancelPhoneAlarm(context)` via `AlarmManager.setAlarmClock` (permission `USE_EXACT_ALARM`), request code 2001. Two more alarms share the same scheduling code path but each has its own, entirely separate request code, so arming one can never replace another: `scheduleTestPhoneAlarm` / request code 2002 (the Debug screen's daylight test alarm - never loads, logs to, or writes night state, only rings), and `scheduleOutOfBedAlarm` / `cancelOutOfBedAlarm` / request code 2003 (D4, below). Every firing broadcast carries the instant it was scheduled for, so `PhoneAlarmReceiver` always marks the instant the intent actually asked for as fired, never whatever the latest plan happens to say by the time it runs.

`PhoneAlarmReceiver` starts `AlarmRingService` (foreground, type `mediaPlayback`, falls back to `specialUse` if mediaPlayback prerequisites are not met), which loops the default alarm sound with `AudioAttributes.USAGE_ALARM`, vibrates, and posts a high-importance `CATEGORY_ALARM` notification with a full-screen intent to `AlarmActivity` (`setShowWhenLocked`, `setTurnScreenOn`). If the default alarm sound URI is unavailable fall back to the ringtone, then to vibration only, and log which one was used. Auto-stop after `EngineConfig.ringAutoStopAfter` (9 min; 20 s on a fast night) if nobody acts - kept strictly less than `outOfBedDelay` (enforced by the engine's `validateConfig`) so the out-of-bed nudge is never swallowed by the still-ringing wake alarm's own auto-stop.

**D6: the ring screen has two actions**, both available whichever alarm (wake, nap, or the out-of-bed nudge) is ringing:

- **Stop** - silences the sound and vibration only. The night keeps running, so D5's nap mode still applies if the owner falls back asleep.
- **I'm awake** - silences the ringing AND ends the night through the normal `endNight` path, recording `NightState.awakeConfirmedAt`.

**D4: the out-of-bed nudge.** Ten minutes (`EngineConfig.outOfBedDelay`) after the wake alarm OR any D5 nap alarm rings, a second phone alarm rings - the "time to actually get up" nudge, armed by `PhoneAlarmReceiver` the moment the real alarm's receiver runs (never for the test alarm, never chained from the nudge's own firing). Its wording says it is the out-of-bed nudge rather than the wake alarm, but every stop/end path is identical to the wake alarm's own ring screen (D6 applies here too). Cancelled only by the night ending - confirming awake ends the night, so it is cancelled then too.

Permissions: `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`, `USE_EXACT_ALARM`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`, `VIBRATE`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. `android:allowBackup="false"` (health data).

### `ui` : Compose

One `NightViewModel` exposing a single immutable `UiState` as `StateFlow`. Screens per [design.md](design.md): Setup checklist, Before bed, Night (states A, B, C, the `FINISHED` amendment, and D), plus a small Logs screen.

- **Setup checklist** (shown until everything is green, reachable later from a gear icon): Gadgetbridge installed; band MAC (text field, empty until the owner types it); export file picked (`ACTION_OPEN_DOCUMENT` + `takePersistableUriPermission`); notifications allowed; full-screen alarms allowed; battery optimisation exemption; a plain list of the Gadgetbridge settings to switch on (from findings.md plus: auto export with a location, Intent API "allow database export" and "broadcast on export"); a "Test connection" button that runs one `syncAndReadBandData` and shows the outcome in plain words. D2: there is no band alarm slot setup here any more - the band needs nothing configured for alarms, only for the sync and export the checklist already covers.
- **Before bed**: deadline switch + time picker, "Sleep up to" 4.5 / 6 / 7.5 / 9 h where unavailable lengths (engine `isSleepLengthAvailable`) are disabled and covered with thin "/" hatch lines at 45 degrees about 1 mm apart, Start night. D7: the phone backup switch is gone - there is always exactly one alarm, the phone's, so there is nothing left to toggle. If the picked length becomes unavailable, fall back to the longest available one.
- **Night**: opening it triggers a tick. States from the engine: A `NOT_YET_ASLEEP` with no stretch, B awake with `FULL_CYCLES` or `DEADLINE_ONLY`, C awake with `NAP`. The `FINISHED` amendment (deadline passed, or D5's nap cap spent with no deadline left to fall back on) shows its own reason text and offers "End night" - D3: it is reached far more often now, since a band-detected wake no longer ends the night by itself; only the deadline, the nap cap (when no deadline remains), or the owner's own "I'm awake" tap reach it, and the last of those skips `FINISHED` entirely and lands straight on D. A spent nap cap with a deadline still ahead does not reach `FINISHED` at all - it lands on state B instead, the alarm held at the deadline (`DEADLINE_ONLY`, F4). State D is the morning report after ending the night (from `summarizeNight`: total, each stretch with from-to times, length, cycles to one decimal). While asleep the screen shows state A's layout with the real onset. Hours, never cycles, on A, B, C. Always show when the last sync happened and whether it worked.
- **Logs**: the night history. A list of saved night logs, newest first. Tapping a row reopens that night as a **Past night** screen; each row's three-dot button opens a menu with Share (the share sheet, `FileProvider`) and Delete (confirmed once, then the file is removed). The three-dot button was chosen over long-press and over swipe-to-delete because it is discoverable.
- **Past night**: one saved night, drawn from its log with the same `MorningReportBody` composable state D uses - total slept, each stretch with its from-to times, length and cycles to one decimal - plus that night's deadline and picked length. A night whose log kept only the total (`PastNightSummary.TotalOnly`) shows the real total and says outright that the stretch times were not recorded; one with no `night_end` at all says no summary was recorded. Nothing is ever fabricated or zeroed to fill a gap.

Look: dark, low glare, warm amber accent (`#E8A33D` range), large serif numerals for times (use the platform serif family; do not bundle fonts in v1), sans for text.

## Tests

JVM unit tests (JUnit 5, no emulator) for everything that is pure: night state JSON round trip (including a state file written by the old build, which must still decode with its deleted band-alarm fields simply ignored - D2), log line formatting and parsing, the `night_end` summary round trip and its old-format fallback, the Logs list-row derivation and the past-night derivation, the SQL row to `SleepSegment` mapping (extract the mapping into a pure function so it is testable), `applyAwakeConfirmation` (D3/D6), the post-wake nap count `PhoneAlarmReceiver` increments only when a nap alarm actually fires (never when armed) and how it decides "genuinely new" (D5), UI state derivation from `NightState` + engine output. Android-only glue is not unit tested in v1; it is checked on the phone.
