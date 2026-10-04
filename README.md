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

The **Debug** section in Settings, just above More (debug builds only), has three controls:

- **Simulated band data** (switch): sleep segments come from the **Asleep** switch on the Night screen
  instead of a real Gadgetbridge sync. Turn this on first - the speed chips are locked until it is, since a
  warped clock against real band data makes no sense (the band's timestamps would always read hours stale
  against a virtual "now"). Locked while a night is running.
- **Simulated start** (a time, default 23:00): switching Simulated band data on starts the simulated clock at
  the next occurrence of this time (today's if it is still ahead, else tomorrow's), at 1x, so a desk test in
  the afternoon still runs a night that starts at bedtime. The time is kept until you change it; a new time
  applies at the next switch-on, never to a clock already running.
- **Ring phone alarm**: a standalone test alarm that rings straight away, always real time regardless of the
  simulated clock, for checking the ring screen in daylight with no night running.

Once simulated band data is on, the Night screen's amber banner becomes the simulation controls: the
simulated clock, the speed chips and an **Asleep** chip:

- **1x / 60x / Auto**: the app's own idea of "now" runs this many times faster than the real clock.
  Everything the app computes - the plan, the log, the alarms - is measured against that virtual time; only
  the instant handed to Android's alarm system is converted back to a real one, so alarms genuinely ring,
  just sooner in real time. Changing speed never itself moves the clock, only how fast it runs from then on.
- **Auto** runs at 3600x, then 600x from 30 simulated minutes before the next alarm (the planned one, or a pending
  nudge or nap), then 60x from 10 minutes before. While the alarm is only projected from "now" (no sleep seen
  yet, or awake mid-night) it never comes closer, so Auto slows down only for a fixed alarm ahead (the
  deadline, or the morning alarm the plan keeps while you are awake mid-night), and with neither ahead it waits
  at 60x. The chip shows the speed it is running at.
- **Back to 1x**: switching **Asleep** either way, pressing **I'm up**, and any alarm starting to ring all
  put the clock back to 1x and leave Auto, so whatever happens next is watched at real speed. Pick 60x or
  Auto again to skip ahead.
- **End night** puts the clock back on real time, turns the switches off and empties the simulated sleep.
  It leaves the Simulated start time as it is.

**The banner**: once simulated band data is on, an amber-outlined block reads **SIMULATED** on the left
and the app's own current virtual time on the right, on Before bed (without controls) and on the Night
screen (with them). The tracking notification carries the same words as text.

**Order of operations for a full simulated night**: turn on Simulated band data, start the night, then drive
it entirely from the Night screen's banner: Asleep on, then Auto (or 60x, a good balance to follow the plan
as it moves). Keep the app open throughout: whenever the simulated clock differs from the real one (any speed
above 1x, or a Simulated start other than right now) every sync tick runs from a coroutine inside the app
process rather than Android's alarm system, since the alarm system's own Doze throttling (about one firing
per 9 real minutes while the phone is idle) would otherwise swallow a tick that is only seconds away in real
time - which means a simulated night dies if the app process is killed, unlike a real one.

### Walkthrough

Since 2026-10-02 the speed chips and the Asleep chip live only in the Night screen's top banner, and
switching Asleep drops the clock to 1x - so wherever a step below says to turn Asleep on or off, do it on the
Night screen, then tap **60x** again to carry on at that speed.

1. Install the **debug** build (`app-debug.apk`, per the Build section above - not the release one).
2. Open the app, go to **Settings** (gear icon) and scroll down to the **Debug** section.
3. Turn on **Simulated band data**. With it on, the app never needs Gadgetbridge or the band configured at
   all for this walkthrough - the checklist below no longer needs the band steps.
4. Open **Setup** from Settings: only the phone-side items (Notifications, Full-screen alarms, Battery
   optimisation) should still be red. Grant any that are.
5. Tap **Done** to reach **Before bed**. You should see the amber-outlined block reading **SIMULATED** with
   the simulated time on the right (the Simulated start, 23:00 unless you changed it, moving at 1x) and
   **Start night** enabled with no band connected. Pick **4.5 h** (3 cycles) on the "Sleep up to" row and
   leave the deadline switch off.
6. Tap **Start night**. A **"This is a simulated night"** dialog appears - this confirmation exists so a
   simulated night can never start by accident at real bedtime. Tap **Start simulated night**.
7. You land on the **Night screen**, with the simulation controls in the amber banner at the top.
8. Tap the **Asleep** chip in the banner, then tap **Auto** (or **60x**). The Night screen shows **"Morning
   alarm"**, the alarm's own time as the big number, and how long until it: 4.5 h after you fell asleep. Since
   N1 that is the whole screen, in every mode and whether you are asleep or awake - which alarm is coming, when
   it rings, how long until then. The only button is **I'm up** (owner rule of 2026-10-02, one choice at a
   time): there is no End night before the morning alarm. To abandon a night early, press I'm up, then End
   night.
9. Optional, waking mid-night: turn **Asleep** off (the clock drops to 1x; tap Auto or 60x again). While you
   are awake the alarm is projected from "now" plus 15 minutes to fall asleep, so it slides later as you stay
   awake. Turn Asleep back on after a while: the alarm moves to a whole number of cycles from the new onset,
   enough to cover what is still owed of the picked total (rounded up to a whole cycle, forgiving 10 minutes),
   so the night's total is never short of what you picked.
10. Let the morning alarm ring (Auto slows to 600x and then 60x for the approach; the ring drops the clock to
    1x). You land on the ring screen with one button, **Stop**, which silences it and leaves the night running.
    (N1 removed the second one, **I'm awake**, which used to end the night on the spot: a mis-tap half asleep
    cancelled every alarm left in the night. Ending a night is the Night screen's job now.) Tap **Stop** and
    return to the Night screen. Expected: **"Out-of-bed nudge"**, its time 10 virtual minutes after the Stop
    (the Settings default; it is 10 min after the ring's own 9 min auto-stop if nobody touches it), a countdown,
    and two buttons: **Nap for 20 min** and **End night**.
11. Tap Auto or 60x again and let the nudge ring, worded "Time to get up" - the same screen, the same one
    button. Tap **Stop**: another nudge is armed 10 virtual minutes on. That is L1 (decided 2026-09-21): the
    nudge repeats, with no cap, until you end the night, because **Stop** only silences the ring. Since P3
    (2026-09-30) nothing after the morning alarm watches the band: turning **Asleep** on now arms no nap, and
    a nap that would ring at or after the morning alarm's time counts as the morning alarm itself. Napping is
    your own choice, made with the button.
12. Tap **Nap for 20 min**: the nudge is swapped for **"Nap alarm"** 20 virtual minutes from the press, and the
    only button is **I'm up**. Let the nap ring and tap **Stop**: the nudge and the Nap button come back, as
    many times as you like. Pressing **I'm up** during the nap instead replaces the nap with a nudge 10 minutes
    out.
    With a deadline set, the night can also finish on its own: the deadline passing once the morning alarm
    has rung (or a deadline more than an hour behind with nothing rung) is **Night finished** with an **End
    night** button. L2 (decided 2026-09-21): while a nudge is still pending at that moment, the night's
    bookkeeping (state, tick alarm, tracking notification) is deferred and the chain keeps repeating, and N2
    shows that pending nudge rather than "Night finished" over a still-armed alarm. See the L2 record in
    `docs/decisions.md` for the owner's reasoning.
13. Tap **End night** and confirm to see the morning report, built entirely from what you just simulated.
    Ending the night also resets every Debug switch and any active clock warp - the same happens on its own if
    the app sits unopened for 2 h after they were last changed. L2 correction: that auto-reset only fires once
    no night state is left (`DebugScreenController.resetIfIdle` skips outright while any exists), so it will
    NOT fire on its own for a night still running or a finished night whose nudge chain is still pending.
    Ending the night is what actually clears it, which is exactly what this step has you do; a desk test only
    leaks into a real bedtime if you walk away from a night without ending it.
14. Open **Logs**: the night you just ran carries a **simulated** tag, and its file is named
    `night-sim-<yyyyMMdd-HHmm>.jsonl` (a real night is always `night-<yyyyMMdd-HHmm>.jsonl`, so the two can
    never be confused). Tap the row to reopen that night's summary - the same report you just saw - or use its
    three-dot button to **Share** it, and see every `data` event tagged `source=simulated`, every
    `phone_alarm_fired` and `out_of_bed_alarm_fired` entry the simulated night actually rang, and `night_end`
    recording how it ended. The same menu's **Delete** removes a
    night for good after one confirmation, which is how to clear desk-test logs out.
15. Separately, any time no night is active (the button is disabled, with a reason shown underneath, while a
    night is running - it must never be able to replace the real night's own alarm): from Settings' Debug section,
    tap **Ring phone alarm** to check the full-screen alarm activity, the sound and the notification's Stop
    button in daylight, straight away regardless of any simulated clock speed. This writes to
    `setup.jsonl`, not a night log - it is not a night.

## Test on the emulator (no phone)

A headless Android emulator (AVD `sca35`, API 35) runs the debug build on the PC, and the `testplayer` module is
a stand-in for an audiobook app: a looping tone with a media session, so the bedtime fade and the pause on sleep
behave as on a phone. `scripts/emulator.sh` drives both; its header lists every command and the one-time setup.

```bash
source scripts/env.sh
./gradlew :app:assembleDebug :testplayer:assembleDebug
scripts/emulator.sh start && scripts/emulator.sh install && scripts/emulator.sh open
scripts/emulator.sh tap "Start night"     # after Setup and the Debug switch, as in the walkthrough
scripts/emulator.sh play                  # media starts; the fade begins about 1 s later
scripts/emulator.sh log                   # media_fade_start, media_fade_step, ...
```

It does not cover Bluetooth headphones or a real band.

Whole-night scenarios that need no emulator run on Robolectric with the ordinary tests
(`app/src/test/kotlin/com/nikita/sleepcycle/scenario/`): the real night code against a simulated Android.

## Known limits

- Reboot during the night (e.g. a system update): the phone alarm is restored only once the phone is unlocked once after the reboot (`BootReceiver` needs credential-protected storage). Direct Boot support, which would restore it before first unlock, is deferred - not fixed in this batch.
- Without a deadline, if the band never reports sleep (e.g. it is taken off), the wake time keeps moving forward all night and nothing rings until the band eventually reports sleep or the owner ends the night by hand.
- The night ending on its own (the deadline passing, or the two-nap cap spending itself with no deadline left) closes the night's own bookkeeping - the persisted state, the tick alarm, the tracking notification - only once there is nothing left pending; a still-ringing alarm is left alone either way. L2 (decided 2026-09-21): if an out-of-bed nudge is pending at that exact moment, closing the bookkeeping is deferred, not merely skipped for the nudge alone - state, the tick alarm and the notification all stay exactly as they are, and the chain keeps repeating, until your own "End night" on the Night screen silences it. The night reaching its own end does not.
- Playing media (audiobook, music, video) is paused once the band says you fell asleep, but only as soon as the band itself confirms sleep, typically 5 to 24 minutes late. The app checks every 5 minutes for the first hour of the night to keep its own share of that delay small. An app that ignores media-button presses keeps playing; the night log's `media_pause_on_sleep` line says which happened.
- Bedtime fade (Settings, Bedtime audio): about 1 s after media starts playing following Start night, the media volume is set to the starting volume (default 25%, raised to it if lower), held 10 minutes, then lowered one step every 5 minutes to the ending volume (default 5%). Until the morning alarm the fade owns the volume: a volume you turn up is pulled back to its schedule on the next check, one you turn down stays. Falling asleep pauses the media and parks the volume at the starting volume (or lower, if the fade already got there); every awakening with media playing starts a fresh fade at exactly the starting volume. Your own volume comes back once the morning alarm has rung, at End night, or when the night finishes on its own.
- Doze: on a real night every sync, and so every fade step, is woken through Android's alarm system (`setExactAndAllowWhileIdle`), which Doze throttles to about one firing every 9 minutes while the phone is idle. A 5 minute fade step, or the 5 minute syncs of the first hour, can therefore land a few minutes late. The alarms themselves (`setAlarmClock`) are not throttled.
- A simulated night dies if the app process is killed - the fast in-process tick path a high simulation speed relies on has no reboot or process-death recovery of its own, unlike a real night's `AlarmManager` path. Debug-only, never a real night's concern.
