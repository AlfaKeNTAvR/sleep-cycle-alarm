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

Output `AlarmPlan(mode, bandAlarm: Instant?, phoneAlarm: Instant?, cycles: Int, referenceOnset: Instant?, onsetIsProjected: Boolean, reason: String, overdueSince: Instant?, sleptSoFar: Duration, owedCycles: Int)`.

Write it as named steps, one small function each: `findReferenceOnset`, `chooseMode`, `computeBandAlarm`, `computePhoneAlarm`, `describePlan`. `chooseMode` and `describePlan` are `when` blocks with one branch per rule.

**Reference onset.** `ASLEEP`: onset of the latest stretch, not projected. Otherwise `now + fallAsleepEstimate`, projected.

**What rule 7's "the planned alarm" means:** the deadline, and only the deadline (decided 2026-09-17 with the owner). There is no carried-forward boundary on a night with no deadline: an earlier plan's own band alarm must never shorten the night, so `AlarmPlan` no longer carries a `wakeBoundary` field at all.

**Cycles still owed** (rule 2 as a total for the night, decided 2026-09-17 with the owner). `pickedCycles` is the whole night's budget, not a fresh count per stretch. Sleep already had tonight is subtracted:

- `sleptSoFar` = total length of all sleep stretches, EXCLUDING the current one when the state is `ASLEEP` (the current stretch is the one the new alarm is being measured from, so counting it would subtract it twice).
- `remaining` = `pickedCycles * cycleLength - sleptSoFar`, never below 0.
- `owedCycles` = `remaining / cycleLength` rounded to the NEAREST whole number (`Math.round`), so a remainder that does not divide evenly lands closest to the picked total rather than cutting a cycle short. A remainder of exactly half a cycle rounds up.
- With a deadline: `fit = floor((deadline - referenceOnset) / cycleLength)`, never below 0, and `cycles = min(owedCycles, fit)`. Without a deadline: `cycles = owedCycles`. A cycle ending exactly at the deadline fits.

Worked example, the owner's: picked 7.5 h (5 cycles). Asleep 23:00, alarm 06:30. Wake 02:00 having slept 3 h (2 cycles), back asleep 02:10: remaining 4.5 h, 3 cycles, alarm 06:40, total 7.5 h. (Corrected 2026-09-17 during implementation: the owner's note said 05:40, which is 3.5 h after the 02:10 onset and would make the night 6.5 h, contradicting the same sentence's "remaining 4.5 h" and "total 7.5 h". 02:10 + 4.5 h = 06:40 is the value all three other numbers agree on.) Second example: slept 2 h, remaining 5.5 h, 3.67 cycles rounds to 4, alarm is 6 h after the new onset, total 8 h.

**A data gap inside a stretch counts as slept time.** `sleptSoFar` measures each stretch end to end, and a gap with no data at all never ends a stretch (only an awake mark does - see step 1), so night 1's 16 min gap from 04:49 to 05:05 was spent out of the night's budget exactly as if it had been sleep. Deliberate: the band simply stopped reporting, the sleeper did not get up, and subtracting every gap instead would keep extending the night on the strength of missing data. Worth knowing when reading a night log - the `plan` event's `sleptSoFarMinutes` can exceed the sleep the band actually recorded.

**The deadline cap FLOORS while the total ROUNDS**, so the two disagree on purpose. `owedCycles` rounds to the nearest whole cycle (it may overshoot the picked total by up to 45 min), but `fit` floors, discarding up to one cycle minus a minute - 89 min - of the time before the deadline, since a cycle that does not fit whole is not offered at all. When the deadline binds, the night therefore ends up to 89 min short of both the deadline and the picked total, and nothing offers a nap for that remainder: rule 7 only applies after an awakening, and a sleeper who never woke simply gets the shorter night. Example: asleep 23:00, picked 7.5 h, deadline 06:00 - 7 h available, 4 cycles fit, alarm 05:00, an hour before the deadline and 30 min short of the picked total.

**The two numbers are on the plan.** `AlarmPlan` carries `sleptSoFar` and `owedCycles` (the owed count BEFORE the deadline cap; `cycles` is after it), so the night log records them as fields instead of only inside `reason`: `cycles` alone cannot say whether the total or the deadline bound the plan.

**The total also feeds rule 7:** nap applies when less than one cycle is still owed (`owedCycles == 0`), on any night, as well as when less than one cycle fits before a deadline.

**Is this a return to sleep after an awakening?** `afterAwakening` is true when the state is `AWAKE` and at least one stretch exists, or the state is `ASLEEP` and the latest stretch has `followsAwakening`.

**Modes, first match wins:**

1. `FINISHED`: the deadline is at or before `now`; or the state is `AWAKE` and `previousPlan.bandAlarm` is at or before `now` (woke up at or after the alarm). `bandAlarm = null`. The night is over for the engine; the phone alarm is untouched (with a deadline it still equals the deadline).
2. `NAP` (rule 7): `afterAwakening`, and either less than one cycle is still owed (`owedCycles == 0`, the night's total is nearly reached) - which needs no deadline and applies on any night - or there IS a deadline and less than one cycle fits before it (`referenceOnset + cycleLength > deadline`). The fitting test is a deadline test and nothing else: with no deadline the picked total is the only cap, so a still-owed cycle is always slept in full, however close an earlier plan's alarm was.
   - While `AWAKE`: `bandAlarm = now + napLength`, capped by the deadline when there is one. It slides forward on every sync.
   - Once `ASLEEP`: `bandAlarm = onset + napLength`, capped by the deadline when there is one. Never later than that, whatever the previous plan said: sync delay can never lengthen the nap. If that time is closer than `minAlarmLead` or already past, the overdue rule below applies.
3. `DEADLINE_ONLY` (rule 5): there is a deadline, `cycles == 0`, and this is not after an awakening (first sleep of the night starts too close to the deadline, or not asleep yet). `bandAlarm = deadline`.
4. `FULL_CYCLES` (rules 3, 4, 6): `bandAlarm = referenceOnset + cycles * cycleLength`.

**Overdue rule** (any mode with a band alarm): if the computed band alarm is before `now + minAlarmLead`, the person should already be up. Mode becomes `OVERDUE`: keep `previousPlan.bandAlarm` if the previous plan was `OVERDUE` and its alarm is still after `now`; otherwise `bandAlarm = now + minAlarmLead` rounded UP to the next whole minute (an instant already exactly on a minute boundary is unchanged) - the band only takes hour:minute, and rounding down instead would drop up to 59 s off the lead, defeating `minAlarmLead`. With a deadline the overdue alarm is never later than the deadline (if it would be, mode is `FINISHED`). Effect: while the band still says asleep after the alarm time, it buzzes again shortly after each sync, like a snooze, until an awake mark ends the night. The overdue period is capped at `maxOverdueDuration`, counted from the band alarm that was missed (the last non-`OVERDUE` plan's band alarm, carried forward while `OVERDUE` continues as `AlarmPlan.overdueSince`): once `now` reaches that cap, mode becomes `FINISHED` (band alarm null, phone alarm kept as `FINISHED` already keeps it, `nextSyncDelay` null) even with no deadline and no awake mark.

**Phone alarm.** With a deadline: always exactly the deadline, in every mode. Without one: if `phoneBackupEnabled`, `bandAlarm + phoneBackupOffset` for `FULL_CYCLES` and `NAP`; in `OVERDUE` and `FINISHED` keep `previousPlan.phoneAlarm` (the backup must not slide away with the snooze); else null.

**Known and accepted:** while lying awake the projected onset moves with `now`, so the band alarm moves at each sync and drops by one cycle when a cycle stops fitting. That is rule 1 working as intended. The app only writes to the band when the alarm minute actually changes.

`reason` is one plain English sentence for the night log with the numbers used, e.g. `"Asleep since 00:30, 5 of 5 picked cycles fit before 08:30, band alarm 08:00"`. Format times in the zone passed to the function.

## Step 3: helpers for the UI

- `isSleepLengthAvailable(cycles, now, deadline, config)`: true when `now + fallAsleepEstimate + cycles * cycleLength <= deadline`, always true without a deadline. Drives the hatched picker options.
- `listWakeOptions(referenceOnset, settings, config, upToCycles = settings.pickedCycles)`: for k in 1..`upToCycles`, `referenceOnset + k * cycleLength`, dropping those after the deadline. Each option carries `k` and its sleep duration. Drives the timeline. `upToCycles` is a plain count, not a picker value, so 0 (nothing owed) is legal and lists nothing: the UI passes the current plan's `cycles`, so the timeline never offers a night the engine has already decided against - after waking at 05:30 of a 7.5 h night the screen lists the one cycle still owed, not a fresh 3 to 9 h.
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
- Deadline 08:00, plan 08:00, awake 07:00 to 07:05: `NAP`, never a new full count; alarm never later than onset + 20 min and never later than 08:00. The same night with no deadline is `FULL_CYCLES` for the cycle still owed, not a nap. No deadline, awake at 03:00: what is still owed is recounted (rule 6).
- Already-slept sleep measured HOURS into the new stretch, not one minute into it: 3 h slept, awake, then back asleep for two more hours - `sleptSoFar` is still 3 h and the alarm is still 3 cycles from the new onset. One minute in, subtracting the current stretch by mistake changes nothing visible.
- A brief awakening on a night with NO deadline: only what is left of the total is owed (6 h slept of 7.5 h leaves one cycle, alarm 08:20), where a per-stretch count would plan five more cycles. The same night WITH a deadline is not proof on its own - the cap alone lands on the same time.
- No deadline, 6.5 h slept of the picked 7.5 h, back asleep at 05:40 with the previous plan's alarm at 06:30: `FULL_CYCLES`, band alarm 07:10, night total 8 h. The same night with a 05:55 deadline: `NAP` at 05:55.
- Deadline 08:30, plan 08:00, awake at 06:30 then asleep 06:45: one cycle fits before 08:30, `FULL_CYCLES`, band alarm 08:15.
- Nap: slides while awake over consecutive syncs, capped by the boundary, fixed once asleep, late detection (sleep 00:50, now 01:15, previous 01:20) gives `OVERDUE` at now + 2 min, not 01:20.
- Slept through: onset 00:30, 5 cycles, now 08:05, still asleep: `OVERDUE`, alarm 08:07; at 08:06 the alarm stays 08:07; at 08:10 it becomes 08:12; phone backup stays 08:15. A fresh (not kept) overdue alarm computed at a mid-minute now, e.g. 08:05:30, rounds UP to 08:08, never down.
- Deadline 08:30, now 08:31: `FINISHED`, no throw, `nextSyncDelay` null. Deadline 30 s ahead: no alarm closer than `minAlarmLead`.
- Awake mark after the alarm time: `FINISHED`.
- Night crossing midnight (now 23:00, deadline 07:00 next day).
- No deadline: backup on and off.
- Empty, unsorted, overlapping and zero-length segments.
- `nextSyncDelay` in every mode, never zero.
