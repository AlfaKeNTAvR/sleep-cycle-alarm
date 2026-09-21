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
lets you sit at the desk, run a whole night on a clock that moves faster than real time, and watch the plan,
the phone alarm, the out-of-bed nudge and the night log all work without waiting for a real night.

### How to use debug mode

The Debug screen has three groups of controls, in the order they appear:

- **Simulated band data** (switch): sleep segments come from the **Asleep** toggle further down instead of a
  real Gadgetbridge sync. Turn this on first - the two clock controls below are both locked until it is,
  since a warped clock against real band data makes no sense (the band's timestamps would always read hours
  stale against a virtual "now").
- **Simulated clock speed** (1x / 10x / 60x / 600x) and **Set simulated time**: the app's own idea of "now"
  runs this many times faster than the real clock, or jumps straight to a chosen hour and minute. Everything
  the app computes - the plan, the log, the alarms - is measured against that virtual time; only the instant
  actually handed to Android's alarm system is converted back to a real one, so alarms genuinely ring, just
  sooner in real time than the virtual gap suggests. Both controls are locked until Simulated band data is on,
  and the time jump is locked outright while a night is running - pick your speed and starting time before
  tapping Start night, not during. Changing speed never itself moves the clock, only how fast it runs from
  then on. **Reset to real time** is the only thing that discards a jump.
- **Asleep** toggle and **Clear simulated sleep**: flips the simulator between asleep and awake, the same way
  the band would report it. Locked until Simulated band data is on. **Ring phone alarm**, lower down, is
  unrelated to all of this: a standalone test alarm 5 real seconds out, always real time regardless of the
  simulated clock, for checking the ring screen in daylight with no night running.

**The banner**: once any control above is live, an amber banner reading **SIMULATED** and/or
**HH:mm[, Nx]** appears on Before bed, the Night screen and the tracking notification. The time is
the app's own current virtual time; the multiplier is left off at 1x, so a jump with no speed change just
shows the time.

**Order of operations for a full simulated night**: turn on Simulated band data, pick a speed (60x is a good
balance - a 4.5 h night finishes in a few minutes and the UI is still easy to follow), start the night, then
drive it entirely from the Debug screen's Asleep toggle. Keep the app open throughout: below one real minute
of delay, a sync tick runs from a coroutine inside the app process rather than Android's alarm system, since
the alarm system's own Doze throttling would otherwise swallow a tick that is only seconds away in real time -
which means a simulated night dies if the app process is killed, unlike a real one.

### Walkthrough

1. Install the **debug** build (`app-debug.apk`, per the Build section above - not the release one).
2. Open the app, go to **Setup** (gear icon), scroll to the bottom, tap **Debug**.
3. Turn on **Simulated band data**. With it on, the app never needs Gadgetbridge or the band configured at
   all for this walkthrough - the checklist below no longer needs the band steps. Pick **60x** on the
   Simulated clock speed row.
4. Tap back to **Setup**: only the phone-side items (Notifications, Full-screen alarms, Battery
   optimisation) should still be red. Grant any that are.
5. Tap **Done** to reach **Before bed**. You should see the amber banner reading **SIMULATED SLEEP DATA  ·
   SIMULATED HH:mm, 60x** (the current virtual time) and **Start night** enabled with no band connected. Pick
   **4.5 h** (3 cycles) on the "Sleep up to" row and leave the deadline switch off.
6. Tap **Start night**. A **"This is a simulated night"** dialog appears - this confirmation exists so a
   simulated night can never start by accident at real bedtime. Tap **Start simulated night**.
7. You land on the **Night screen**: amber banner, a gear icon top-right (only in a debug build) that takes
   you back to the Debug screen without leaving the running night.
8. Tap that gear icon, then turn the **Asleep** toggle on. The **Simulated timeline** list grows a `LIGHT`
   entry right there. Tap back - the Night screen now shows a real onset, an amber **"Morning alarm"** label
   with the engine's own reason underneath it, and the alarm itself 4.5 h away in virtual time, which at 60x
   is a few real minutes out.

   **Branch: reaching the already-rang header.** Steps 9-12 below toggle Asleep off and back on repeatedly,
   which burns the night's budget into nap territory before the morning alarm ever reaches its own 4.5 h
   target - a nap ring is exempt from the "already rang" check that a real morning-alarm ring is not, so that
   path can never show what this branch shows. To see it, run this instead of step 9, on its own pass: leave
   **Asleep on** and do not touch it again, and wait about 4.5 real minutes at 60x (the picked length, with no
   toggling to interrupt it) for the morning alarm to ring on its own. When it rings, tap **Stop**, not **I'm
   awake**, and return to the Night screen. Expected: the header reads **"Out-of-bed nudge at HH:mm"**, the
   time being 15 virtual minutes after the ring; the hero number shows the morning alarm's own time (the one
   that just rang, not a dash); the subtitle reads **"Already rang"**; and there is no reason line underneath.
   Then wait about 15 real seconds more for that nudge to ring on its own, tap **Stop** again, and return: the
   header reads **"Out-of-bed nudge at HH:mm"** once more, another 15 virtual minutes on. That is L1 (decided
   2026-09-21): the nudge repeats until the night ends, because tapping **Stop** only silences the ring and
   only **I'm awake** means you actually got up. It will keep ringing every 15 virtual minutes from here, with
   no cap, so end the night with **I'm awake** or **Stop night** when you are done looking at this step - the
   header falls back to **"No alarm armed"** only once the night is over. One caption is not reachable this way: the deadline caption this
   same already-rang state can also show (see must-fix 2 of the second review round) needs the deadline switch
   on, but turning it on changes the post-firing plan to DEADLINE_ONLY - armed at the deadline itself, which
   never reaches "already rang" at all. Reaching both the already-rang header and the deadline caption
   together needs the band to under-count a cycle, which this synthetic simulator never does, so keep the
   deadline switch off for this branch and do not expect that caption. Once done, start a fresh walkthrough
   from step 6 to continue with step 9 below.
9. Wait about a minute of real time (roughly an hour of virtual sleep at 60x - `resolveEngineConfig` ignores
   the debug options, so the cycle length stays the real 90 minutes and the owed-cycle count rounds to
   nearest; a round needs a full virtual hour before the subtitle visibly moves, so "wait a few seconds"
   shows nothing at all), reopen Debug, turn **Asleep** off, wait about a minute again, turn it back on. The
   picked length is the whole night's budget, so every stretch you "sleep" is subtracted from it: the plan
   does not start a fresh count from the new onset, it counts only what is still owed. Watch the Night
   screen's "of sleep" subtitle count down a cycle each round (there is no separate list of candidate wake
   times any more - the owner asked for it removed as not worth reading at 3am), and the alarm keep landing
   4.5 h after you first fell asleep however often you wake.
10. Keep repeating off / on (about a minute of real time each way) until the "of sleep" subtitle is down to
    its last whole cycle and the alarm is only moments out. The next time you turn Asleep back on, less than
    half a cycle is left of the budget,
    nothing whole is owed, and the Night screen switches to the **nap** card: a short 20-minute nap from that
    onset (rule 7), with the mode label now reading **"Nap alarm"**.
    This is the way to reach the nap card on a night with no deadline - using up the total is the only thing
    that ends such a night, so waking near the previous alarm no longer produces a nap by itself. (With the
    deadline switch on instead, a nap also appears as soon as less than one cycle fits before the deadline.)
    This nap already counts toward the whole night's two-nap cap even though the main wake alarm has never
    rung - the cap no longer waits for that. Keep the app open and do not press anything else: the nap alarm
    actually reaches its own time and you can watch it fire.
11. When that alarm rings, you land on the ring screen with two buttons - **Stop** (silences it, the night
    keeps running) and **I'm awake** (silences it and ends the night right there, landing on the morning
    report). Tap **Stop**, then 15 virtual minutes later (about 15 real seconds at 60x) a second alarm rings on
    its own: the out-of-bed nudge, worded "Time to get up" - the same two buttons, same either-way behaviour.
    Tap **Stop** there too - and note that since L1 that nudge has already armed the NEXT one, 15 virtual
    minutes out, so do the next part promptly or expect it to ring again while you are in Debug. Now reopen
    Debug and turn **Asleep** on again: this is the second nap this night,
    and it spends the two-nap cap. If instead you turn Asleep back on before a pending nudge fires, watch it
    for the nudge to disappear rather than ring - a nap armed after an alarm cancels that alarm's own still-
    pending nudge (the new nap still arms a fresh nudge of its own, 15 minutes after it rings in turn), and
    that is also how a repeating chain is meant to stop mid-night when you genuinely fall back asleep.
12. Let the second nap alarm ring, tap **Stop**, let its own nudge ring and tap **Stop** again. Now reopen
    Debug and turn **Asleep** on a third time: with the cap already spent and no deadline left to fall back
    on, the night finishes immediately on its own - no third nap, no fixed wait - and the screen shows
    **Night finished** with an **End night** button, without you having to press "I'm awake" to get there.
    L2 detail worth seeing here (decided 2026-09-21, superseding what this step used to say): the nudge chain
    does NOT stop at this point either. Before L2 the reasoning below was true in the OTHER direction -
    reaching **Night finished** cleared the night's own state immediately, and the receiver needs that state
    to arm the next nudge, so whichever nudge was already armed would ring one last time and the chain would
    stop. That turned out to be an accident of structure rather than the owner's intent (it also meant a
    deadline night's chain stopped one ring after the deadline, which he had not asked for either), so
    `finishNightIfNeeded` now defers its bookkeeping - state, tick alarm, tracking notification, all of it -
    for as long as a nudge is still pending. Wait 15 more virtual minutes right here on the **Night finished**
    screen and watch the same nudge ring again; tap **Stop** and it arms yet another one. Only **End night**
    (or "I'm awake" on a ring screen, had one still been up) actually silences it. See the L2 record in
    `docs/decisions.md` for the owner's reasoning and what he accepted in exchange (the notification and tick
    alarm staying up past the point the night would otherwise have closed).
13. Tap **End night** to see the morning report, built entirely from what you just simulated. Ending the
    night also resets every Debug switch and any active clock warp - the same happens on its own if the app
    sits unopened for 2 h after they were last changed. L2 correction: that auto-reset only fires once no
    night state is left (`DebugScreenController.resetIfIdle` skips outright while any exists), so it will NOT
    fire on its own for a **Night finished** screen left sitting the way step 12 describes - the deferred
    night's state is still there. **End night** is what actually clears it, which is exactly what this step
    has you do; a desk test only leaks into a real bedtime if you walk away from **Night finished** without
    ending it.
14. Open **Logs**: the night you just ran carries a **simulated** tag, and its file is named
    `night-sim-<yyyyMMdd-HHmm>.jsonl` (a real night is always `night-<yyyyMMdd-HHmm>.jsonl`, so the two can
    never be confused). Tap the row to reopen that night's summary - the same report you just saw - or use its
    three-dot button to **Share** it, and see every `data` event tagged `source=simulated`, every
    `phone_alarm_fired` and `out_of_bed_alarm_fired` entry the simulated night actually rang, and `night_end`
    recording whether it ended by your own "I'm awake" tap or on its own. The same menu's **Delete** removes a
    night for good after one confirmation, which is how to clear desk-test logs out.
15. Separately, any time no night is active (the button is disabled, with a reason shown underneath, while a
    night is running - it must never be able to replace the real night's own alarm): from the Debug screen,
    tap **Ring phone alarm** to check the full-screen alarm activity, the sound and the notification's Stop
    button in daylight, 5 real seconds later regardless of any simulated clock speed. This writes to
    `setup.jsonl`, not a night log - it is not a night.

## Known limits

- Reboot during the night (e.g. a system update): the phone alarm is restored only once the phone is unlocked once after the reboot (`BootReceiver` needs credential-protected storage). Direct Boot support, which would restore it before first unlock, is deferred - not fixed in this batch.
- Without a deadline, if the band never reports sleep (e.g. it is taken off), the wake time keeps moving forward all night and nothing rings until the band eventually reports sleep or the owner ends the night by hand.
- The night ending on its own (the deadline passing, or the two-nap cap spending itself with no deadline left) closes the night's own bookkeeping - the persisted state, the tick alarm, the tracking notification - only once there is nothing left pending; a still-ringing alarm is left alone either way. L2 (decided 2026-09-21): if an out-of-bed nudge is pending at that exact moment, closing the bookkeeping is deferred, not merely skipped for the nudge alone - state, the tick alarm and the notification all stay exactly as they are, and the chain keeps repeating, until your own "I'm awake" or "Stop night" silences it. The night reaching its own end does not.
- A simulated night dies if the app process is killed - the fast in-process tick path a high simulation speed relies on has no reboot or process-death recovery of its own, unlike a real night's `AlarmManager` path. Debug-only, never a real night's concern.
