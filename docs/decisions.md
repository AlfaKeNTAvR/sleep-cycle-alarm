# Decisions

All decided 2026-09-17 unless noted.

## Architecture

**Separate app that talks to unmodified Gadgetbridge.** No fork.
Why: Gadgetbridge keeps its F-Droid updates, our code stays small, and the Intent API already does the two things we need (start a sync, set band alarms). A fork would mean maintaining a large codebase, losing updates, and reinstalling Gadgetbridge.

**Our app owns the phone alarm** (Android alarm-clock scheduling), not Google's Clock app.
Why: the alarm moves many times a night. Other apps can create Clock alarms but cannot reliably move or delete them, so recalculations would pile up duplicates.

**Do not use the band's built-in smart alarm.** Our app computes the exact wake time.
Why: the band's smart alarm (8:30 with a 60 min window) vibrated exactly at 8:30 while the user was already awake. It likely depends on TruSleep, which is off. The Intent API also cannot set the smart flag or wake window.
(Superseded 2026-09-20: "sets a normal band alarm" no longer applies - the app does not set any band alarm at all any more. See "Phone-only alarms" below.)

**Build on the Windows PC with command-line tools only**: Java 21, Android SDK command-line tools in the user folder, Google platform-tools (adb).
Why: the phone is already connected and tested there; no IDE needed. Install not done yet, needs a go-ahead.

**No Nix.** Considered a Nix dev shell for reproducible tooling, but Nix only runs in WSL on Windows, which adds WSL setup and Wi-Fi adb pairing. Development will happen mainly on this Windows PC, so reproducibility comes from the Gradle wrapper, a pinned JDK toolchain and pinned SDK versions in the build files instead.

## Alarm rules

1. **Always wake at the end of a cycle.** Never mid-cycle. Cycle length is 90 min for now, to be calibrated from exported data.
2. **Sleep length picker: 4.5 h, 6 h, 7.5 h, 9 h.** Default 7.5 h. **This is a total for the whole night** (decided 2026-09-17): sleep already had is subtracted, so waking after 3 h of a 7.5 h night leaves 4.5 h, and the alarm is set 4.5 h after you fall back asleep. A remainder that is not a whole number of cycles rounds to the nearest cycle, so the total can land up to 45 min over.
3. **No deadline:** alarm = latest sleep onset + whatever is still owed of the picked total (rule 2). On the first sleep of the night that is the full picked length.
4. **With a deadline:** alarm = latest onset + the largest whole number of cycles that ends by the deadline, never more than what is still owed.
5. **No full cycle fits before the deadline:** the alarm rings at the deadline.
6. **Recount after every awakening** the band marks. The alarm is measured from when you fall back asleep, but for what is still owed of the night's total (rule 2), not a fresh full count.
7. **Nap mode:** if you wake up and less than one full cycle is still owed of the night's total, the alarm rings 20 min after you fall back asleep - with or without a deadline. A second trigger applies only when there IS a deadline: less than one full cycle fits before it, and then the nap ends at the deadline if that comes sooner. On a night with no deadline the picked total is the only thing that ends the night, so an earlier plan's own alarm never cuts a still-owed cycle down to a nap (decided 2026-09-17). 20 min is before deep sleep usually starts.
   While awake in nap mode, the alarm is kept 20 min ahead and slid forward each sync, then locked when sleep is detected, so sync delay cannot make the nap too long.
   **Post-wake naps are capped at two** (D5, decided 2026-09-20): once the main wake alarm has fired, this same nap mode still applies to each further awakening, but only the first two such naps get their own alarm - a third genuinely new return to sleep finishes the night outright. This is a separate mechanism from rule 7's own mid-night nap above, which stays uncapped: the cap only ever governs naps taken after the main wake alarm has already rung. See "Phone-only alarms" below.
   (Superseded 2026-09-20, during implementation: "finishes the night outright" only holds when there is no deadline left ahead - with one still ahead the alarm holds at the deadline instead of the night ending. Naps are also now counted when they FIRE, not when they are armed. See "Fixes during implementation" below.)
   (Superseded again 2026-09-20, second implementation pass: "a separate mechanism ... which stays uncapped" is no longer true. The cap now counts every nap alarm that fires all night, mid-night or post-wake alike - the same two-nap budget covers both. See "Wake and nudge follow-up fixes" below.)
8. **Already awake when the alarm rings:** acceptable, no special handling.

## Phone alarm (superseded 2026-09-20 - see "Phone-only alarms" below)

- **With a deadline:** phone safety alarm always rings exactly at the deadline.
- **Without a deadline:** optional phone backup alarm, default 15 min after the band alarm.

The phone is no longer a backup to anything: it is the only alarm there is. See "Phone-only alarms" below.

## Syncing

- Every 15 min overnight, every 5 min in the last 30 min before the planned wake time.
- Opening the app triggers a sync, so the screen is current within about 20 s.
- Alarm math uses the sleep and wake timestamps in the data, not the sync time.

## Data and debugging

- **The app keeps a night log**: every sync (time, success, duration), every detected sleep and wake time, every alarm decision with its reason, every error. Exportable for analysis.
  Why: when a night goes wrong we must be able to answer "why" from data, including overnight sync failures. Night 1 (2026-09-18) is what that bought: the log proved both of that night's bugs from its own fields.

## The band alarm (decided 2026-09-18, after night 1) - superseded 2026-09-20

Kept for the record: this is why the band alarm mechanism was patched on 2026-09-18, and it is exactly the mechanism that turned out not to be fixable, which is why 2026-09-20's decision below deletes it rather than patching it again.

- **A DISMISS never disarms the band.** `DISMISS_ALARM` only edits Gadgetbridge's database. The band keeps whatever was LAST WRITTEN to each slot and only a SET into that slot replaces it. Night 1's log showed slot 2 reading `enabled:false, title:""` for an hour and the band vibrating at its old 07:04 anyway.
- **One title, one slot, for the whole night**, replacing decision 11's two alternating titles. A move is DISMISS our title then SET the new time under the same title, in that order, in one tick, so the SET reclaims the slot the DISMISS just freed. Why: the gap the alternating titles existed to avoid does not exist on this hardware, while the second slot they used really did stay armed at a stale time. On night 1 the owner was woken twice.
- **While awake, the band keeps the time it already has.** The wake time is only moved when the band reports a real, non-projected onset. Why: every short awakening put the engine back on a projected onset that slides with the clock, which walked the band alarm 77 min across night 1. The phone alarm and the screens still follow the projected onset, which is what they are for.
- **Nothing can switch a band alarm off from this app.** Gadgetbridge only omits an alarm from what it sends the band when that alarm is marked "unused", which is a long-press in its own alarm list and is not reachable through the Intent API. So the night ends with an alarm still armed, and the app says so: the morning report and the night log both name the time. Clearing it is the owner's: long-press it in Gadgetbridge and mark it unused, or let the next night's first alarm overwrite that slot.

Even with the one-title-one-slot fix above, the band alarm still fired at 10:47 on the night of 2026-09-20 - a leftover alarm the app's own DISMISS could not touch, plus three re-buzzes per dismiss from the band's own firmware. The fix below stops trying to make the band alarm trustworthy and removes it instead.

## Phone-only alarms (decided 2026-09-20)

Why: night of 2026-09-20 showed the band alarm cannot be controlled well enough to be the wake mechanism - see the superseded section above. The band becomes a sensor only; every alarm moves to the phone, where `AlarmManager`'s alarm-clock API already gives exact firing, Doze exemption, a full-screen ring activity, clean cancellation and reboot restore.

- **D1, one alarm, and it is the phone's.** `AlarmPlan.bandAlarm` is renamed `wakeAt`; `AlarmPlan.phoneAlarm` is gone. Every tick arms the phone alarm at `plan.wakeAt`. The deadline is a planning cap only - it is never itself a second alarm.
- **D2, the band stops receiving alarm commands.** Every band-alarm mechanism above (dismiss/set, one-title-one-slot, the awake-hold retargeting filter, the leftover-alarm report) is deleted outright, not patched again. The band keeps every sensor path: sync, export, database read.
- **D3, the owner's own tap ends the night, not the band.** The old rule - `FINISHED` as soon as the band reports `AWAKE` and the alarm time has passed - is the bug that lost sleep: falling back asleep after the alarm got the owner nothing, because the app had already decided the night was over. Now the night ends only when the owner taps "I'm awake" (or "Stop night"), the deadline passes, or D5's nap cap is spent.
- **D4, the out-of-bed nudge.** Ten minutes after any alarm rings, a second alarm rings - deliberately not a snooze, and not optional: the owner wants the ten minutes of lying there, then a hard nudge. This replaces the "get-out-of-bed habit reminder" that used to sit under Deferred, in a simpler, unconditional form (no step-count check).
  (Superseded 2026-09-20, second implementation pass, H7.1: raised from ten to fifteen minutes. See "Wake and nudge follow-up fixes" below.)
- **D5, post-wake naps capped at two.** If the wake alarm has fired and the owner has not confirmed awake, and the band then reports sleep again, that is a nap under rule 7 exactly as before, but now counted: two per night, then the third genuinely new return to sleep finishes the night instead. Separate from rule 7's own mid-night nap, which stays uncapped.
  (Superseded 2026-09-20, during implementation, in two ways - see "Fixes during implementation" below: the count is taken when a nap alarm actually FIRES, not when it is armed; and "finishes the night instead" only applies when no deadline is left ahead - with one still ahead the alarm holds at the deadline.)
  (Superseded again 2026-09-20, second implementation pass, G8: "separate from rule 7's own mid-night nap, which stays uncapped" no longer holds - the cap now counts every nap alarm that fires all night, mid-night or post-wake alike. See "Wake and nudge follow-up fixes" below.)
- **D6, the ring screen gets two actions.** "Stop" silences the alarm only, the night keeps running. "I'm awake" silences it and ends the night, recording when.
- **D7, the removed toggle and warning.** The "phone alarm backup" toggle on Before bed, `AppSettings.phoneBackupEnabled`, and the "no phone alarm tonight" warning are all removed - there is now always a phone alarm, so none of them can ever have meant anything again.
- **D8, the overdue rule collapses into the sequence above.** The old `OVERDUE` mode re-armed the band every ~5 min while it still read asleep, capped at 30 min - a workaround for a wake mechanism that might not fire. The phone alarm fires exactly on time, so the catch-up loop is gone; D4 and D5 now cover "did not get up". `minAlarmLead`'s pull-forward survives unchanged.

## UI

See [design.md](design.md). Screens show hours, not cycles; cycles appear only in the morning report. Cycle statistics (length per stretch in cycles) live in exported data and the morning report, not on the night screens.

## Deferred (not v1)

- Skipping the alarm when already awake.

## Not a concern

- Sleep never detected: assume the band is always worn.

## Fixes during implementation (decided 2026-09-20, after "Phone-only alarms" above)

Six corrections made while implementing the "Phone-only alarms" decisions, before the first real night on the new code. Where one of these supersedes wording earlier in this file, that wording is marked in place rather than deleted; this section is the reasoning.

- **Nap counting moved from arm time to fire time.** `NightState.napAlarmsUsed` is now incremented by `PhoneAlarmReceiver` the instant a nap alarm actually FIRES, never when `armPhoneAlarmIfNeeded` arms it. Supersedes D5's "two per night" wording above wherever it read as arm-time counting.
  Why: counting at arm time let a single still-pending nap be recounted on every tick once its own alarm had fired and the owner simply stayed asleep past it - the two-nap cap was spent after only one real nap, and the night then ended with the owner still asleep and no alarm left to ring. Counting fires is also the literal reading of the owner's own words, "two nap alarms."
- **A spent nap cap no longer ends the night outright when a deadline is still ahead.** Supersedes rule 7's and D5's "finishes the night" wording above: with a deadline still ahead, the mode becomes `DEADLINE_ONLY` (alarm at the deadline) instead of `FINISHED`. `FINISHED` by the cap now happens only when there is no deadline left to fall back on.
  Why: the deadline is the hardest promise the app makes. The old ordering let a spent nap cap silently cancel it while time still remained.
- **`NightState.wakeAlarmFiredAt` split out from `phoneAlarmFiredFor`.** `NightState` gained its own `wakeAlarmFiredAt` field: the MAIN wake alarm's own fired instant, never a nap's. `phoneAlarmFiredFor` stays the re-arm guard (whichever alarm - wake or nap - fired last). Supersedes any reading of the decisions above as if one field served both roles.
  Why: with a single shared field, a mid-night rule 7 nap alarm firing could switch on the nap-cap machinery even though the main wake alarm had never fired that night.
- **Rule 7's `AWAKE`-state safety net stops sliding once the wake alarm has fired.** Before this fix it stayed reachable indefinitely after the wake alarm rang, re-arming an alarm at `now + napLength` on every tick with nothing to ever cancel it - not even reliably self-cancelling, since Doze can stretch ticks late enough to clear the 20 min margin, letting it ring at full volume long after the owner left for the day. D4's out-of-bed nudge and D5's capped naps cover the follow-up instead.
- **`EngineConfig.ringAutoStopAfter` added** (9 min; the fast debug night that once shortened it is gone, and a simulated night now compresses it through the clock instead - see V1 in "Debug mode: a simulated clock replaces fast night"): how long a ringing alarm rings before stopping itself if nobody acts. `validateConfig` now requires it strictly less than `outOfBedDelay`, enforced rather than left to coincidence: the two used to both default to 10 min, measured from different instants, and the out-of-bed nudge routinely fired a moment before this auto-stop tore the still-ringing service down, swallowing it.
  (Superseded 2026-09-20, third implementation pass: "20 s on a fast debug night" no longer applies - the fast-night duration shrink is gone. `ringAutoStopAfter` is always 9 real minutes now, on every night, and is never itself sped up by a simulated clock. See "Debug mode: a simulated clock replaces fast night" below.)
- **The `FINISHED` reason text now names the cause.** With no deadline, the log/UI reason for `FINISHED` reads "Night finished, the 2 nap alarms are used up" instead of a bare "Night finished" - the two ways a no-deadline night can end (this cap, or "I'm awake"/"Stop night") now read differently in the log.

Also caught and fixed in the same pass, not itself a design decision: reboot recovery (`BootReceiver`) now always resumes ticking whenever a night state exists at all, rather than returning early and orphaning the night in some cases. Only the phone alarm's own (re)arming stays conditional, gated by `shouldArmPhoneAlarm` (never a null, past, or already-fired `wakeAt`).

## Wake and nudge follow-up fixes (decided 2026-09-20, second implementation pass)

Five more decisions made after a further night's use of the "Phone-only alarms" code, all superseding wording earlier in this file rather than replacing it outright - see each marked spot above.

- **H7.1, the out-of-bed nudge moves from ten to fifteen minutes.** Same mechanism as D4, just a longer wait before it rings.
- **H7.2, a nap supersedes a still-pending nudge.** The nudge's whole premise is that the owner is awake and not getting up. Once a tick detects them asleep again and arms a nap, that premise is false, and letting an earlier alarm's nudge ring anyway would wake them mid-nap at full volume. So arming a nap cancels any nudge (and its H7.3 pre-check below) still pending from an earlier alarm. Nothing is lost: every alarm that fires arms its own fresh nudge 15 minutes later regardless, nap or not.
- **H7.3, a pre-nudge check.** Two minutes before the nudge is due, the app silently re-syncs the band and asks whether the owner is asleep right now - not merely what the last tick happened to know, which can be minutes stale by the time the nudge is about to ring. Confirmed asleep: the nudge is cancelled, the nap already in progress owns the wake-up. Anything else - awake, no data yet, a sync that failed, timed out, or returned only stale data - lets the nudge ring untouched.
  Why: the owner's own framing, deliberately fail-open. A nudge that did not need to ring is a far smaller harm than one that was needed and never rang, so every uncertain case defaults to ringing rather than cancelling.
- **G8, the nap cap now counts the whole night, not just after the wake alarm.** D5's cap originally only engaged once the main wake alarm had fired at least once, leaving rule 7's own mid-night nap uncapped (see the two spots marked above). That scoping left a gap: a night that uses up the whole picked total through repeated mid-night waking can enter its very first nap without any `FULL_CYCLES`/`DEADLINE_ONLY` alarm ever having rung, and the old guard let that count run unbounded. The cap now counts every nap alarm that fires, mid-night or post-wake alike, toward the same two-nap budget for the whole night.
- **H1/H2, `NightState` gains `morningAlarmAt` and `lastNapAlarmFiredAt`.** Both close gaps in rule 7's own sliding mid-night nap (see engine-spec.md's "Modes" for exactly where each is read):
  - `morningAlarmAt` is the night's own morning alarm time, latched from whichever tick last produced a `FULL_CYCLES` or `DEADLINE_ONLY` plan, never overwritten by a nap's own sliding alarm. It replaces an earlier guard built on the previous tick's own plan, which turned out to be dead code in exactly the case it existed for: while lying awake past the wake alarm, rule 7 re-arms an alarm at `now + napLength` on every tick, and the previous tick's own value IS that slid nap, always ahead of `now` by construction, so a guard comparing against it could never fire. `morningAlarmAt` is a fact the plan never carried before, and it stops the slide for good, even on a tick where the wake alarm's own firing was never recorded (a failed state save, a reboot, a delivery race).
  - `lastNapAlarmFiredAt` is the most recent nap alarm's own fired instant. When the owner sleeps straight through a nap alarm, the next plan needs to tell "this alarm already rang for this exact stretch" apart from "this target merely happens to be overdue" - only the first case should skip the ordinary two-minute pull-forward and instead give a fresh twenty minutes measured from the nap that just fired, not a bare reprise of it measured from `now`. Without this, the target would recompute later and later on every subsequent tick, never actually reached however long the owner stayed asleep.

## Debug mode: a simulated clock replaces fast night (decided 2026-09-20, third implementation pass)

Why: the old "fast night" switch shrank `EngineConfig`'s own durations (a 5-minute cycle length and so on), so the times shown on screen were still real wall-clock times - there was no way to ask "what happens if it is 03:10 and I wake up now" without actually waiting until 03:10. Replaced outright with a real simulated clock the whole app reads its own notion of "now" from.

- **One clock, two knobs.** A chosen speed (1x, 10x, 60x, or 600x real time) and, separately, a jump to any chosen wall-clock time. Changing speed never itself moves the clock, only how fast it runs from that moment on. `AlarmManager` only ever understands real time, so every instant handed to it is converted back to a real instant at the moment of arming; everything else the app does - night state, plans, logs, segments, the simulated sleep timeline - runs on virtual time. `EngineConfig`'s own durations are never shrunk any more: shrinking them as well as warping the clock would multiply the two effects together (a 5-minute cycle at 600x would be half a second).
- **Requires simulated band data.** The speed dial and the jump are both locked unless "Simulated band data" is on: a warped clock against real band data is nonsense, since the band's samples carry real timestamps and an engine reading a virtual `now` against them would see data that looks hours stale and conclude the owner never slept. Turning simulated band data off clears any active warp too, so the two can never be live at once.
- **Speed 1 can still carry an applied jump.** "It is now 03:00, let me poke at things by hand" is a legitimate mode on its own - only "Reset to real time" discards a jump; picking speed 1 on its own just means time now runs at its true rate from wherever it was jumped to.
- **The jump is refused while a night is active.** It would move time out from under alarms already armed at real instants, and out from under an engine that has already committed decisions against the pre-jump timeline.
- **One "Asleep" toggle, not three buttons.** The old fell-asleep / woke-up / fell-back-asleep buttons collapsed into a single toggle, since "asleep again after waking" and "asleep for the first time" are the same fact from the engine's point of view.
- **The ring test stays real.** "Ring phone alarm" rings 5 real seconds later regardless of any active warp - it is a daylight check of the ring path, never part of a simulated night.
- **The debug banner and the night notification show the simulated time and speed** whenever the clock is warped, e.g. "SIMULATED 03:15, 60x" (never "1x" - speed 1 with a jump applied just shows the time). Without it there would be no way to tell what time the app thinks it is.

## The same alarm is never rung twice, and a nap never displaces a pending morning alarm (H8, decided 2026-09-21)

Two defects the owner hit on a simulated night at 60x, both on the path from sleep data to a ringing alarm, fixed together because they are the same confusion: an alarm that has already been decided, or already rung, is not something a later tick may re-decide.

- **H8a, a spent morning alarm is never pulled forward.** The morning target is a fixed instant for the whole night (the onset plus whole cycles, or the deadline). After it rang, every following tick recomputed that same instant, found it in the past, and D8 moved it to `now + minAlarmLead` - a brand new alarm two minutes out, armed and rung again on every tick for as long as the band still read asleep. The owner saw it as "it shifts the alarm a couple of minutes forward, every single time". Now, once `wakeAlarmFiredAt` is at or after that target, the plan carries no alarm at all (`wakeAt` null, mode unchanged), and D4's out-of-bed nudge plus D5/G8's capped naps own the follow-up - exactly what F5 already decided for rule 7's own AWAKE branch. D8 itself is untouched for an alarm that never rang (a tick lost to Doze, a failed arming, a late reboot): that case is the whole reason D8 exists.
- **H8b, rule 7 defers to a morning alarm that is still pending.** Waking a few minutes before the alarm is rule 7 (awake, picked total spent), and its sliding `now + napLength` target is LATER than the morning alarm. The phone has ONE alarm slot for the wake alarm and every nap alike, so each slid target overwrote the alarm the owner actually asked for, and H1 then stopped the sliding for good the moment that alarm time passed - a night that ended having rung nothing at all. The owner reported it as "the alarm never fired". Rule 7 now hands the pending morning alarm back instead of arming anything over it. Nothing is lost: if the owner does doze off, the next tick sees them asleep and arms a real nap from that onset, which is D5/G8's own path anyway.
- **Two app-layer consequences, decided with it.** A `FULL_CYCLES`/`DEADLINE_ONLY` plan that has no alarm left to arm must not ERASE the latched `morningAlarmAt` (it keeps the previous value), and a firing AT the latched morning alarm time counts as the wake alarm even when the plan that armed it was `NAP` - otherwise the night's real wake-up would spend one of the two nap alarms and leave `wakeAlarmFiredAt` unrecorded.
- **Also corrected here: a nap supersedes a pending out-of-bed nudge only when the owner is actually asleep.** H7.2 now also requires the plan to be measured from a REAL onset, not a projected one. A `NAP`-mode plan covers lying awake too, so the nudge was being cancelled in exactly the situation it exists for.

The whole sequence - ticks scheduled by `nextSyncDelay`, the phone alarm armed and re-armed, alarms actually RINGING, and the four fire-time facts fed back into the next plan - is now replayed end to end in the engine's own `AlarmSequenceReplayTest`. Every earlier sequence test hand-picked when an alarm fired, which is why neither defect was visible to any of them.
