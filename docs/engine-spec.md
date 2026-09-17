# Sleep engine spec (v2, after the Opus and Sol reviews)

Pure Kotlin, no Android imports, Gradle module `:engine`, package `com.nikita.sleepcycle.engine`. Unit tested on the JVM. Everything here is a pure function: same input, same output, nothing mutated. Times are `java.time.Instant`; durations are `java.time.Duration`. Rules come from [decisions.md](decisions.md); rule numbers below refer to it.

The engine is called every 5 to 15 min all night, including after the alarm and after the deadline. No point in the night is a caller error.

## Constants (`EngineConfig`, all overridable for calibration)

| Name | Default | Meaning |
|---|---|---|
| `cycleLength` | 90 min | One sleep cycle (rule 1) |
| `napLength` | 20 min | Nap after falling back asleep (rule 7) |
| `fallAsleepEstimate` | 15 min | Assumed time to fall asleep from "now" when not asleep |
| `phoneBackupOffset` | 15 min | Phone backup after the band alarm, no-deadline case |
| `minAwakening` | 1 min | Awake marks shorter than this do not split a stretch |
| `minAlarmLead` | 2 min | A band alarm is never set closer to `now` than this (the band takes hour and minute only, and the write takes seconds) |
| `allowedCycleCounts` | 3, 4, 5, 6 | Picker: 4.5 h, 6 h, 7.5 h, 9 h (rule 2) |
| `nearAlarmSyncWindow` | 30 min | Inside this window before the band alarm, sync faster |
| `frequentSyncDelay` / `normalSyncDelay` | 5 min / 15 min | Sync cadence |
| `maxOverdueDuration` | 30 min | Overdue period cap, from the missed band alarm (rule 1 amendment) |

## Input

- `SleepSegment(start, end, kind)` with `kind` in `LIGHT`, `DEEP`, `AWAKE`. The list may be unsorted, may overlap, may have gaps with no data, may be empty, may contain marks dated after `now` (clock skew).
- `NightSettings(deadline: Instant?, pickedCycles: Int, phoneBackupEnabled: Boolean)`.
- `now: Instant`.
- `previousPlan: AlarmPlan?` (the plan from the previous sync, null on the first one).

## Step 1: one clean timeline (`normalizeSegments`, `buildSleepStretches`, `detectSleepState`)

Stretches and state MUST come from the same normalised timeline, so they can never disagree.

`normalizeSegments(segments, now, config)`:
- Drop segments whose `end` is not after `start`. Drop segments that start after `now`; clip ends to `now`.
- Drop AWAKE marks shorter than `minAwakening`.
- AWAKE wins over sleep where they overlap: cut the awake intervals out of every LIGHT and DEEP segment. A sleep segment that continues past an awake mark re-opens after it (`LIGHT 00:30-04:00` + `AWAKE 01:44-01:50` gives sleep 00:30-01:44 and 01:50-04:00).
- Result: sorted, non-overlapping intervals, each either sleep or awake.

`buildSleepStretches`: a `SleepStretch(onset, end, followsAwakening: Boolean)` is one continuous run of sleep. Sleep intervals that touch, overlap, or are separated only by a gap with no data belong to one stretch (night 1 had a 16 min data gap while asleep). Only an awake interval ends a stretch. `followsAwakening` is true when an awake interval sits between this stretch and the previous one.

`detectSleepState`: `NOT_YET_ASLEEP` when there is no sleep interval; otherwise by the last interval of the timeline: `AWAKE` if it is an awake interval, `ASLEEP` if it is sleep. On a tie in end time the awake interval is last (it already won the overlap).

## Step 2: the plan (`computeAlarmPlan`)

Output `AlarmPlan(mode, bandAlarm: Instant?, phoneAlarm: Instant?, cycles: Int, referenceOnset: Instant?, onsetIsProjected: Boolean, wakeBoundary: Instant?, reason: String)`.

Write it as named steps, one small function each: `findReferenceOnset`, `findWakeBoundary`, `chooseMode`, `computeBandAlarm`, `computePhoneAlarm`, `describePlan`. `chooseMode` and `describePlan` are `when` blocks with one branch per rule.

**Reference onset.** `ASLEEP`: onset of the latest stretch, not projected. Otherwise `now + fallAsleepEstimate`, projected.

**Wake boundary** (what "the planned alarm" in rule 7 means): the deadline when there is one. Without a deadline: the band alarm of the latest `FULL_CYCLES` plan, carried forward through `previousPlan.wakeBoundary` while in other modes; null on the very first plan.

**Whole cycles that fit.** With a deadline: `fit = floor((deadline - referenceOnset) / cycleLength)`, never below 0, `cycles = min(pickedCycles, fit)`. Without a deadline: `cycles = pickedCycles`. A cycle ending exactly at the deadline fits.

**Is this a return to sleep after an awakening?** `afterAwakening` is true when the state is `AWAKE` and at least one stretch exists, or the state is `ASLEEP` and the latest stretch has `followsAwakening`.

**Modes, first match wins:**

1. `FINISHED`: the deadline is at or before `now`; or the state is `AWAKE` and `previousPlan.bandAlarm` is at or before `now` (woke up at or after the alarm). `bandAlarm = null`. The night is over for the engine; the phone alarm is untouched (with a deadline it still equals the deadline).
2. `NAP` (rule 7): `afterAwakening`, and less than one cycle fits before the wake boundary: `referenceOnset + cycleLength > wakeBoundary`. With no wake boundary (no deadline, no earlier full plan) nap never applies.
   - While `AWAKE`: `bandAlarm = min(now + napLength, wakeBoundary)`. It slides forward on every sync.
   - Once `ASLEEP`: `bandAlarm = min(onset + napLength, wakeBoundary)`. Never later than that, whatever the previous plan said: sync delay can never lengthen the nap. If that time is closer than `minAlarmLead` or already past, the overdue rule below applies.
3. `DEADLINE_ONLY` (rule 5): there is a deadline, `cycles == 0`, and this is not after an awakening (first sleep of the night starts too close to the deadline, or not asleep yet). `bandAlarm = deadline`.
4. `FULL_CYCLES` (rules 3, 4, 6): `bandAlarm = referenceOnset + cycles * cycleLength`.

**Overdue rule** (any mode with a band alarm): if the computed band alarm is before `now + minAlarmLead`, the person should already be up. Mode becomes `OVERDUE`: keep `previousPlan.bandAlarm` if the previous plan was `OVERDUE` and its alarm is still after `now`; otherwise `bandAlarm = now + minAlarmLead` rounded UP to the next whole minute (an instant already exactly on a minute boundary is unchanged) - the band only takes hour:minute, and rounding down instead would drop up to 59 s off the lead, defeating `minAlarmLead`. With a deadline the overdue alarm is never later than the deadline (if it would be, mode is `FINISHED`). Effect: while the band still says asleep after the alarm time, it buzzes again shortly after each sync, like a snooze, until an awake mark ends the night. The overdue period is capped at `maxOverdueDuration`, counted from the band alarm that was missed (the last non-`OVERDUE` plan's band alarm, carried forward while `OVERDUE` continues as `AlarmPlan.overdueSince`): once `now` reaches that cap, mode becomes `FINISHED` (band alarm null, phone alarm kept as `FINISHED` already keeps it, `nextSyncDelay` null) even with no deadline and no awake mark.

**Phone alarm.** With a deadline: always exactly the deadline, in every mode. Without one: if `phoneBackupEnabled`, `bandAlarm + phoneBackupOffset` for `FULL_CYCLES` and `NAP`; in `OVERDUE` and `FINISHED` keep `previousPlan.phoneAlarm` (the backup must not slide away with the snooze); else null.

**Known and accepted:** while lying awake the projected onset moves with `now`, so the band alarm moves at each sync and drops by one cycle when a cycle stops fitting. That is rule 1 working as intended. The app only writes to the band when the alarm minute actually changes.

`reason` is one plain English sentence for the night log with the numbers used, e.g. `"Asleep since 00:30, 5 of 5 picked cycles fit before 08:30, band alarm 08:00"`. Format times in the zone passed to the function.

## Step 3: helpers for the UI

- `isSleepLengthAvailable(cycles, now, deadline, config)`: true when `now + fallAsleepEstimate + cycles * cycleLength <= deadline`, always true without a deadline. Drives the hatched picker options.
- `listWakeOptions(referenceOnset, settings, config)`: for k in 1..pickedCycles, `referenceOnset + k * cycleLength`, dropping those after the deadline. Each option carries `k` and its sleep duration. Drives the timeline.
- `summarizeNight(stretches, config)`: total sleep, and per stretch its onset, end, duration and `cycles` as a decimal rounded to one place (duration / cycleLength). Drives the morning report.
- `nextSyncDelay(plan, now, config): Duration?`: null when the mode is `FINISHED` (stop syncing). `frequentSyncDelay` in `NAP` and `OVERDUE`, or when the band alarm is within `nearAlarmSyncWindow`; else `normalSyncDelay`. Never zero, never negative.

## Validation

`pickedCycles` outside `allowedCycleCounts`, or a nonsensical `EngineConfig` (non-positive cycle length and so on), is a caller error: throw `IllegalArgumentException` with a clear message. Nothing else throws. A deadline in the past is `FINISHED`. Bad band data is cleaned, never an error.

## Tests that must exist

One test class per function, plus `WholeNightSequenceTest` that replays a night as a series of calls, feeding each plan back in as `previousPlan`. Assert `mode` and `bandAlarm` (not just `cycles`). Cover at least:

- Night 1 real data: sleep 00:30 to 08:14, awake marks 01:44 to 01:50, 02:29 to 02:33, 04:47 to 04:49, data gap 04:49 to 05:05, awake at 08:13. Fixture has sleep resuming at 05:05. Expect 4 stretches with onsets 00:30, 01:50, 02:33, 05:05, the last three with `followsAwakening`.
- A data gap between two sleep segments with no awake mark does not split the stretch.
- `LIGHT 00:30-04:00` + `AWAKE 01:44-01:50`: stretches 00:30-01:44 and 01:50-04:00, reference onset 01:50.
- `AWAKE 01:00-02:00`, `LIGHT 01:30-03:00`, `AWAKE 01:45-01:50`: the stretch 02:00-03:00 survives; stretches and state agree.
- `LIGHT 00:30-08:14` and `AWAKE 08:13-08:14` in both input orders: `AWAKE` both times.
- A mark dated after `now` is ignored.
- Rules 3, 4, 5, 6, 7 with and without a deadline, including the exact boundary (a cycle ending exactly at the deadline fits; one minute over does not).
- Picked 9 h at 00:40 with an 08:30 deadline: projected onset 00:55, 5 cycles, band alarm 08:25. At 01:30: projected onset 01:45, 4 cycles, band alarm 07:45.
- First sleep at 07:30 with an 08:30 deadline: `DEADLINE_ONLY`, band alarm 08:30 (not a nap).
- No deadline, plan 08:00, awake 07:00 to 07:05: `NAP`, never a new full count; alarm never later than onset + 20 min and never later than 08:00. No deadline, awake at 03:00: full count restarts (rule 6).
- Deadline 08:30, plan 08:00, awake at 06:30 then asleep 06:45: one cycle fits before 08:30, `FULL_CYCLES`, band alarm 08:15.
- Nap: slides while awake over consecutive syncs, capped by the boundary, fixed once asleep, late detection (sleep 00:50, now 01:15, previous 01:20) gives `OVERDUE` at now + 2 min, not 01:20.
- Slept through: onset 00:30, 5 cycles, now 08:05, still asleep: `OVERDUE`, alarm 08:07; at 08:06 the alarm stays 08:07; at 08:10 it becomes 08:12; phone backup stays 08:15. A fresh (not kept) overdue alarm computed at a mid-minute now, e.g. 08:05:30, rounds UP to 08:08, never down.
- Deadline 08:30, now 08:31: `FINISHED`, no throw, `nextSyncDelay` null. Deadline 30 s ahead: no alarm closer than `minAlarmLead`.
- Awake mark after the alarm time: `FINISHED`.
- Night crossing midnight (now 23:00, deadline 07:00 next day).
- No deadline: backup on and off.
- Empty, unsorted, overlapping and zero-length segments.
- `nextSyncDelay` in every mode, never zero.
