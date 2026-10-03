# Autonomous decisions, 2026-10-02 18:34: Debug screen rework and Auto speed

Branch `nikita/feat/debug-auto-speed`, from main.

## TDD seams (agreed)
Auto speed rule, waking sets 1x, End night cleanup, plus: the tick that lands on the slow-down, the chip choice and which chip shows selected, the Auto chip's running speed. End night cleanup is DataStore only, so it is checked on the phone, not unit tested.

## Decisions
- **Auto slows down for the nearest alarm ahead**: the planned alarm or a pending nudge or own nap, whichever is sooner. An alarm already behind the clock is ignored. No alarm at all: 600x.
- **Stages** (owner changes after the first install): 3600x far off, 600x from 30 simulated min before the alarm, 60x from 10 (both boundaries inclusive). A tick is booked on each boundary.
- **Screen redraw up to 10 times a second** (owner request): the UI refresh floor went from 500 ms to 100 ms, so the clock moves in 6-minute steps at 3600x and 1-minute steps at 600x. Only a simulated clock redraws that often.
- **A tick is booked on the slow-down instant**, because the ordinary sync gap (5 to 15 simulated min) could otherwise skip the whole 10 min approach.
- **Auto is applied inside the night tick**, after the plan, before any alarm is armed, so the alarms and the next tick are armed under the new speed in the same tick.
- **Choosing Auto** starts at the speed Auto wants right now (from the last plan and the pending nudge or nap).
- **Ring drops to 1x after the ring starts**: the alarm never waits on a store write. Its auto-stop, first timed under the fast clock, is re-timed at 1x once the drop lands. On a 600x ring the auto-stop would otherwise be under 1 real second.
- **Asleep switch**: the drop to 1x happens before the event is stamped, so the debounce re-tick is booked at 1x.
- **Turning simulated band data off** also turns Auto off.
- **End night** now also empties the simulated sleep timeline (the Clear button is gone, nothing else clears it). Same for the 2 h idle reset, which shares the code.
- **Removed**: `computeJumpWarp`, `isJumpToTimeAllowed`, `isResetToRealTimeAllowed`, their 4 tests, 15 unused strings, and the timeline and jump fields of the debug UI state.
- **SIMULATION_SPEEDS** now lists every speed the clock can run at (1, 10, 60, 600), not the chips.
- **Shared Settings look**: the section, switch row and button row moved from SettingsScreen into `ui/components/SettingsSection.kt`, used by Settings, Debug and the Night screen's Simulation card.
- **Debug "Ring" is a small bordered button** on the right of the row, the same one Settings uses for "Ask at".

- **Debug screen removed** (owner asked for a Debug section in Settings): the Night screen's Debug shortcut (sliders icon) went with it, since the controls it led to are in the banner and inert mid-night. The setup wizard's Debug button now opens Settings, so back from there goes to Before bed, not the wizard.

- **Fade only lowers** (phone test, 20:50 night): parking now keeps a volume the fade already took below the starting volume (it lifted 3 back to 10), and a tick leaves a volume turned down below the schedule alone (it lifted 4 to 5). Tested at the pure seams `fadeParkStep` and `fadeTickAction`, the same seam as the earlier fade tests.

- **Fade reacts at once** (phone test, 21:10 night): NightService now watches media starting or stopping and media volume changes, and 1 s after the last one runs the fade's share of a tick (`runMediaFadeCheck`): no band sync, no plan, no pause, against the sleep state the last tick saw. The 1 s wait lets a held volume key finish instead of fighting it. The watcher itself has no unit test (Android callbacks, no Robolectric or coroutines-test in the project); the decisions it runs are the tested pure fade functions.
- **Parked volume is capped too**: while parked (asleep, or awake before media plays), a volume turned above the starting volume is pulled back to it; one turned below stays.
- **Before bed banner** (owner request): now the same amber-outlined block as the Night screen, "SIMULATED" left, clock right, no controls. Supersedes W7's no-clock banner. The clock re-reads every 30 s there, so it can lag the real minute by up to 30 s.

- **Awake re-tick survives other ticks** (phone test, 21:45 night): coming back to the app after starting media ran an immediate tick that replaced the re-tick booked for when the awake mark passes the 1 min floor, so the awakening (and the wake fade) was never seen. Every tick now books its next one no later than that instant (`simulatedAwakeSettlesAt`, `earliestTickAt`). Simulated nights only.

- **Phone-free testing** (owner approved): headless API 35 emulator (`scripts/emulator.sh`, AVD `sca35`) plus a `testplayer` module standing in for an audiobook app; verified on it: fade starts about 1 s after play, a raised volume is pulled back, the awake re-tick survives an extra sync, pause on sleep stops the player. Robolectric 4.16 (JUnit 4 via the vintage engine) runs whole-night scenarios with the ordinary tests; first one reproduces the lost awake re-tick (red without the fix, green with it).
- **emulator.sh targets the emulator through ANDROID_SERIAL**, not `adb -s`: the repo's publish-safety test forbids `-s` in tracked files, and dropping it outright would let the script tap a plugged-in phone.

- **Play while asleep** (emulator test 2026-10-03, owner decision): a fresh fade starts whatever the band says, at exactly the starting volume; once it has run its course (hold, then one step per 5 min to the ending volume) with the band still saying asleep, the media is paused, the volume parked and the fade re-armed. An app that ignores the pause is tried again on each tick. Each tick books the next one no later than the running fade's next step; the 1 s watcher runs a full tick after starting a fade so that booking happens at once.
- **Auto speed lock**: every speed change (chips, Auto in the tick, the drops to 1x) reads and writes under one lock. Not the night lock, because the tick that applies Auto already holds it.
- **Auto while waiting for sleep**: the alarm is projected from "now" until sleep is seen, so Auto ignores it and runs towards the deadline only; with no deadline it waits at 60x instead of 3600x.
- **A night finishing on its own** puts the fade's original volume back, like End night.
- **File writes use `Files.move` (replace, atomic)** instead of `File.renameTo` in the three temp-file writes: same atomic rename on Android, but `renameTo` cannot replace a file on Windows, so every night state save after the first failed under Robolectric.
- **Test audit**: removed 2 TickScheduling tests and 3 warp replays that could not fail (identity inputs); the tick chain test now checks spacing instead of a count fixed by construction. New Robolectric scenarios: a whole night's fade, play while asleep, a 1x tap racing Auto ticks.
- **Not tested**: the self-finishing night's restore (reaching FINISHED needs a long scripted night); the speed race test passes, but was not shown failing against the old code.
- **Simulated start** (owner approved, 2026-10-03): stored as its own key in the debug store, outside DebugOptions, so the night-end and idle resets never touch it. Switching simulated band data on writes the clock first, then the switch (W4 order). A start time exactly equal to now counts as today (no jump) rather than tomorrow. Changing the time while simulated data is on does not move the running clock; it applies on the next switch-on. The time row stays editable mid-night, since a new time does nothing until then.
- **Simulated night log time** (owner request, 2026-10-03): a debug night's log is named, listed, sorted and titled (Logs, Past night) by the REAL time Start night was tapped. Recorded once as NightState.realStartedAt (taken through the warp that made the simulated start, so the same moment); every log write uses it. Event times inside the log stay simulated. A state file saved before this decodes with the real start equal to the simulated one, so a night already in progress keeps writing to its existing log rather than splitting into two. Not derived from the warp snapshot in DebugOptions: that copy comes from the Debug store's mirror, which can lag the live clock, and Auto re-anchors the live clock mid-night, so one explicit record is the exact source.

## Open questions
- Auto with no night running (Before bed) has no alarm to slow down for, so it would sit at 600x. The chips only show on the Night screen, so this cannot happen today.
