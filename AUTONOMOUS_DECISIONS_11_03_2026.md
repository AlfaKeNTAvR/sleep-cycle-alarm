# Autonomous decisions, 2026-11-03

Run: PER-130 (band data reliability). Part 1: a band that syncs but measures nothing is treated as stale and the app says so. Part 2: automatic reconnect through Gadgetbridge, with the manual fix as fallback. Both parts are on branch nikita/fix/band-data-reliability. The ticket was rewritten into one because the Linear workspace hit its free issue limit.

## Diagnosis (for the record)
- Since 2026-10-30 13:47 local, the band sent heart-rate rows with the value -1 and no sleep stages, while every sync succeeded.
- The app counted those rows as fresh, read "not asleep yet" all night, and slid the alarm to the deadline (Nov 2 and Nov 3).
- Fixed on the band side by toggling "Enable automatic heart rate measuring" in Gadgetbridge at 09:28 on Nov 3. Gadgetbridge sends that setting only when it is toggled, never on reconnect.

## Decisions made before implementation
- A notification for a band that measures nothing during a night is silent (no sound, no vibration) but visible on the lock screen. The owner asked for "check and notify"; waking him for it would cost more sleep than the lost tracking.
- A reconnect is tried only when the band seems disconnected or stuck (a sync fails, or brings no new samples at all). It is not tried when samples arrive but carry no heart rate, because a reconnect does not re-send the heart-rate setting.

## Decisions made during implementation
(subagents append below)
- Part 1: the decisions.md entry is P4, not P3: P3 already exists (the "Nap for 20 min" button) and code comments cite it.
- Part 1: a HEART_RATE above 0 is a real value; -1, 0 and NULL are not. Freshness keeps the 30 min threshold on the newest real value.
- Part 1: the two stale reasons are `StaleReason.NO_NEW_SAMPLES` and `StaleReason.NO_HEART_RATE_VALUES` (log values `no_new_samples`, `no_heart_rate_values`). "Not measuring" means: real values missing or older than 30 min, but heart-rate rows newer than 30 min still arrive. A failed sync stays a separate error, as before.
- Part 1: when the band does not measure, sleep rows that might still arrive are ignored too (the whole read is stale). On the Nov 2-3 nights none arrived, and Huawei sleep stages depend on heart rate.
- Part 1: the stale reason is persisted as `NightState.lastStaleReason` (null when fresh or the sync failed), so the Night screen wording survives a restart. Old state files decode it as null.
- Part 1: the Night screen's boolean `bandNotSyncingWarning` was replaced by `bandDataWarning` (NOT_SYNCING / NOT_MEASURING_HEART_RATE), rather than adding a second flag beside it. Existing P1 tests were updated to the new field.
- Part 1: the notification is posted only on the transition into "not measuring" and cleared on the first fresh sync or at night end. A failed sync in between leaves it showing. A swiped-away notification is not re-posted until the band measures and stops again (no nagging; the banner stays). Own channel `band_not_measuring`, id 7, same silent-but-lock-screen setup as the Are you up? channel.
- Part 1: no anchoring change was needed. startNight already saves a plan made at Start night (onset Start night + 15 min), and the first stale tick keeps it via shouldKeepPreviousPlan. The bug was only that -1 rows read as fresh. To prove it with the tick's own logic, the plan decision was extracted into the pure `decideTickPlan` and the freshness verdict into `decideSyncOutcome`; runNightTick calls both, behavior unchanged.
- Part 1: the Nov 2 replay test runs those pure steps on synthetic raw rows (not a full Robolectric tick), since a real tick needs Gadgetbridge broadcasts and an export file. A control case with real heart-rate values shows normal nights still re-plan as before.
- Part 1: no new CONTEXT.md term: "stale" and "band not measuring" stay code and UI wording, not glossary terms.
- Part 2: the decisions.md entry is P5. Constants: 3 s pause between DISCONNECT and CONNECT, 30 s wait for BLUETOOTH_CONNECTED, 30 min night cooldown, 5 s for the Intent API check. One 30 s reply wait for both the night and the connection test, rather than a shorter one at bedtime: simpler, and Start night never waits on the test.
- Part 2: "a sync fails (any step)" includes the read steps (copy, quick_check, device_lookup), where a reconnect cannot help. Kept to the ticket's wording; the cooldown caps the cost at one needless reconnect per 30 min. Only the "settings" failure (no MAC or export file) skips it, since there is no band to address.
- Part 2: in the connection test, a reconnect replaces P2's 5 s pause (the reconnect already waits 3 s plus the reply) and the retry follows at once. "Not measuring" keeps the old pause-and-retry when automatic. A tapped test (Setup's Test connection too, not only Before bed's Test again) also reconnects once on failure. Worst case about 75 s, was 45 s. `runWithOneRetry` was replaced by `runConnectionTestAttempts` and its tests rewritten, rather than kept beside it.
- Part 2: the Intent API check runs only after a passing test (band known connected), sends CONNECT and waits 5 s. It is skipped when the test's own reconnect already got its reply. A failed test whose reconnect got no reply shows no hint: the band could just be out of range. The hint is a WARNING line in the report (Setup shows it) plus `SetupCheckReport.bluetoothIntentApiLooksOff` for a muted line on Before bed, under the band check.
- Part 2: BLUETOOTH_CONNECTED is accepted only when its EXTRA_DEVICE_ADDRESS matches the band (case-insensitive), or carries no address; awaitGadgetbridgeBroadcast gained an `accept` filter, default accept-all, so existing callers are unchanged.
- Part 2: the night reconnect lives in `syncWithReconnect` (NightBandReconnect.kt), called from readBandDataForTick in place of the plain sync. The tick's result after a reconnect is the second sync's, fresh or not. `SyncOutcome.reconnectAttemptedAt` carries the attempt time to the tick's state save (`NightState.lastReconnectAttemptAt`). The attempt time is saved with the rest of the tick, so a process death mid-reconnect can allow one extra reconnect on the next tick.
- Part 2: cooldown entries are logged as `band_reconnect_skipped` (about 2 lines per 30 min while the band stays down), so the log shows why no reconnect happened. A last attempt in the future (clock set back) does not block reconnects.
- Part 2: the pre-nudge check's own re-sync (OutOfBedPreNudgeCheck.kt) does not reconnect; only the tick does.
- Part 2 (follow-up 2): `night_end` gains `noBandDataCause` with three values, not two: `sync_failed` (P1 wording kept), `no_new_samples` ("No band data reached the app: the band sent no new data") and `no_heart_rate_values` ("No heart-rate values reached the app: the band was not measuring"). Written only when noBandData is true. The in-memory models keep the `noBandData` boolean and add `noBandDataCause` beside it, instead of replacing the boolean with a nullable cause, to keep the change and its test churn small.
- Part 2 (follow-up 1): grep found no other place the MAC reaches a log or message besides the device lookup failure. The Setup screen's band address field still shows it, since that is where the owner types it.
- Part 2: no new CONTEXT.md term: "reconnect" is plain wording.

## Open questions for the owner
(subagents append below)
- Part 1: the morning report's no-data line still says "No band data reached the app: the band's syncs were failing", which is untrue for a band that synced but did not measure. Reword it per cause (needs a night_end field for the reason), or leave it?
- Part 1: on Before bed, the corner status still says "Band not responding" when the test fails for "not measuring heart rate" (the amber line below it has the right message and fix). Add a "Band not measuring" corner status?
- Part 1 (security, pre-existing, not changed): BandDataReader.kt's device lookup failure message is "no DEVICE row for MAC <the MAC>", and that cause is written to the night log and setup.jsonl. Drop the MAC from that message?
- Part 1: should the notification come back after it is swiped away while the band still does not measure (like the Are you up? ask), or stay dismissed as now?
- Part 2: the three Part 1 questions above (no-data wording, "Band not measuring" corner, MAC in the lookup failure) were answered by the owner and fixed in Part 2.
- Part 2: the BLUETOOTH_CONNECTED reply has not been seen on the phone yet. Worth one check: open the app with the toggle on and look at setup.jsonl for `intent_api_check` with `connectedReply=true`. If it is false while the toggle is on, the hint would show wrongly every test.
- Part 2: after an explicit DISCONNECT while the band is out of range, does Gadgetbridge 0.95.1 keep trying to reconnect when it comes back, or does it wait for the next CONNECT (up to 30 min later)? If the latter, a reconnect could lengthen an outage. Worth watching in the first night logs with `band_reconnect_result`.
- Part 2: a night tick can now hold the night lock for about 2 min in the worst case (failed sync, reconnect, failed re-sync). Buttons that wait for the lock (I'm up, End night) could feel slow during that window. Acceptable, or should the reconnect run outside the lock?
- Part 2: reconnect on any failed step, including read-side failures (export file unreadable, device lookup), where it cannot help? Kept per the ticket; easy to narrow to activity_sync / database_export.
