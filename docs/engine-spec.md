# Sleep engine spec (v3, phone-only alarms)

Pure Kotlin, no Android imports, Gradle module `:engine`, package `com.nikita.sleepcycle.engine`. Unit tested on the JVM. Everything here is a pure function: same input, same output, nothing mutated. Times are `java.time.Instant`; durations are `java.time.Duration`. Rules come from [decisions.md](decisions.md); rule numbers below refer to it.

The engine is called every 5 to 15 min all night, including after the alarm and after the deadline. No point in the night is a caller error.

## Constants (`EngineConfig`, all overridable for calibration)

| Name | Default | Meaning |
|---|---|---|
| `cycleLength` | 90 min | One sleep cycle (rule 1) |
| `napLength` | 20 min | Nap after falling back asleep (rule 7) |
| `fallAsleepEstimate` | 15 min | Assumed time to fall asleep from "now" when not asleep |
| `minAwakening` | 1 min | Awake marks shorter than this do not split a stretch |
| `minAlarmLead` | 2 min | The wake alarm is never armed closer to `now` than this (D8) |
| `allowedCycleCounts` | 3, 4, 5, 6 | Picker: 4.5 h, 6 h, 7.5 h, 9 h (rule 2) |
| `nearAlarmSyncWindow` | 30 min | Inside this window before the wake alarm, sync faster |
| `frequentSyncDelay` / `normalSyncDelay` | 5 min / 15 min | Sync cadence |
| `outOfBedDelay` | 10 min | D4: how long after any alarm rings before the out-of-bed nudge rings. Armed by the app layer when that alarm fires, not computed here - kept in `EngineConfig` so a fast debug night shortens it like every other timing constant |
| `ringAutoStopAfter` | 9 min (20 s on a fast night) | How long a ringing alarm rings before stopping itself if nobody acts. Not read by the engine itself - kept in `EngineConfig`, like `outOfBedDelay`, purely so a fast debug night can shorten it too, and so `validateConfig` can enforce it stays strictly less than `outOfBedDelay`: otherwise the out-of-bed nudge can fire just as the still-ringing wake alarm's own auto-stop tears the ringing service down, swallowing the nudge |

## Input

- `SleepSegment(start, end, kind)` with `kind` in `LIGHT`, `DEEP`, `AWAKE`. The list may be unsorted, may overlap, may have gaps with no data, may be empty, may contain marks dated after `now` (clock skew).
- `NightSettings(deadline: Instant?, pickedCycles: Int)`.
- `now: Instant`.
- `previousPlan: AlarmPlan?` (the plan from the previous sync, null on the first one).
- `wakeAlarmFiredAt: Instant?` (D5): whether the MAIN wake alarm has already fired this night, and only that alarm - `NightState.wakeAlarmFiredAt` on the app side, a field of its own, distinct from `NightState.phoneAlarmFiredFor` (the re-arm guard, set by ANY alarm firing). A mid-night rule 7 nap firing never sets this. Null until the wake alarm itself fires.
- `napAlarmsUsed: Int` (D5): how many post-wake nap alarms have actually FIRED this night, not how many have been armed - `NightState.napAlarmsUsed`, written by `PhoneAlarmReceiver` the instant one fires and persisted in its own small file. Defaults to 0.

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

Output `AlarmPlan(mode, wakeAt: Instant?, cycles: Int, referenceOnset: Instant?, onsetIsProjected: Boolean, reason: String, sleptSoFar: Duration, owedCycles: Int)`.

`wakeAt` is the single alarm for the night (D1: "one alarm, and it is the phone's"). Every tick arms the phone's `AlarmManager` alarm-clock alarm at this instant; the band is a sensor only and is never sent an alarm command (D2). `wakeAt` is null exactly when `mode` is `FINISHED`.

Write it as named steps, one small function each: `findReferenceOnset`, `chooseMode`, `computeWakeAlarm`, `describePlan`. `chooseMode` and `describePlan` are `when` blocks with one branch per rule.

**Reference onset.** `ASLEEP`: onset of the latest stretch, not projected. Otherwise `now + fallAsleepEstimate`, projected.

**What rule 7's "the planned alarm" means:** the deadline, and only the deadline (decided 2026-09-17 with the owner). There is no carried-forward boundary on a night with no deadline: an earlier plan's own alarm must never shorten the night, so `AlarmPlan` no longer carries a `wakeBoundary` field at all.

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

1. `FINISHED`, rule 1: the deadline is at or before `now`. `wakeAt = null`. The night is over for the engine.
2. D5's nap cap: once the wake alarm has fired at least once (`wakeAlarmFiredAt != null`) and `napAlarmsUsed` has already reached `MAX_NAP_ALARMS` (2), a genuinely NEW return to sleep - one whose reference onset differs from the previous plan's own, so this is not the same still-pending nap being re-confirmed on a later tick - is refused a third nap. What happens instead depends on whether a deadline is still ahead (`isPastDeadline` above has already ruled out a deadline at or before `now`, so any deadline reaching here still has time left on it): with a deadline, the alarm rings at the deadline (`DEADLINE_ONLY`, `wakeAt = deadline`) rather than the night ending outright - the deadline is the hardest promise the app makes, and letting a spent nap cap silently cancel it with time still to run would break that promise. Only when there is no deadline left to fall back on does the cap finish the night (`FINISHED`, `wakeAt = null`). Layered on top of rule 7 below: it never changes what rule 7 itself decides, it only adds a hard stop (or, with a deadline, a hard floor) once the two post-wake naps rule 7 would otherwise keep offering are both spent. See "Post-wake naps, capped at two" below. Band-detected wake never reaches `FINISHED` by itself any more (D3) - only the deadline, this cap (with no deadline left), or the owner's own confirmation (handled entirely at the app layer, never seen by the engine - see app-spec.md) end the night.
3. `NAP` (rule 7): `afterAwakening`, and either less than one cycle is still owed (`owedCycles == 0`, the night's total is nearly reached) - which needs no deadline and applies on any night - or there IS a deadline and less than one cycle fits before it (`referenceOnset + cycleLength > deadline`). The fitting test is a deadline test and nothing else: with no deadline the picked total is the only cap, so a still-owed cycle is always slept in full, however close an earlier plan's alarm was.
   - While `AWAKE`: `wakeAt = now + napLength`, capped by the deadline when there is one. It slides forward on every sync - UNLESS the wake alarm has already fired (`wakeAlarmFiredAt != null`), in which case this branch arms nothing at all (`wakeAt = null`, mode stays `NAP` with no alarm to show for it). Before this rule, the AWAKE branch stayed reachable indefinitely after the wake alarm fired, re-arming an alarm at `now + napLength` on every tick with nothing to ever cancel it - not even reliably self-cancelling, since ticks throttled by Doze can run late enough to clear the 20 min margin, letting it ring at full volume long after the owner got up. D4's own out-of-bed nudge, plus D5's capped naps below, cover the follow-up instead.
   - Once `ASLEEP`: `wakeAt = onset + napLength`, capped by the deadline when there is one. Never later than that, whatever the previous plan said: sync delay can never lengthen the nap. If that time is closer than `minAlarmLead` or already past, the pull-forward rule below applies (D8).
4. `DEADLINE_ONLY` (rule 5): there is a deadline, `cycles == 0`, and this is not after an awakening (first sleep of the night starts too close to the deadline, or not asleep yet). `wakeAt = deadline`.
5. `FULL_CYCLES` (rules 3, 4, 6): `wakeAt = referenceOnset + cycles * cycleLength`.

**Post-wake naps, capped at two** (D5). Once the wake alarm has fired, a further return to sleep still goes through rule 7 exactly as before - same nap-eligibility test, same `onset + napLength` - but each such nap is now counted. `napAlarmsUsed` (`NightState.napAlarmsUsed`) is incremented by the app layer, `PhoneAlarmReceiver`, the instant a nap alarm actually FIRES, never when it is armed - and persisted in its own small file, separate from the rest of `NightState`, so the count can never be lost to a concurrent tick's own state save. Counting at fire time, not arm time, is a deliberate correction: counting at ARM time let a single still-pending nap be recounted on every tick once its own alarm had fired and the owner simply stayed asleep past it - spending the whole two-nap cap after only one real nap, then ending the night with the owner still asleep and no alarm left to ring. Counting fires is also the literal reading of the owner's own words, "two nap alarms." Once that count reaches `MAX_NAP_ALARMS` (2), the next genuinely new return to sleep is capped from arming a third nap - see mode item 2 above for what happens instead (`FINISHED` with no deadline left, `DEADLINE_ONLY` with one still ahead). "Genuinely new" matters for two reasons: the same still-pending nap must not be double-counted just because a later tick re-confirms the same `wakeAt`, and rule 7's own `AWAKE`-state safety net (sliding `now + napLength` while lying awake, unconfirmed) must not be mistaken for a counted nap either - only a real, non-projected onset that differs from the previous plan's own counts. Deliberately separate from rule 7's own mid-night nap, which this does not touch: rule 7 fires whenever less than one cycle is owed after ANY awakening, all night, with no cap; D5 only adds a hard stop to naps taken AFTER the main wake alarm has already rung.

Worked example (from `PostWakeNapTest`, mirrored end to end against the app's own fire-time counting functions in `WholeMorningSequenceTest`): the wake alarm rings at 07:00 (`FULL_CYCLES`, the picked total spent) and fires - `wakeAlarmFiredAt` becomes 07:00, `napAlarmsUsed` is still 0. Still awake at 07:05 - rule 7's own sliding safety net arms nothing (the AWAKE-branch stop above), not yet counted either way. Falls back asleep at 07:11, onset 07:10: the first nap is armed, alarm 07:30 - `napAlarmsUsed` is STILL 0, since nothing has fired yet. That alarm fires at 07:30: `napAlarmsUsed` becomes 1. Awake again at 07:33; falls back asleep at 07:36, onset 07:35: the second nap is armed, alarm 07:55 - `napAlarmsUsed` stays 1 until that alarm actually fires. It fires at 07:55: `napAlarmsUsed` becomes 2, the cap. Awake at 07:58; falls asleep again at 08:01: a genuinely new, third return to sleep with the cap already spent and no deadline to fall back on, so the night goes straight to `FINISHED` instead of arming another nap. The same third return to sleep with a deadline still ahead (08:30) lands on `DEADLINE_ONLY` instead, alarm 08:30, per mode item 2 above (`PostWakeNapTest`'s and `WholeMorningSequenceTest`'s own F4 case).

**Pull-forward rule** (D8, any mode with a wake alarm): if the computed `wakeAt` is before `now + minAlarmLead`, it is pulled forward to `now + minAlarmLead`, rounded UP to the next whole minute (an instant already exactly on a minute boundary is unchanged). With a deadline the pulled-forward alarm is never later than the deadline. The pull-forward never changes the mode - there is no `OVERDUE` mode any more. The phone alarm fires exactly on time regardless of how it was computed, and D4's out-of-bed nudge plus D5's capped post-wake naps now cover "did not get up" from here. The old band-only overdue loop - re-arming every sync while the band still read asleep, capped at a fixed duration counted from the missed alarm - existed only to compensate for an alarm that could not be trusted to fire once; the phone's exact alarm needs no such compensation, so each pull-forward is simply recomputed fresh from `now` on whichever tick needs it, never carried over from an earlier one.

**The deadline is a planning cap only** (D1). It bounds `cycles` (via `capCyclesByDeadline`) and it is `wakeAt` itself in `DEADLINE_ONLY` mode, but it is never armed as a second, separate alarm the way the old phone-safety-alarm was: there is only ever the one `wakeAt` on the plan, and only ever the one alarm the app arms from it, on the phone.

**Known and accepted:** while lying awake the projected onset moves with `now`, so the plan's `wakeAt` moves at each sync and drops by one cycle when a cycle stops fitting. That is rule 1 working as intended, and the phone alarm and the Night screen follow it.

**The app has nothing left to filter** (D1/D2). Every one of the engine's own moves - the projected onset sliding forward while not yet asleep, a mode change, a pull-forward - reaches the phone alarm directly: re-arming `AlarmManager.setAlarmClock` is cheap and reliable on every tick, unlike the band's alarm slots were, so there is no band-write filter living outside the engine any more. The engine still knows nothing about how its output is armed; it is simply that "how it's armed" got much simpler. What the app needs is already on the plan: `referenceOnset` and `onsetIsProjected` say which onset a target was computed from.

`reason` is one plain English sentence for the night log with the numbers used, e.g. `"Asleep since 00:30, 5 of 5 picked cycles fit before 08:30, alarm 08:00"`. Format times in the zone passed to the function.

## Step 3: helpers for the UI

- `isSleepLengthAvailable(cycles, now, deadline, config)`: true when `now + fallAsleepEstimate + cycles * cycleLength <= deadline`, always true without a deadline. Drives the hatched picker options.
- `listWakeOptions(referenceOnset, settings, config, upToCycles = settings.pickedCycles)`: for k in 1..`upToCycles`, `referenceOnset + k * cycleLength`, dropping those after the deadline. Each option carries `k` and its sleep duration. Drives the timeline. `upToCycles` is a plain count, not a picker value, so 0 (nothing owed) is legal and lists nothing: the UI passes the current plan's `cycles`, so the timeline never offers a night the engine has already decided against - after waking at 05:30 of a 7.5 h night the screen lists the one cycle still owed, not a fresh 3 to 9 h.
- `summarizeNight(stretches, config)`: total sleep, and per stretch its onset, end, duration and `cycles` as a decimal rounded to one place (duration / cycleLength). Drives the morning report.
- `nextSyncDelay(plan, now, config): Duration?`: null when the mode is `FINISHED` (stop syncing). `frequentSyncDelay` in `NAP`, or when the wake alarm is within `nearAlarmSyncWindow`; else `normalSyncDelay`. Never zero, never negative.

## Validation

`pickedCycles` outside `allowedCycleCounts`, or a nonsensical `EngineConfig` (non-positive cycle length and so on), is a caller error: throw `IllegalArgumentException` with a clear message. This includes one cross-field check: `ringAutoStopAfter` must be strictly less than `outOfBedDelay`, or the out-of-bed nudge could fire while the wake alarm's own auto-stop is tearing the still-ringing service down. Nothing else throws. A deadline in the past is `FINISHED`. Bad band data is cleaned, never an error.

## Tests that must exist

One test class per function, plus `WholeNightSequenceTest` that replays a night as a series of calls, feeding each plan back in as `previousPlan`. Assert `mode` and `wakeAt` (not just `cycles`). Cover at least:

- Night 1 real data: sleep 00:30 to 08:14, awake marks 01:44 to 01:50, 02:29 to 02:33, 04:47 to 04:49, data gap 04:49 to 05:05, awake at 08:13. Fixture has sleep resuming at 05:05. Expect 4 stretches with onsets 00:30, 01:50, 02:33, 05:05, the last three with `followsAwakening`.
- A data gap between two sleep segments with no awake mark does not split the stretch.
- `LIGHT 00:30-04:00` + `AWAKE 01:44-01:50`: stretches 00:30-01:44 and 01:50-04:00, reference onset 01:50.
- `AWAKE 01:00-02:00`, `LIGHT 01:30-03:00`, `AWAKE 01:45-01:50`: the stretch 02:00-03:00 survives; stretches and state agree.
- `LIGHT 00:30-08:14` and `AWAKE 08:13-08:14` in both input orders: `AWAKE` both times.
- A mark dated after `now` is ignored.
- Rules 3, 4, 5, 6, 7 with and without a deadline, including the exact boundary (a cycle ending exactly at the deadline fits; one minute over does not).
- Picked 9 h at 00:40 with an 08:30 deadline: projected onset 00:55, 5 cycles, alarm 08:25. At 01:30: projected onset 01:45, 4 cycles, alarm 07:45.
- First sleep at 07:30 with an 08:30 deadline: `DEADLINE_ONLY`, alarm 08:30 (not a nap).
- Deadline 08:00, plan 08:00, awake 07:00 to 07:05: `NAP`, never a new full count; alarm never later than onset + 20 min and never later than 08:00. The same night with no deadline is `FULL_CYCLES` for the cycle still owed, not a nap. No deadline, awake at 03:00: what is still owed is recounted (rule 6).
- Already-slept sleep measured HOURS into the new stretch, not one minute into it: 3 h slept, awake, then back asleep for two more hours - `sleptSoFar` is still 3 h and the alarm is still 3 cycles from the new onset. One minute in, subtracting the current stretch by mistake changes nothing visible.
- A brief awakening on a night with NO deadline: only what is left of the total is owed (6 h slept of 7.5 h leaves one cycle, alarm 08:20), where a per-stretch count would plan five more cycles. The same night WITH a deadline is not proof on its own - the cap alone lands on the same time.
- No deadline, 6.5 h slept of the picked 7.5 h, back asleep at 05:40 with the previous plan's alarm at 06:30: `FULL_CYCLES`, alarm 07:10, night total 8 h. The same night with a 05:55 deadline: `NAP` at 05:55.
- Deadline 08:30, plan 08:00, awake at 06:30 then asleep 06:45: one cycle fits before 08:30, `FULL_CYCLES`, alarm 08:15.
- Nap: slides while awake over consecutive syncs, capped by the boundary, fixed once asleep. Late detection (onset 00:50, now 01:15): the nap alarm (onset + napLength = 01:10) has already passed, so it is pulled forward (D8) to now + 2 min = 01:17, mode staying `NAP` - not a separate `OVERDUE` mode.
- Slept through: onset 00:30, 5 cycles gives a raw alarm of 08:00. Still asleep at 08:05: pulled forward to now + 2 min = 08:07 (mode stays `FULL_CYCLES`). Still asleep at 08:06: pulled forward again, freshly, to 08:08 - each tick recomputes independently rather than keeping an earlier pulled-forward instant, since re-arming the phone costs nothing the way a band write used to. Still asleep at 08:10: 08:12. A pull-forward computed at a mid-minute now, e.g. 08:05:30, rounds UP to 08:08, never down; computed exactly on a minute boundary, e.g. 08:05:00, it still rounds up to 08:07 (the lead itself, not the current second, decides the target).
- Deadline 08:30, now 08:31: `FINISHED`, no throw, `nextSyncDelay` null. Deadline 30 s ahead: no alarm closer than `minAlarmLead`.
- An awake mark after the alarm time no longer finishes the night by itself (D3): with `owedCycles == 0` it falls through to rule 7 and produces `NAP`, not `FINISHED`. Only the deadline, D5's nap cap, or the owner's own confirmation (app layer only, see app-spec.md) end the night now.
- D5's post-wake nap cap: two genuinely new post-wake naps each produce `NAP`, counted only once each actually FIRES (not when armed); a third genuinely new return to sleep after the cap is spent produces `FINISHED` with no deadline left, or `DEADLINE_ONLY` at the deadline when one is still ahead (see `PostWakeNapTest`, `ExtraNightScenariosTest` and `WholeMorningSequenceTest`, worked out above). Rule 7's own `AWAKE`-state safety net, and the same nap merely re-confirmed on a later tick before its own alarm fires, must not be counted (`isPostWakeNapCapSpent`).
- Night crossing midnight (now 23:00, deadline 07:00 next day).
- Empty, unsorted, overlapping and zero-length segments.
- `nextSyncDelay` in every mode, never zero.
