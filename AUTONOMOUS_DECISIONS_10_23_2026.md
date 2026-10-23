# Autonomous decisions, 2026-10-23

Run: PER-148 (Settings and History on the Night screen), PER-149 (One more cycle), PER-150 (No-alarm night), then PER-151 (Are you up? ask). Design settled with the owner in the grilling session the same day; terms in CONTEXT.md.

## Decisions

### PER-149 One more cycle (branch nikita/feat/one-more-cycle)
- Its own alarm kind: the ring screen, notification and Night screen say "One more cycle" rather than reusing the nap wording. Log events one_more_cycle_armed, one_more_cycle_fired, one_more_cycle_refused.
- Shares the nap's alarm slot and saved record; not counted against the nap limit; repeatable.
- Uses the engine's cycle length (90 min), so a future cycle length setting moves it too.
- Hidden once a deadline has passed (it never caps); Nap is still offered then.
- Offered wherever Nap is, including after a band-detected nap before the morning alarm.
- Outlined button; Nap stays the one filled button.

### PER-148 Settings and History on the Night screen (branch nikita/feat/settings-history-on-night)
- Icons in the Night screen header in every state, morning report included. If the night ends while Settings or History is open, back lands on the morning report.
- History hides the running night; it appears after End night.
- Nap length and nudge delay changes are written into the saved night state (logged as after_alarm_settings_changed); the next nap or nudge uses them, alarms already set keep their time.
- Start night always arms the fade; on/off is checked at each fade start.
- Left as is: an engine nap armed while asleep before the morning alarm (rare) is recomputed each tick, so a nap length change mid-night moves that one alarm. Freezing it would need an engine change; not worth it for a rare case unless the owner says so.

### PER-150 No-alarm night (branch nikita/feat/no-alarm-night)
- Lives in the engine: NightSettings.noAlarm plus AlarmMode.NO_ALARM, returned before every existing rule, so normal nights run the same code as before. The engine rejects a No-alarm night with a deadline.
- I'm up is recorded as the morning alarm's stop, so the usual after-alarm behavior follows (for example, no media pause on sleep after it).
- The last picked cycle count is kept while No alarm is picked.
- "No alarm" is a fifth equal-width chip in the same row; its label may wrap on a narrow screen.
- Log: noAlarm=true on night_start and night_end; plan events have mode NO_ALARM and an empty wakeAt.

### PER-151 Are you up? ask (branch nikita/feat/are-you-up-ask)
- Checked on each tick: No-alarm night, before I'm up, 6 h asleep in total, and a band awakening of 15+ min.
- Notification channel is default importance with sound, vibration and light off, not low importance: Pixel hides low-importance (silent) notifications from the lock screen by default.
- I'm up on the banner skips the usual confirmation dialog, since it answers the app's own question.
- A dismissed ask, or one found missing after a reboot, is posted again. Ending the night removes it.
- Still sleeping asks again only after the band shows sleep and then a new 15+ min awakening.
- Log: are_you_up_asked, are_you_up_reposted, are_you_up_answered.

### Merging
- Two docs/app-spec.md conflicts (the Night and Past night paragraphs) were resolved by keeping every ticket's addition. 994 tests green after all three merges.

## Open questions for the owner
- PER-150: Past night reads "Picked: No alarm". Good wording?
- PER-150: is the 5-chip row readable on the Pixel, or should No alarm get its own line?
- PER-151: on the Pixel, does the ask show on the lock screen and stay silent?
- PER-151: the ask can come up to about 15 min after the awakening reaches 15 min (normal tick). Book an extra tick at the 15 min mark?
- PER-151: if band data stops updating, the awakening length is measured from the band data, not the clock. Acceptable?
- PER-148: should a mid-night nap length change also leave an already armed engine nap (the rare in-between-cycles nap before the morning alarm) untouched? Today it moves it.
- PER-149: ring headline "One more cycle" or "One more cycle alarm" (the nap rings as "Nap alarm")?
- PER-149: should One more cycle be the filled (main) button instead of Nap?
