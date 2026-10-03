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

## Open questions
- Auto with no night running (Before bed) has no alarm to slow down for, so it would sit at 600x. The chips only show on the Night screen, so this cannot happen today.
