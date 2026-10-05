# Autonomous decisions, 2026-10-04: why Okay or Bad (rating symptoms)

Owner answers before the run (2026-10-04):
- Autonomous, TDD, one subagent. Design: the canvas boards "Proposed: morning, why Okay or Bad" and "Proposed: 15:00, why Okay or Bad" (approved: "Looks good").
- Morning items (after Okay or Bad on the morning report): Still sleepy, Woke before alarm, Slow to fall asleep, Headache. (Owner, 2026-10-04: Groggy on waking and Heavy, overslept removed after the design was approved.)
- 15:00 items (Okay or Bad on the notification opens the app with the dialog): Sleepy in afternoon, Low energy, Hard to focus.
- Each item an icon with its name under it; tick any number; Save stores them with that rating, Skip stores none; Good never asks.
- Night logs > past night: under each rating, a row of the ticked icons with names; tapping it opens the same dialog to edit any time.
- Changing a rating to Good clears that moment's symptoms; back to Okay or Bad asks again.

## Decisions
- **Storage.** Symptoms are `sleep_symptoms` lines in the night's own log: `{"type":"sleep_symptoms","fields":{"moment":"after_end_night"|"later","symptoms":"still_sleepy,headache"}}`. Each line holds the whole ticked set for one moment (empty string = none), so the last line per moment wins, the same as `sleep_rating`. They are read into `RecordedRating.symptoms`, which defaults to empty, so logs from before this feature still read. An unknown name is skipped and the rest are kept.
- **Good clears, done on read.** A Good rating line clears that moment's symptoms because the log is read in order. No extra "clear" line is written, so a crash between two appends can't leave stale symptoms. Going back to Okay after Good starts from none: the old ticks don't come back.
- **When the dialog asks.** It asks only when the rating moves to Okay or Bad from no rating or from Good. Okay to Bad (or Bad to Okay) keeps the ticks and doesn't ask again. The line under the rating edits them. This rule is the same on the morning report and on Past night.
- **Skip writes nothing.** Right after a rating nothing is ticked yet, so "Skip stores none" holds. When editing later, Skip (or tapping outside the dialog, or Back) keeps the existing ticks. To clear them, untick everything and press Save.
- **Morning card shows the ticks too.** The design only showed the line under Past night's rows. I also put it under the morning faces, with "Tick what felt off" when nothing is ticked. Without it, there is no way to change the ticks before leaving the morning report.
- **Empty but editable line.** An Okay or Bad rating with nothing ticked shows a muted "Tick what felt off" line, so the dialog can still be reached. Good and unrated rows show nothing.
- **15:00 Okay/Bad opens MainActivity directly.** These buttons are activity PendingIntents, not a broadcast to the receiver. Since Android 12 a receiver started from a notification may not open an activity (the "trampoline" rule). The rating is recorded inside the app, by `recordLaterRatingFromNotification`, which also cancels the notification. The intent uses CLEAR_TOP + SINGLE_TOP, so an open app gets it through onNewIntent. A rotation, or a reopen from Recents (which redelivers the intent), does not record the rating or open the dialog a second time. Good is unchanged: a broadcast, no app opened.
- **The dialog sits on top of every screen.** It is hosted once in MainActivity. The 15:00 dialog therefore appears over whichever screen the app opens on (usually Before bed), and the app doesn't navigate to Past night.
- **Tile layout after the owner's cut to 4 morning items.** Rows hold at most 3 tiles, spread evenly, so the morning's 4 items show as 2 by 2 and the afternoon's 3 as one row of 3. Three columns would leave one tile alone on the second row.
- **Visual details the spec left open.** Tile icons are 28dp and the line icons 16dp. A new colour token, `SymptomIconIdle` #C9CCD3, is used for unticked icons. Save reuses the app's amber `PrimaryActionButton`, and Skip is a muted text button under it. Title is 22sp regular. The icons are the approved SVG paths parsed at runtime with `addPathNodes`; a unit test builds every one, so a broken path fails the tests rather than crashing the app when the dialog opens.
- **Refactor.** The ViewModel's `rateMorning` and `ratePastNight` now share one `rateNight` path: read the previous rating, record, redraw, maybe ask. The morning card's state moved out of the ViewModel into the pure `buildMorningRatingCard`.

## Open questions
- Should the 15:00 Okay/Bad also navigate to that night's Past night screen under the dialog? For now it opens over whatever screen the app starts on.
- The tile colours, sizes and dialog layout were not checked on the phone or emulator; the brief said not to use them. They need a look on the device.
- Logs list chips don't show symptoms (unchanged). Should they?
