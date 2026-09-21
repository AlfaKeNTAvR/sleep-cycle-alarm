# Autonomous decisions, 2026-09-21 19:14 (N1: one line for the whole night)

Task: rebuild the night screen around the mockup the owner picked (label, big time, countdown), and make
Stop the ring screen's only action. Full reasoning is in `docs/decisions.md` under **N1**; this file carries
only the calls made without asking, and the questions I would otherwise have put to him.

## Decisions made without asking

1. **Collapsed the three live-night content types into one** rather than trimming each in place. Once the
   extra lines were gone the three had identical fields and identical rendering. The alternative, keeping
   three types with dead fields, is the "add code to avoid touching code" shape CLAUDE.md rules out.
2. **Kept the shared alarm names** ("Morning alarm", "Nap alarm", "Out-of-bed nudge") rather than the
   mockup's "Nap until". W18 made the screen, the ring and the notification use one set of names on purpose,
   so the owner is never guessing at 03:40 why the phone is beeping. See question 1 below.
3. **Countdown wording is the app's existing duration format**: "in 20 min", "in 5 h 38". The mockup read
   "in 5 h 38 min". One duration format in the app beat matching the mockup exactly. See question 2.
4. **H8's already-rang case shows the dash plus "Morning alarm rang at 07:00"** instead of putting the rung
   time in the hero slot as it used to. Under N1 the hero answers one question - when does the next alarm
   ring - so a past time there reads as one still coming. See question 4.
5. **Left `awakeConfirmedAt` in place although nothing writes it any more.** It is persisted-state schema
   with its own round-trip and tolerant-decode tests; deleting it changes the on-disk format for no
   behavioural gain. See question 3.
6. **Did not touch the morning report, the FINISHED screen, or the before-bed screen.** The owner's
   complaint was about a live night, and the morning report is where he said the sleep figure belongs.

## Open questions for the owner

1. "Nap alarm" or "Nap until" on the label? I chose "Nap alarm" for consistency with the ring screen and
   the notification. One string to change if he wants the mockup's wording.
2. "in 5 h 38" or "in 5 h 38 min" for a countdown over an hour? I chose the former, matching the morning
   report's own duration wording.
3. Delete the now-dead `awakeConfirmedAt` plumbing (state field, `endNight` parameter,
   `applyAwakeConfirmation`, their tests) in a follow-up, or leave it?
4. When the morning alarm has already rung and nothing is armed, is "--:--" over "Morning alarm rang at
   07:00" what he wants to see? It is a rare state (needs the band to under-count a cycle), and it is also
   the one that means something has gone wrong, so it is worth him confirming the wording.
5. The countdown is computed when the screen state is built, so it refreshes on the ordinary tick cadence
   (5 or 15 minutes) rather than ticking second by second. That matches how the rest of the screen updates
   and costs nothing; a live-ticking countdown would need its own timer. Worth doing?

## Not verified on the phone

The Pixel was unplugged for this whole run, so this is tests-and-build only: 598 tests, 0 failures, lint
clean, `assembleDebug` clean, on a full forced rebuild (`--rerun-tasks`, 57 tasks executed). Not installed,
and the layout has not been looked at on a real screen.
