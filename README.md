# sleep-cycle-alarm

An Android smart alarm for the Honor Band 5. It wakes you at the end of a 90-minute sleep cycle, counted from the moment you actually fall asleep, and never later than your deadline.

The app does not talk to the band directly. It drives [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge), which stays installed and unmodified, through Gadgetbridge's Intent API.

## Status

Implemented (2026-09-17): both Gradle modules build, `:engine` and `:app` unit tests pass. A final fix batch addressing three independent reviews (phone alarm reachability, the band alarm protocol, night-log completeness, UI/state seams) has been applied ahead of the first real night. See `AUTONOMOUS_DECISIONS_09_17_2026.md` for the full decision log and known limits.

## Docs

- [docs/decisions.md](docs/decisions.md): every decision made so far, with the reason.
- [docs/plan.md](docs/plan.md): implementation phases and open questions.
- [docs/findings.md](docs/findings.md): what we measured and proved on the real band and phone.
- [docs/design.md](docs/design.md): the screens.

## Hardware

- Band: Honor Band 5 ("Honor Band 5", MAC `AA:BB:CC:DD:EE:FF`).
- Phone: Google Pixel 10.
- Bridge: Gadgetbridge 0.94.0 from F-Droid.

## Build

```
source scripts/env.sh
./gradlew :app:assembleDebug
adb -s PHONE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

## First run on the phone

Before the first night, in Gadgetbridge:

- Settings > Developer options > Intent API: turn on "Allow activity sync trigger", "Broadcast on activity sync finish", "Allow database export trigger", "Broadcast on export".
- Auto export: turn on, and set a save location (the file our app's Setup screen will pick).
- Band gear > Developer options: turn on "Allow 3rd party apps to set alarms".
- Open the band's alarm list and clear the title of one more disabled alarm, so at least two slots are free or already ours (SCA-A / SCA-B); the Setup screen's "Test connection" checks this and tells you exactly how many more it needs.
- Turn off the band's own smart alarm (e.g. its default 08:30 one) if it is enabled - it will still vibrate on its own schedule regardless of this app, and Setup's connection test flags any such alarm as a non-blocking warning.

Then in our app's Setup screen:

- Confirm the band's Bluetooth MAC address (prefilled with the owner's one band).
- Pick the export file Gadgetbridge auto-exports to, via the system file picker.
- Grant the permissions Setup asks for: notifications, full-screen alarms (Android 14+), battery optimisation exemption, exact alarms.
- Run "Test connection" - it must pass within the last 24 h before "Start night" unlocks on the Before-bed screen.
- Use a deadline for the first nights. Without one, the wake time keeps moving until the band reports sleep, so nothing caps the night - the Before-bed screen says so under the deadline switch too.

## Try it at your desk (debug mode)

A debug build (`./gradlew :app:assembleDebug`) has a Debug/simulation screen a release build never has. It
lets you sit at the desk, drive a whole night by hand, and watch the plan, the band alarm, the phone alarm
and the night log all work without waiting for a real night. About 15 minutes end to end.

1. Install the **debug** build (`app-debug.apk`, per the Build section above - not the release one).
2. Open the app, go to **Setup** (gear icon), scroll to the bottom, tap **Debug**.
3. Turn on all three switches: **Simulated band data**, **Fast night**, **Dry run band commands**. With
   these two data/command switches both on, the app never needs Gadgetbridge or the band configured at all
   for this walkthrough - the checklist below no longer needs the band steps.
4. Tap back to **Setup**: only the phone-side items (Notifications, Full-screen alarms, Battery
   optimisation) should still be red. Grant any that are.
5. Tap **Done** to reach **Before bed**. You should see the amber warning banner naming all three active
   switches (**SIMULATED SLEEP DATA**, **BAND COMMANDS NOT SENT**, **FAST NIGHT**) and **Start night** enabled
   with no band connected. Pick **4.5 h** (3 cycles) on the "Sleep up to" row, leave the deadline switch off,
   and turn the **phone backup** switch on - with the defaults (5 cycles, backup off) the fast night is only
   25 min long and no phone alarm ever rings in this walkthrough.
6. Tap **Start night**. A **"This is a simulated night"** dialog appears - this confirmation exists so a
   simulated night can never start by accident at real bedtime. Tap **Start simulated night**.
7. You land on the **Night screen**: amber banner, a gear icon top-right (only in a debug build) that takes
   you back to the Debug screen without leaving the running night.
8. Tap that gear icon, then **I fell asleep now**. The **Simulated timeline** list grows a `LIGHT` entry
   right there. Tap back - the Night screen now shows a real onset and a band alarm about 15 minutes away
   (fast night: 5 min per cycle, 3 picked cycles = 15 min).
9. Wait about a minute, reopen Debug, tap **I woke up now**, wait a few seconds, tap **Fell back asleep
   now**. Waking this early after falling asleep still leaves plenty of room before the band alarm, so the
   plan restarts a full cycle count rather than switching to a nap - watch the Night screen's alarm time move
   out a little each time.
10. Repeat wake / fell-back-asleep once you are close to the current band alarm time (within one cycle
    length, i.e. within about 5 minutes of it). This time the Night screen switches to the **nap** card
    instead - only a short (3 min) nap fits before the wake boundary. Keep the app open and do not press
    anything else: the nap alarm actually reaches its own time and you can watch it fire (band alarm, then
    the phone backup) before anything turns to OVERDUE.
11. If you let the phone keep "sleeping" past the nap alarm instead of ending the night, the screen switches
    to **OVERDUE**. The band alarm really does move and re-arm on every sync while overdue, not just the
    on-screen countdown: each sync sends a fresh SET at now + `minAlarmLead`, rounded up to the next whole
    minute. In this walkthrough (dry run on) you will not feel anything on your wrist - watch the **Logs**
    screen instead: a `band_alarm_command` SET entry with `dryRun=true` every sync (fast night: every 1 min),
    each one confirmed a tick later. With dry run **off** and a real band connected, the band itself buzzes
    again on that same cadence - a real snooze, not just a number changing on screen. Once 5 minutes have
    passed since the missed alarm, the screen shows **Night finished** on its own; nobody has to press
    anything for this part.
12. Tap **I'm up, end night** / **Stop night** to see the morning report, built entirely from what you just
    simulated. Ending the night also resets all three Debug switches to off - the same happens on its own if
    the app sits unopened for 2 h after they were last changed, so a desk test like this one can never leak
    into a real bedtime by accident.
13. Open **Logs**: the night you just ran carries a **simulated** tag, and its file is named
    `night-sim-<yyyyMMdd-HHmm>.jsonl` (a real night is always `night-<yyyyMMdd-HHmm>.jsonl`, so the two can
    never be confused). Share it to see every `data` event tagged `source=simulated` and every
    `band_alarm_command` tagged `dryRun=true`.
14. Separately, any time no night is active (this button is disabled, with a reason shown underneath, while a
    night is running - it must never be able to replace the real night's own alarm): from the Debug screen,
    tap **Ring phone alarm in 1 min** to check the full-screen alarm activity, the sound and the
    notification's Stop button in daylight. This writes to `setup.jsonl`, not a night log - it is not a night.

## Known limits

- Reboot during the night (e.g. a system update): the phone alarm is restored only once the phone is unlocked once after the reboot (`BootReceiver` needs credential-protected storage). Direct Boot support, which would restore it before first unlock, is deferred - not fixed in this batch.
- Without a deadline, if the band never reports sleep (e.g. it is taken off), the wake time keeps moving forward all night and nothing rings until the band eventually reports sleep or the owner ends the night by hand.
