# Autonomous decisions, 2026-10-02 18:34: Debug screen rework and Auto speed

Branch `nikita/feat/debug-auto-speed`, from main.

## TDD seams (agreed)
Auto speed rule, waking sets 1x, End night cleanup, plus: the tick that lands on the slow-down, the chip choice and which chip shows selected, the Auto chip's running speed. End night cleanup is DataStore only, so it is checked on the phone, not unit tested.

## Decisions
- **Auto slows down for the nearest alarm ahead**: the planned alarm or a pending nudge or own nap, whichever is sooner. An alarm already behind the clock is ignored. No alarm at all: 600x.
- **Stages** (owner changes after the first install): 3600x far off, 600x from 30 simulated min before the alarm, 60x from 10 (both boundaries inclusive). A tick is booked on each boundary.
- **On-screen clock at 3600x moves in half-hour steps**: the UI refresh floor (500 ms) is kept, so the 3600x stage is exempt from the old "no jump over 5 simulated min" rule. Lowering the floor would rebuild the screen 10 times a second.
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

## Open questions
- Auto with no night running (Before bed) has no alarm to slow down for, so it would sit at 600x. The chips only show on the Night screen, so this cannot happen today.
