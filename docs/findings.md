# Findings

## Night 1 (2026-09-16 to 17), Gadgetbridge database export

Setup: TruSleep off, automatic heart rate on, realtime heart rate mode on.

- Heart rate recorded every minute all night (table `HUAWEI_ACTIVITY_SAMPLE`, `SOURCE = 11`).
- Band sleep segments (`SOURCE = 13`, stored as start/end pairs): `RAW_KIND` 6 = light, 7 = deep, 8 = awake.
- Band detected sleep 00:30 to 08:14.
- Steps stopped about 00:05; heart rate dropped from ~78 to ~66 bpm at ~00:28. Heart rate and the band agree on onset within about 2 min.
- Awake marks at 01:44 to 01:50, 02:29 to 02:33, 04:47 to 04:49 (then no data until 05:05), 08:13. User confirmed real wake-ups at 02:29 and 04:47, so the band's awake marks are accurate. 01:44 was not remembered.
- No steps during either awakening: movement alone cannot detect waking in bed.
- Step and heart rate data sync right up to the current minute.

Unknown: whether the band releases sleep and awake marks mid-night or only after the night ends. Test: sync once mid-night, check the Sleep tab.

## Gadgetbridge Intent API, proven 2026-09-17 via adb

Required Gadgetbridge settings:

- Settings > Developer options > Intent API: "Allow activity sync trigger", "Broadcast on activity sync finish".
- Band gear > Developer options: "Allow 3rd party apps to set alarms".

Commands (broadcasts to package `nodomain.freeyourgadget.gadgetbridge`):

- Sync: `nodomain.freeyourgadget.gadgetbridge.command.ACTIVITY_SYNC`. Took about 19 s. Gadgetbridge then broadcasts `nodomain.freeyourgadget.gadgetbridge.action.ACTIVITY_SYNC_FINISH`.
- Set alarm: `nodomain.freeyourgadget.gadgetbridge.command.SET_ALARM`, extras `device` (MAC, required), `hour`, `minutes`, `title`, optional `days`. Uses the first free slot (disabled and untitled). Confirmed on the band.
- Remove alarm: `nodomain.freeyourgadget.gadgetbridge.command.DISMISS_ALARM`, extras `device`, `mode` (`all` | `time` | `title`), plus `title` or `hour`/`minutes`. Disables the slot and clears its title; the slot stays visible on the band.
- Logged "payload longer than current MTU" warnings while writing alarms; harmless in practice.

## Not yet tested

- Reading data from our app: database export via `command.TRIGGER_DATABASE_EXPORT` (setting "Allow database export"), and whether our app can read the exported file under Android storage rules. Health Connect is the fallback.
- Band battery cost of ~40 syncs a night.
- Whether lying still while reading gets marked as sleep.
