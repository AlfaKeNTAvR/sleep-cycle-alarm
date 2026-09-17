# Plan

## Before implementation

- [ ] Mid-night sync test: sync once when waking in the night, note time and whether sleep bars appear. Export the database in the morning.
- [ ] Decide: sleep detection from band marks (if released mid-night) or from per-minute heart rate (fallback).
- [ ] Approve tool install on the Windows PC (Java 21, Android SDK command-line tools, platform-tools; about 3 GB).
- [ ] Choose implementation mode: Autonomous or Supervised.

## Phases

1. **Tooling.** Install the build tools, create the Kotlin + Jetpack Compose project, build and install an empty app on the Pixel over adb.
2. **Gadgetbridge bridge.** Start a sync, wait for the sync-finished broadcast, get the data into our app (database export, or Health Connect if the export file can't be read), set and dismiss band alarms by title.
3. **Sleep engine** (plain logic, unit tested, no Android code). Read samples into sleep and awake stretches; compute the band alarm, phone alarm and nap mode from the rules in [decisions.md](decisions.md). Test cases come from night 1 and from each rule.
4. **Alarms and scheduling.** Background service for the night: sync schedule (15 min, 5 min near wake), band alarm updates, phone alarm via Android alarm-clock scheduling, battery-optimization exemption.
5. **Night log and export.** Record every sync, detection, alarm decision and error; export as a file.
6. **UI.** Before bed screen and the night screen states from [design.md](design.md).
7. **Field testing.** Several real nights, export logs, calibrate cycle length and thresholds.

## Open questions

- Nap mode without a deadline: the restart rule makes a fresh full count after each awakening, so "less than one cycle left before the planned alarm" needs a reference point (the plan before waking?).
- Can our app read Gadgetbridge's database export under Android storage rules?
- Band battery cost of syncing every 5 to 15 min.
- Does the band mark lying still while reading as sleep?
- Estimated time to fall asleep used for greying out sleep lengths (assumed 15 min).
