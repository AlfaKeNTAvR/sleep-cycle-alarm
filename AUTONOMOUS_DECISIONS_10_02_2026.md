# Autonomous decisions, 2026-10-02: the "I'm up" button

Owner ask: split "I'm up" from "End night", so a nap is possible without waiting for the morning alarm to ring
(2026-10-02: awake at 08:00, the 08:19 alarm had to ring first). Owner picked: autonomous mode, tests at the
pure decision and the Night screen state, and a confirmation dialog on "I'm up".

## Decisions

1. **"I'm up" stands in for the morning alarm being stopped now.** It records the press as the morning alarm's
   firing (so the engine arms nothing else for the night), cancels the pending phone alarm, and arms the
   out-of-bed nudge 10 min out. The Nap button follows from that nudge, with no new code path for naps.
2. **Shown only before the morning alarm has rung**, on any live (not finished) night. After the alarm, Nap and
   End night already cover it.
3. **It replaces whatever follow-up was pending**, including a nudge left over from an earlier pre-wake nap
   alarm: a fresh cycle, like any alarm ringing.
4. **"I'm up, end night" now reads "End night".** The end button keeps "Stop night" before the morning alarm, as
   before. The `IM_UP` enum name was left as it is (only its label changed), to keep the diff small.
5. **Runs under the night lock**, so a tick already in flight cannot re-arm the alarm just cancelled. An
   immediate tick follows, so the screen drops the cancelled alarm at once.
6. **The night summary counts "in bed after wake alarm" from the I'm up press**, since that press is recorded as
   the wake alarm. Judged correct: it is the moment he said he was up.
7. **New night-log events:** `im_up_pressed` (with the cancelled alarm time) and `im_up_refused` (pressed after
   the alarm already rang, e.g. a stale screen).

## Open questions

- Not yet tested on the phone: the Pixel was disconnected from adb during this run.
- The old dialog text for ending after the alarm ("This stops the alarm and shows tonight's report") is unchanged.
