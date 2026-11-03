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

## Open questions for the owner
(subagents append below)
- Part 1: the morning report's no-data line still says "No band data reached the app: the band's syncs were failing", which is untrue for a band that synced but did not measure. Reword it per cause (needs a night_end field for the reason), or leave it?
- Part 1: on Before bed, the corner status still says "Band not responding" when the test fails for "not measuring heart rate" (the amber line below it has the right message and fix). Add a "Band not measuring" corner status?
- Part 1 (security, pre-existing, not changed): BandDataReader.kt's device lookup failure message is "no DEVICE row for MAC <the MAC>", and that cause is written to the night log and setup.jsonl. Drop the MAC from that message?
- Part 1: should the notification come back after it is swiped away while the band still does not measure (like the Are you up? ask), or stay dismissed as now?
