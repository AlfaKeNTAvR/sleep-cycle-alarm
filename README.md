# sleep-cycle-alarm

An Android smart alarm for the Honor Band 5. It wakes you at the end of a 90-minute sleep cycle, counted from the moment you actually fall asleep, and never later than your deadline.

The alarm rings on the phone, not the band. The band only reports when you are asleep - it drives [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge), which stays installed and unmodified, through Gadgetbridge's Intent API, and never receives an alarm command from this app.

## Status

Implemented: both Gradle modules build, `:engine` and `:app` unit tests pass. The wake alarm is phone-only (see [docs/decisions.md](docs/decisions.md)'s "Phone-only alarms"): the band is a sleep sensor only, and the app owns the whole alarm lifecycle through Android's `AlarmManager`. See `AUTONOMOUS_DECISIONS_09_17_2026.md` for the original implementation's decision log and known limits.

## Docs

- [docs/decisions.md](docs/decisions.md): every decision made so far, with the reason.
- [docs/plan.md](docs/plan.md): implementation phases and open questions.
- [docs/findings.md](docs/findings.md): what we measured and proved on the real band and phone.
- [docs/design.md](docs/design.md): the screens.

## Hardware

- Band: Honor Band 5.
- Phone: Google Pixel 10.
- Bridge: Gadgetbridge 0.94.0 from F-Droid.

## Build

```
source scripts/env.sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First run on the phone

Before the first night, in Gadgetbridge:

- Settings > Developer options > Intent API: turn on "Allow activity sync trigger", "Broadcast on activity sync finish", "Allow database export trigger", "Broadcast on export".
- Settings > Automations > Auto export database: turn on "Auto export enabled", then set "Export location" to a save location our app can read, e.g. Documents (the file our app's Setup screen will pick). This location must be set, or Gadgetbridge's export trigger (what our app sends every sync) writes nothing.
- Do NOT use Settings > Data management > Export Data instead: since Android 11 that writes inside Gadgetbridge's own app-private folder, which our app cannot read and the system file picker will not even show.
- Nothing to set up on the band's own alarm list: the band never receives an alarm command from this app (the alarm rings on the phone), so there is no slot to free, no smart-alarm slot to park, and no band alarm to turn off on this app's account. If the band has its own smart alarm enabled from before, it will still vibrate on its own schedule, entirely independent of this app - turn it off in the band's own alarm list if you do not want it.

Then in our app's Setup screen:

- Confirm the band's Bluetooth MAC address (prefilled with the owner's one band).
- Pick the export file Gadgetbridge auto-exports to, via the system file picker.
- Grant the permissions Setup asks for: notifications, full-screen alarms (Android 14+), battery optimisation exemption, exact alarms.
- Run "Test connection" - it must pass within the last 24 h before "Start night" unlocks on the Before-bed screen.
- Use a deadline for the first nights. Without one, the wake time keeps moving until the band reports sleep, so nothing caps the night - the Before-bed screen says so under the deadline switch too.

## Try it at your desk (debug mode)

A debug build (`./gradlew :app:assembleDebug`) has a Debug/simulation screen a release build never has. It
lets you sit at the desk, drive a whole night by hand, and watch the plan, the phone alarm, the out-of-bed
nudge and the night log all work without waiting for a real night. About 15 minutes end to end.

1. Install the **debug** build (`app-debug.apk`, per the Build section above - not the release one).
2. Open the app, go to **Setup** (gear icon), scroll to the bottom, tap **Debug**.
3. Turn on both switches: **Simulated band data** and **Fast night**. With simulated data on, the app never
   needs Gadgetbridge or the band configured at all for this walkthrough - the checklist below no longer
   needs the band steps. There is no third "dry run" switch any more: the phone alarm is the only alarm this
   app ever arms, so there is nothing left that a dry run would need to suppress - it always actually rings,
   simulated night or not.
4. Tap back to **Setup**: only the phone-side items (Notifications, Full-screen alarms, Battery
   optimisation) should still be red. Grant any that are.
5. Tap **Done** to reach **Before bed**. You should see the amber warning banner naming both active switches
   (**SIMULATED SLEEP DATA**, **FAST NIGHT**) and **Start night** enabled with no band connected. Pick
   **4.5 h** (3 cycles) on the "Sleep up to" row and leave the deadline switch off - there is no phone-backup
   switch any more, since the phone alarm always rings, deadline or not.
6. Tap **Start night**. A **"This is a simulated night"** dialog appears - this confirmation exists so a
   simulated night can never start by accident at real bedtime. Tap **Start simulated night**.
7. You land on the **Night screen**: amber banner, a gear icon top-right (only in a debug build) that takes
   you back to the Debug screen without leaving the running night.
8. Tap that gear icon, then **I fell asleep now**. The **Simulated timeline** list grows a `LIGHT` entry
   right there. Tap back - the Night screen now shows a real onset and an alarm about 15 minutes away
   (fast night: 5 min per cycle, 3 picked cycles = 15 min).
9. Wait a couple of minutes, reopen Debug, tap **I woke up now**, wait a few seconds, tap **Fell back asleep
   now**. The picked length is the whole night's budget (3 cycles = 15 min here), so every minute you "slept"
   is subtracted from it: the plan does not start a fresh count from the new onset, it counts only what is
   still owed. Watch the Night screen's timeline lose an option each round, and the alarm land roughly 15
   minutes after you first fell asleep however often you wake.
10. Keep repeating wake / fell-back-asleep until about 13 of the 15 minutes have been slept in total (the
    timeline is down to its last option, and the alarm is only a few minutes out). The next time you fall
    back asleep, less than half a cycle is left of the budget, nothing whole is owed, and the Night screen
    switches to the **nap** card: a short 3 min nap from that onset (rule 7 - this first nap is not yet
    capped, since the main wake alarm has not rung even once this night). This is the way to reach the nap
    card on a night with no deadline - using up the total is the only thing that ends such a night, so waking
    near the previous alarm no longer produces a nap by itself. (With the deadline switch on instead, a nap
    also appears as soon as less than one cycle fits before the deadline.) Keep the app open and do not press
    anything else: the nap alarm actually reaches its own time and you can watch it fire.
11. When that alarm rings, you land on the ring screen with two buttons - **Stop** (silences it, the night
    keeps running) and **I'm awake** (silences it and ends the night right there, landing on the morning
    report). Tap **Stop**, then about 30 s later (fast night's shortened out-of-bed delay) a second alarm
    rings on its own: the out-of-bed nudge, worded "Time to get up" - the same two buttons, same either-way
    behaviour. Tap **Stop** there too. Now reopen Debug and drive **I woke up now** / **Fell back asleep now**
    again: this return to sleep, and the one after it, are each a counted post-wake nap (capped at two), so
    you get two more short nap alarms exactly like the first. The third time you drive the app back to sleep
    after a nap alarm has rung, the night finishes immediately on its own - no fixed wait, unlike the old
    band-only overdue snooze this replaced - and the screen shows **Night finished** with an **End night**
    button, without you having to press "I'm awake" to get there.
12. Tap **I'm up, end night** / **Stop night** / **End night** to see the morning report, built entirely from
    what you just simulated. Ending the night also resets both Debug switches to off - the same happens on
    its own if the app sits unopened for 2 h after they were last changed, so a desk test like this one can
    never leak into a real bedtime by accident.
13. Open **Logs**: the night you just ran carries a **simulated** tag, and its file is named
    `night-sim-<yyyyMMdd-HHmm>.jsonl` (a real night is always `night-<yyyyMMdd-HHmm>.jsonl`, so the two can
    never be confused). Tap the row to reopen that night's summary - the same report you just saw - or use its
    three-dot button to **Share** it, and see every `data` event tagged `source=simulated`, every
    `phone_alarm_fired` and `out_of_bed_alarm_fired` entry the simulated night actually rang, and `night_end`
    recording whether it ended by your own "I'm awake" tap or on its own. The same menu's **Delete** removes a
    night for good after one confirmation, which is how to clear desk-test logs out.
14. Separately, any time no night is active (this button is disabled, with a reason shown underneath, while a
    night is running - it must never be able to replace the real night's own alarm): from the Debug screen,
    tap **Ring phone alarm in 1 min** to check the full-screen alarm activity, the sound and the
    notification's Stop button in daylight. This writes to `setup.jsonl`, not a night log - it is not a night.

## Known limits

- Reboot during the night (e.g. a system update): the phone alarm is restored only once the phone is unlocked once after the reboot (`BootReceiver` needs credential-protected storage). Direct Boot support, which would restore it before first unlock, is deferred - not fixed in this batch.
- Without a deadline, if the band never reports sleep (e.g. it is taken off), the wake time keeps moving forward all night and nothing rings until the band eventually reports sleep or the owner ends the night by hand.
