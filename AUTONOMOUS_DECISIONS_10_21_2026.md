# Autonomous decisions, 2026-10-21

Batch of four Linear tickets built in parallel by subagents, merged on `nikita/feat/batch-2026-10-21`.

## PER-124: 15:00 notification opens that night's log
- Good saves and opens nothing. Okay or Bad saves, then opens that night's Past night with the afternoon symptoms dialog on top. A tap on the notification body opens that night's Past night with no dialog and records nothing.
- Back goes Past night, then Logs, then Before bed (or Night if a night is running).
- On a cold start the app finishes picking its first screen before switching to Past night. Tests pass with or without that wait under Robolectric, so it is a safeguard, not proven by a test.

Open questions:
- If an unfinished morning report is showing when the notification is tapped, Back ends on Before bed, not the report. Unlikely at 15:00.

## PER-125: Kept napping and Heavy head
- Morning dialog: 5 tiles, 3 on top (Still sleepy, Headache, Woke before alarm) and 2 below (Slow to fall asleep, Kept napping). Afternoon: 4 tiles, two by two (Sleepy in afternoon, Low energy / Hard to focus, Heavy head).
- Stored as `kept_napping` and `heavy_head`; old names unchanged.
- Icons drawn by the agent, not owner-approved: Kept napping is a go-round arrow around a "z"; Heavy head is the Headache head with a downward arrow instead of the bolt.

Open questions:
- Do the two icons read well on the phone? Is Heavy head too close to Headache?

## PER-126: a night under 1 h discards itself
- Under 1 h from Start night (simulated clock in a simulation), End night and I'm up both open "Discard this night?" with Cancel / Discard.
- Discard cancels every alarm, nudge, nap and tick, stops a ring, restores the media volume, shows no report or rating, deletes the log, and goes back to Before bed. The previous night's 15:00 question is re-armed if still due.
- A simulated night keeps its log, closed with a `night_discarded` event instead of `night_end`, so it is never asked about.

Open questions:
- A kept simulation log has no summary, so its Past night says no summary was recorded.
- Show a short "Night discarded" message on Before bed?
- If an alarm is ringing at the moment of a discard, its "alarm stopped" log line can land in the previous night's log.

## PER-127: nudge time right after Stop
- When a ring ends (notification Stop, ring window Stop, or self-stop), the existing post-firing refresh now also publishes the pending follow-up record, so the Night screen updates at once. The 1 min ticker stays as a backstop.

Open questions:
- Should Nap, I'm up and the night ending publish the same way? Left as they were to keep the change small.

## Merge
- One docs conflict in `docs/app-spec.md` (the SleepRating and SleepRatingAsk bullets), resolved by keeping both sides' updates.
- Full suite green after all four merges: 694 app tests plus engine tests, 0 failures, debug build OK.
