# Decisions

All decided 2026-09-17 unless noted.

## Architecture

**Separate app that talks to unmodified Gadgetbridge.** No fork.
Why: Gadgetbridge keeps its F-Droid updates, our code stays small, and the Intent API already does the two things we need (start a sync, set band alarms). A fork would mean maintaining a large codebase, losing updates, and reinstalling Gadgetbridge.

**Our app owns the phone alarm** (Android alarm-clock scheduling), not Google's Clock app.
Why: the alarm moves many times a night. Other apps can create Clock alarms but cannot reliably move or delete them, so recalculations would pile up duplicates.

**Do not use the band's built-in smart alarm.** Our app computes the exact wake time and sets a normal band alarm.
Why: the band's smart alarm (8:30 with a 60 min window) vibrated exactly at 8:30 while the user was already awake. It likely depends on TruSleep, which is off. The Intent API also cannot set the smart flag or wake window.

**Build on the Windows PC with command-line tools only**: Java 21, Android SDK command-line tools in the user folder, Google platform-tools (adb).
Why: the phone is already connected and tested there; no IDE needed. Install not done yet, needs a go-ahead.

**No Nix.** Considered a Nix dev shell for reproducible tooling, but Nix only runs in WSL on Windows, which adds WSL setup and Wi-Fi adb pairing. Development will happen mainly on this Windows PC, so reproducibility comes from the Gradle wrapper, a pinned JDK toolchain and pinned SDK versions in the build files instead.

## Alarm rules

1. **Always wake at the end of a cycle.** Never mid-cycle. Cycle length is 90 min for now, to be calibrated from exported data.
2. **Sleep length picker: 4.5 h, 6 h, 7.5 h, 9 h.** Default 7.5 h. **This is a total for the whole night** (decided 2026-09-17): sleep already had is subtracted, so waking after 3 h of a 7.5 h night leaves 4.5 h, and the band alarm is set 4.5 h after you fall back asleep. A remainder that is not a whole number of cycles rounds to the nearest cycle, so the total can land up to 45 min over.
3. **No deadline:** band alarm = latest sleep onset + whatever is still owed of the picked total (rule 2). On the first sleep of the night that is the full picked length.
4. **With a deadline:** band alarm = latest onset + the largest whole number of cycles that ends by the deadline, never more than what is still owed.
5. **No full cycle fits before the deadline:** the band vibrates at the deadline.
6. **Recount after every awakening** the band marks. The alarm is measured from when you fall back asleep, but for what is still owed of the night's total (rule 2), not a fresh full count.
7. **Nap mode:** if you wake up and less than one full cycle is still owed of the night's total, the band wakes you 20 min after you fall back asleep - with or without a deadline. A second trigger applies only when there IS a deadline: less than one full cycle fits before it, and then the nap ends at the deadline if that comes sooner. On a night with no deadline the picked total is the only thing that ends the night, so an earlier plan's own alarm never cuts a still-owed cycle down to a nap (decided 2026-09-17). 20 min is before deep sleep usually starts.
   While awake in nap mode, the band alarm is kept 20 min ahead and slid forward each sync, then locked when sleep is detected, so sync delay cannot make the nap too long.
8. **Already awake when the band vibrates:** acceptable, no special handling.

## Phone alarm

- **With a deadline:** phone safety alarm always rings exactly at the deadline.
- **Without a deadline:** optional phone backup alarm, default 15 min after the band alarm.

## Syncing

- Every 15 min overnight, every 5 min in the last 30 min before the planned wake time.
- Opening the app triggers a sync, so the screen is current within about 20 s.
- Alarm math uses the sleep and wake timestamps in the data, not the sync time.

## Data and debugging

- **The app keeps a night log**: every sync (time, success, duration), every detected sleep and wake time, every alarm decision with its reason, every error. Exportable for analysis.
  Why: when a night goes wrong we must be able to answer "why" from data, including overnight sync failures.
- Cycle statistics (length per stretch in cycles) live in exported data and the morning report, not on the night screens.

## UI

See [design.md](design.md). Screens show hours, not cycles; cycles appear only in the morning report.

## Deferred (not v1)

- Get-out-of-bed habit reminder (vibrate ~15 min after actually waking, skip if steps show you are up).
- Skipping the alarm when already awake.

## Not a concern

- Sleep never detected: assume the band is always worn.
