# Autonomous decisions, 2026-10-03: review issues (tmp/fable/ISSUES.md)

Owner answers before the run (2026-10-03):
- Mode: autonomous, subagents in waves, TDD. Seams: engine plan functions, Robolectric scenario tests through receivers, ticks and buttons. Architecture refactor out of scope.
- #6 a nap ringing at or after the latched morning time IS the morning alarm: ring label says Morning alarm, I'm up flow and night_summary treat it as the wake alarm.
- #7 pause-after-fade with band lag: keep as is (no fresh-sync requirement).
- #8 fade settings stay live mid-night: fix the spec, pin with a test.
- #9 (not asked, default): Auto awake mid-night should follow the H8 morning alarm; treated as a bug.

## Decisions
- Second round of owner answers (2026-10-03):
  - #2 silent deadline: if no morning alarm rang and the deadline is past, arm it at now + 2 min (minAlarmLead); end silently only when the deadline is more than 1 hour past.
  - #4 alarm volume: warn in Setup and the Start night readiness check when STREAM_ALARM is low; while ringing raise it to at least 50% of max, restore after.
  - #9 Auto awake mid-night slows to 1x before the planned morning alarm or deadline.
  - #14 End night racing a firing alarm: owner believes the ring has its own window with a Stop; verify on the emulator, code change only if it cannot be stopped. Nap tap vs in-flight nudge: same, verify first.
- Wave A (items 1-6):
  - #1: fixed at the two places that re-armed, not in armPhoneAlarmIfNeeded: `shouldKeepPreviousPlan` gained a `morningAlarmRang` input (no freeze once the morning alarm has rung, which I'm up records), and BootReceiver re-arms the saved plan only through the new `shouldRearmPlanAlarmOnBoot` (same refusals plus "morning alarm rang").
  - #2: rule 1 is now `past deadline -> FINISHED if morning rang or deadline > 1 h past, else DEADLINE_ONLY`; the recovery is the ordinary D8 pull-forward (deadline target in the past -> now + 2 min, whole minute), with the deadline cap applied only while the deadline is still ahead. The 1 h is a new engine constant `DEADLINE_RECOVERY_WINDOW`, not an EngineConfig field (no setting needed). "Morning rang" is `morningAlarmHasRung`, so an I'm up before the deadline also lets it finish. The plan reason reads "The deadline HH:MM passed with no alarm rung, alarm HH:MM." for the recovery.
  - #2 side effect, accepted: the recovery target is re-pulled on every tick (now + 2 min), like any D8 overdue target, so ticks closer together than 2 min (the app opened repeatedly) push it later each time. Ordinary ticks are 5 min apart, so it rings before the next one.
  - #3: the arming intent carries `EngineConfig().ringAutoStopAfter` (AUTO_STOP_AFTER) rather than a per-call value: resolveEngineConfig never changes it for a night (T7), so no call site needed a new parameter. An intent from an older build falls back to the same default. A failed ring-service start runs the fallback immediately, before the bookkeeping.
  - #4: threshold picked: "low" = under half the ALARM stream's range, half rounded up (4 of 7, 8 of 15); the ring floor is the same number, so a volume Setup accepts is one the ring leaves alone. Setup shows it as WARNING (never blocks readiness); alarm_readiness logs alarmVolume, alarmVolumeMax, alarmVolumeLow; alarm_ring_started logs alarmVolumeRaisedFrom. A ring restarted while ringing (F1) keeps the first saved volume; restore also runs in onDestroy.
  - #5: in-memory fallback for `phoneAlarmFiredFor` only (the marker every firing writes): `readPhoneAlarmFiredFor` returns the later of the file and the last saved value; cleared with the other stores at Start night / night end. Enough on its own: the spent-target check and morningAlarmHasRung both read it.
  - #6: label and summary changed, attribution not: a nap at/after the latched morning time rings as MORNING (`alarmLabelFor` adds `morningAlarmHasRung(null, morningAlarmAt, armedFor)`), night_summary uses the new engine helper `morningAlarmRangAt`. PhoneAlarmReceiver still books that firing as a nap (napAlarmsUsed, lastNapAlarmFiredAt), so the J2 must-fix 2 attribution tests stand; its two label tests were rewritten to the new rule. The per-awakening `afterWakeAlarm` flags in the tick log (awakening_started/ended) still key on wakeAlarmFiredAt; left alone as outside the asked scope.
  - Tests that encoded the old rule 1 were updated, not deleted: ChooseModeTest rule 1, ComputeAlarmPlanTest "deadline exactly at now", WholeNightSequenceTest's final FINISHED step (now passes the 08:09 firing it assumed).

## Open questions
- #2: is 1 h the right recovery bound, and should a recovery ring past the deadline say so on the ring screen (it rings as "Morning alarm")?
- #5: the in-memory fallback dies with the process; a second-file or log-based marker would survive that. Worth it given both file writes must fail first?
- #6: should the tick log's per-awakening `afterWakeAlarm` flag also count from a nap that rang after the morning time, to match night_summary?

