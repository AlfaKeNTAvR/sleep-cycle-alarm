# Autonomous decisions, 2026-09-17

Run mode: Autonomous. Dispatcher: Fable. Workers: Sonnet, GPT-5.6 Terra. Reviewers: Opus, GPT-5.6 Sol. Plan critique: GPT-6 Astra.

## Decisions

1. **Tools live in `~\android-dev`** (approved): Temurin JDK 21.0.12, Android SDK (platform-tools 37.0.1, platform 36, build-tools 36.0.0). No PATH change, nothing system-wide. Delete the folder to undo. The project finds them through `local.properties` (not in git) and `scripts/env.sh`.
2. **Work happens on branch `nikita/feat/app-v1`** in the project repo. Nothing is committed or pushed; that stays with you.
3. **Two Gradle modules**: `:engine` (pure Kotlin, alarm math, fast JVM tests) and `:app` (Android). Why: the rules can be tested in seconds without a phone.
4. **Engine takes "sleep stretches", not raw band rows.** Why: tonight's test may show the band does not release sleep marks mid-night. Then only the detector (heart-rate based) changes; the alarm math stays.
5. **Nap mode never triggers without a deadline.** Why: rule 6 restarts a full count after each awakening, so without a deadline a full cycle always fits. See question 1.
6. **Before sleep is detected, the band alarm is already set** from a projected onset (now + 15 min). Why: if every later sync fails, an alarm still exists.
7. **A data gap does not count as an awakening**, only an awake mark does. Why: night 1 had a 16 min gap with no data while asleep.
8. Full engine behaviour is written down in `docs/engine-spec.md`.

### After the GPT-6 Astra plan critique

9. **Reading band data: shared export file.** Gadgetbridge exports its database to one file you pick once; our app is given access to that same file once (Android file picker), copies it, checks it is not corrupt, and reads it. Why: Gadgetbridge has no data API; "all files" permission cannot read its private database either. Health Connect exists in 0.94 but adds another delay, kept as a fallback.
   Needs these Gadgetbridge settings: auto export on with a location, Intent API "allow database export trigger" and "broadcast on export".
10. **Overnight: a foreground service (type "special use") with a notification**, each sync tick woken by an exact alarm, a short wake lock only during the ~20 s sync. Known limit: when the phone is in deep idle Android may stretch 5 min ticks to about 9 min. Accepted for v1; the night log records real tick times so we can measure it.
11. **Band alarm is moved without a gap**: set the new alarm under a second title first, then remove the old one. Two alternating titles (`SCA-A`, `SCA-B`). Why: Gadgetbridge removes by title substring and remove-then-set would leave a moment with no band alarm.
12. **Night state is saved to disk separately from the log** and the phone alarm is re-armed after a reboot or app kill.
13. **Phone alarm uses "exact alarm for alarm clock apps" permission** (granted automatically), full-screen alarm screen, alarm audio channel. Do Not Disturb must allow alarms (Pixel default does).

### After the Opus and Sol reviews of the alarm math (both said: rework)

14. **Decision 5 is reversed.** Without a deadline, "the planned alarm" in your rule 7 is the last full-cycle band alarm. Plan 08:00, you wake at 07:00: 20 min nap, not a new 7.5 h count (the first code moved the alarm to 14:35). Waking at 03:00 still restarts a full count, as rule 6 says.
15. **With a deadline, nap is judged against the deadline**, as your night screen C says ("No full cycle fits before 08:30"). Plan 08:00, deadline 08:30, back asleep 06:45: one cycle fits, band alarm 08:15.
16. **Nap only after an awakening.** A first sleep that starts too close to the deadline gets the band at the deadline (rule 5), not a 20 min nap.
17. **Sleeping through the band alarm**: while the band still says asleep, it buzzes again 2 min after each sync (every 5 min), like a snooze, until it marks you awake. The no-deadline phone backup stays where it was.
18. **Night over**: deadline passed, or an awake mark after the alarm time. Syncing stops, the phone alarm stays armed.
19. **No band alarm closer than 2 min ahead.** The band takes hour and minute only and the write takes seconds.
20. **Awake marks win over overlapping sleep marks; marks dated in the future are ignored.**
21. **Accepted as is**: while you lie awake, the band alarm moves at each sync and drops by 1.5 h when a cycle stops fitting. That is rule 1. The app only writes to the band when the minute changes.

22. **The snooze after a missed band alarm stops after 30 min** (second Sol review). Then the night counts as over; the phone backup stays as it was. Why: with no deadline and a band that never marks you awake (for example taken off), it would buzz every 5 min forever.
23. **Band alarm is confirmed by reading it back.** Gadgetbridge gives no answer when asked to set an alarm, and it only uses a slot that is disabled AND has no title. Your band has one such slot today; the others are titled "Alarm". The app reads Gadgetbridge's alarm table from the same export, confirms the new alarm really exists, and only then removes the old one. Setup asks you to clear the title of one more disabled alarm in Gadgetbridge and blocks "Start night" until two slots are usable. This replaces the blind "set new, then remove old" of decision 11.
24. **Core layer fixes after the Opus and Sol reviews**: phone alarm re-armed on every sync (survives force stop), one failed sync can no longer end the overnight loop (a fallback sync is always scheduled), wake locks for the sync and for the ringing alarm, one sync at a time app-wide, state file with backup copy, stale export data detected and logged, clock or time zone change triggers a re-plan.

### After the final Opus, Sol and Astra reviews (all three: fix first)

25. **Band alarm logic rewritten around "read back first".** Every sync: look at what the band really has, confirm it, then change one thing at a time. The band is never left with zero alarms because the target moved. Removals are verified too. If the export is broken all night, the first band alarm is still sent blind (decision 6 kept).
26. **A stopped phone alarm never rings again.** My "re-arm every sync" (decision 24) would have re-fired an alarm whose time had passed. Now the app remembers which alarm already rang.
27. **The ringing notification gets a Stop button and opens the alarm screen on tap.** Before, if Android showed a banner and not the full screen, there was no way to stop it for 10 min.
28. **Phone alarm is armed before the first Gadgetbridge wait** when a night starts.
29. **The night log records enough to answer the three field questions**: planned vs real sync times (phone idle delay), each sleep segment and when it first appeared (does the band publish marks mid-night), every band command and the band's alarm table (did the alarm really land).
30. **Known limit, not fixed in v1:** if the phone reboots during the night (for example a system update), the phone alarm comes back only after you unlock the phone. Why deferred: the fix changes the most critical path and could not be tested before the first night.
31. **Known limit:** without a deadline, if the band never reports sleep, the wake time keeps moving forward and nothing rings. The Before bed screen says so. Use a deadline for the first nights.

### Debug mode, and the Opus review of it (fix first)

32. **Debug mode (debug build only)**: simulated sleep buttons, a fast night (cycle 5 min, nap 3 min, sync every 1 min, snooze cap 5 min), dry run or real band commands, "ring phone alarm in 1 min". Simulated nights get their own log files (`night-sim-...`). My first fast timings (nap 1 min = alarm lead 1 min) made nap mode impossible to see; corrected.
33. **Debug switches cannot leak into a real night**: a banner names every live switch on Before bed, the Night screen and the night notification; switches turn themselves off when a night ends and 2 h after they were last changed. Why: with "dry run" left on, nothing reaches the band, and with a deadline there is no phone backup either, so nothing would wake you.
34. **The test alarm button is separate from the real phone alarm** and disabled during a night. As first written it replaced the night's real alarm and then marked it as already rung.
35. **The snooze after a missed band alarm now really reaches the band.** The band takes whole minutes only; the snooze target was "now + 2 min" with seconds, got cut down to the minute, and was then rejected as too soon, every time. The alarm math now rounds the snooze up to the next whole minute.
36. **Broken export all night** (superseded by decision 40): the blind band alarm was re-sent when the target changed and every 4 syncs. That was my instruction and it was unsafe: "remove, then set" can leave the band with no alarm when the set fails silently, and every re-send that lands eats another band slot.
37. **App killed mid-command**: what the app is about to ask the band is saved before it is sent, and an alarm of ours found on the band that the app does not remember is adopted or removed. Before, it stayed and buzzed early.
38. **After the night is over the app keeps syncing a few more times** (at most 6) until the band confirms our alarms are gone, so a stale alarm cannot buzz the next day.
39. **Known limit, not changed here:** during an OVERDUE snooze, the read-back protocol means a fresh buzz lands roughly every second sync (about every 5 to 10 min), not every sync.
40. **Blind mode now freezes instead of chasing:** when the app cannot see the band's alarm list it keeps the alarm it already asked for and changes nothing, because a command that fails silently could leave the band with no alarm; the phone alarm still follows the plan.

### Logs as history: reopening and deleting past nights (2026-09-18)

41. **The night summary is now written into `night_end` as structured fields.** It used to be one line, `summary=total=PT6H50M stretches=5`, which says how much was slept but not when - not enough to draw a summary screen. `night_end` now also carries `totalSleep`, `stretches` (a JSON array of onset, end and cycles per stretch), `deadline` and `pickedCycles`. The old `summary` line is kept unchanged, because it is the one thing a human reads when scrolling a raw log.
    Considered and rejected: rebuilding the summary from the `data` events' `segments` field, which is already in every log. That would need the engine's normalize/stretch/summarize pipeline and the night's own EngineConfig applied at read time, so a later engine change would silently rewrite history. What `night_end` records is what that night actually concluded.
42. **Nights logged before decision 41 are shown reduced, never faked.** Their `night_end` has only the old line, so the app shows the real total and one sentence saying how many stretches there were but not when. The deadline and picked length still come from `night_start`, which has always recorded them - so the night of 2026-09-18 renders as "6 h 50", "5 stretches, but not their times", "Wake by 08:30", "Picked 7.5 h". A log with no `night_end` at all (a night never ended) says no summary was recorded. No zeroes, no invented times.
43. **Three-dot menu per row**, with Share moved into it and Delete added. Delete asks once, then removes the file. Tapping the row itself opens the night.
44. **The morning report's rendering is shared, not copied.** `MorningReportContent` (state D) and the new past-night screen both call `MorningReportBody`, which takes the caption as a parameter ("Good morning. You slept" tonight, "You slept" for an older night). `NightScreenContent.MorningReport` gained one defaulted field, `stretchDetailRecorded`, which is the only thing that tells the body to leave the stretch card out rather than draw it empty.
45. **Which night is open lives outside `UiState`**, as a separate `StateFlow` on the ViewModel, exactly like `setupWizardPage`. Why: the band-alarm protocol rewrite is touching `NightViewModel.kt` and `NightUiState.kt` in parallel, and this keeps both diffs to a few additive lines instead of threading another input through `buildUiState`'s already-saturated `combine`.
46. **Menu-open and delete-confirmation state is local to the Logs screen** (`remember { mutableStateOf(...) }`), following `TimePickerField.kt`. Both are momentary and mean nothing once the screen is left, unlike the end-night confirmation, which guards a real transaction.
47. **A simulated fast night's picked length is labelled from its own log.** `night_start` records `fastNight`, so a fast night reads back as "15 min", not "7.5 h".

## Open questions for you

1. No deadline + restart rule: with a plan for 08:00, waking at 06:00 still restarts a full 7.5 h count (alarm 13:45), because one cycle fits before 08:00. Only waking after 06:15 becomes a nap (decision 14). Is a full restart that late what you want, or should the first plan act as a soft deadline all night?
2. `minAwakening` is 1 min: every awake mark restarts the count. Night 1 had a 2 min mark at 04:47 that you did remember, so I kept it sensitive.
3. Should the Logs rows show each night's total slept instead of the file size? It reads better as history, but it means opening and parsing every log file to build the list, where today the list is only a directory listing. Not done here for that reason.
4. Deleting a log is permanent and immediate. Would you rather have an undo (keep the file aside for a day), or is the single confirmation enough?
