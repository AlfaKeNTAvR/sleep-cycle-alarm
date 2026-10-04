# Autonomous decisions, 2026-10-02 16:51: Settings screen and sleep rating

Branch `nikita/feat/settings-and-sleep-rating`, stacked on `nikita/feat/pause-media-on-sleep`.

## TDD seams (agreed)
Settings limits and engine timing, which nights are rateable, both ratings kept, when the later question is asked, the fade start volume. Plus: Logs chips, Past night rating rows, the Nap button using the nap length. Not unit tested (checked on the phone): DataStore, the Settings/rating screens, the notification and its alarm.

## Decisions
- **Nap length range**: 10 to 30 min in 5 min steps. Not in the spec, only the default (20) was.
- **Nudge steps**: 5 min (5, 10, 15), owner request after the first install. **Volume steps**: 5% (10 to 50).
- **Phone back button** does what the screen's own back arrow does (owner request). Before bed and Night have no arrow, so back there still leaves the app.
- **Nudge and nap apply from the next Start night**, not to a night already running: a night freezes them at start, like debug options. Reason: the alarm receivers and boot restore read them from the night state, with no settings read on those paths.
- **Bedtime audio applies at once**: the fade reads its switch and start volume at Start night; the pause switch is read live on every tick. (Superseded 2026-10-03, owner decision: the fade's settings are read live too, see docs/app-spec.md.)
- **Pause off means no fade** (owner request after install): the fade exists to lead into the pause, so with "Pause media when asleep" off the fade rows are dimmed, the fade switch shows off, and no fade starts. Your fade choice is kept and comes back when the pause is switched on. A fade already running when the pause is switched off mid-night keeps stepping down and ends at End night.
- **Fade switched off**: a fade left over from a night that never ended is still finished at Start night, so that old volume comes back.
- **Rateable marker**: `night_start` gets `rateable=true`. A night started on the old build and ended on this one is not rateable (no morning card).
- **Storage**: ratings are extra lines in the night log itself (append-only, last one per moment wins). No new file. Changing a rating in Past night appends a line, so the history stays.
- **Later question timing**: at "Ask at" on the calendar day the night ended. Not asked if the night ended after that time (no asking about it the next afternoon), if the time already passed (phone was off), if already asked or answered, or if a new night started. Asked even when the morning rating was skipped, with different wording ("How do you feel about last night?", no body line).
- **Time base**: rating times and the ask time are real wall-clock time, not the debug simulated clock.
- **Simulated nights can be rated** too, so the feature can be tested at the desk.
- **Past night shows the rating card even when "Rate the night" is off** (only for rateable nights). The switch only hides the morning card and stops the later question.
- **Dimmed rows**: Starting volume is dimmed while the fade is off; Ask again later and Ask at while the rating is off; Ask at while Ask again later is off. Rows stay visible (option A has no nesting).
- **Morning card replaces "Night log saved for export."** when shown, as in the design. The note returns when the card is hidden.
- **Morning report scrolls** if a long night plus the rating card no longer fits; still centred when it fits.
- **Notification**: own channel "Sleep rating", default importance, launcher icon (same as the other notifications). Tapping its body opens the app.
- **Removed**: the unused `napLengthFor` helper; the F1 test that the nudge outlasts the ring auto-stop (the rule is gone).
- **Refactor**: the time picker dialog was pulled out of the deadline field so "Ask at" reuses it. Settings menu rows now sit in one card instead of one card each.

## Not verified
- **Nothing installed on the phone**: adb saw no device at 17:15. Needs: Settings screen look and saving, the nudge/nap values on a real night, the morning faces, chips, Past night re-rate, and the 15:00 notification and its buttons.
- The design artifact's planned boards are not moved to "Current app" yet: waiting for your phone check.

## Open questions
- Should a nudge/nap change in Settings also apply to a night already running?
- Past night card when "Rate the night" is off: keep showing it, or hide it?
- Later question for a night that ended after the ask time (late wake-up): skip (current) or ask the next day?
