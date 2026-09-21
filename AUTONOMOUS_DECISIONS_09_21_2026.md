# Autonomous decisions, 2026-09-21

Working from the audit's J1 batch (branch `nikita/fix/overnight-hardening`, one commit f90012e ahead of
main). This file records non-trivial calls made without stopping to ask, in the order the findings were
worked.

## J1.1 (landed)

- Verified the audit's C1 claim myself before relying on it: `docs/decisions.md`'s own D8 entry says
  "`minAlarmLead`'s pull-forward survives unchanged" when the band-write path was removed, and the D8 doc
  comment in `WakeAlarm.kt` already says the phone alarm has no rounding need the band used to have. Nothing
  in the codebase or docs claims a phone-native `AlarmManager.setAlarmClock` alarm needs any minimum lead
  before it can be armed, so pulling the whole "raw in the future but inside the lead window" case out of
  `pullForwardIfTooSoon` is safe: only the genuinely overdue case (`raw` at or before `now`) still needs a
  lead, so the phone is never asked to arm something in the past.
- Checked the single call site (`computeWakeAlarm` at WakeAlarm.kt:44) - `pullForwardIfTooSoon` is private
  with no other caller, so no other code path needed touching.
- Checked every existing test that exercises `pullForwardIfTooSoon` (`ComputeWakeAlarmTest`,
  `ComputeAlarmPlanTest`, `WholeNightSequenceTest`, `NightTotalCyclesTest`): all of them compute a `raw`
  target that is already at or before `now`, none of them pin the "raw still ahead but inside the lead"
  defect as correct. None broke. No test needed reinterpreting for this finding.
- Added `J1_1` (test names cannot contain a literal `.` - Kotlin's backtick-quoted function names compile to
  JVM method names, which forbid `.`; every other test in the file already avoids it, e.g. `H8`, `D8`, `C1`
  with no decimal point) as the regression test in `ComputeWakeAlarmTest.kt`, using the owner's own traced
  numbers (onset 23:00, 5 cycles, tick at 06:28:30). Verified it fails against the pre-fix code (temporarily
  restored the old `WakeAlarm.kt` from HEAD, ran just that test, saw `AssertionFailedError`, restored the fix)
  before committing.

## J1.2 (landed)

- The task description said `NapAlarmCountingTest` has a test pinning the exact-equality behaviour that would
  need updating. I read the whole file first: no existing test actually exercises a `firedFor` value that is
  close to but not equal to `morningAlarmAt` (the file's one near-miss fixture, `napFiredAt`, sits 30 minutes
  past it, far outside any reasonable tolerance window) - so nothing in the file was actually PINNING the old
  exact-equality bug, and the full `:app:testDebugUnitTest` run confirmed nothing broke when the predicate
  widened. I widened it anyway per the finding (independent hardening, not contingent on J1.1), and instead of
  editing a nonexistent pinned expectation I added four new tests under a J1.2 heading: one minute after
  (inside the window, now true), exactly at the tolerance boundary (still true), one second past the boundary
  (still a genuine nap, false), and the matching `alarmLabelFor` case. Report this discrepancy rather than
  forcing an edit to a test that did not need one.
- Chose NOT to thread `EngineConfig` through `firedAlarmIsWakeAlarm` and its two call sites
  (`PhoneAlarmReceiver.recordWakeOrNapFired`, `alarmLabelFor` and, through it, `BootReceiver`, `NightController`,
  `NightOrchestrator.armPhoneAlarmIfNeeded`, and the UI's `BuildNightScreenContent.kt`). `resolveEngineConfig`
  already always returns the real, unscaled `EngineConfig` regardless of debug options (its own T7 comment:
  "resolveEngineConfig always returns the real EngineConfig regardless of debug options"), so `minAlarmLead` is
  a genuine compile-time constant in practice, not a per-call value a default could silently hide - unlike
  J1.3's `phoneAlarmFiredFor`, which is real per-night state and gets threaded explicitly. Added a private
  `WAKE_ALARM_FIRE_TOLERANCE = EngineConfig().minAlarmLead + 1 min` (3 minutes total) inside
  `NightOrchestrator.kt` instead, next to `firedAlarmIsWakeAlarm` itself, so the widening is visible right where
  H8/J1.2's own doc comment explains it, without touching any UI file's call site or signature.
- The tolerance window is `[morningAlarmAt, morningAlarmAt + 3 min]`, closed on both ends. A real rule 7 nap is
  never less than `napLength` (20 min) past whatever it is measured from, so nothing genuinely nap-shaped can
  ever land inside that 3-minute window and be misattributed as the wake alarm.

## J1.3 (landed)

- Threaded `phoneAlarmFiredFor: Instant?` through `computeAlarmPlan` (engine `Plan.kt`) into
  `computeWakeAlarm`/`morningAlarmAlreadyRang` (`WakeAlarm.kt`), and hardened `morningAlarmAlreadyRang` to treat
  a morning target as spent when it is at or before the LATER of `wakeAlarmFiredAt` and `phoneAlarmFiredFor`
  (a small `laterOf` helper, null-safe on either side). Exactly as specified.
- Found every production call site by letting the Kotlin compiler enumerate them
  (`:engine:compileTestKotlin :app:compileDebugUnitTestKotlin`) rather than trusting my own grep to be
  exhaustive - safer for a signature change with this many callers. Two real production call sites:
  `NightOrchestrator.kt` (passes `state.phoneAlarmFiredFor`, the freshest merged-in value per FIX1) and
  `NightController.kt`'s `startNight` (passes `phoneAlarmFiredFor = null` explicitly, with a comment - genuinely
  null on a brand new night since `clearAlarmFiredStores` just ran, not a default standing in for a forgotten
  caller). The task brief said `NightUiSupport.kt` was one of the touched call sites; it is not - that file
  never calls `computeAlarmPlan` at all (checked directly). Noting the discrepancy rather than silently
  dropping it: the brief may have been thinking of a different file, or of a version of the codebase before
  some earlier refactor.
- Added the "second line of defence" exactly as suggested (optional per the brief's own "consider"): added
  `config: EngineConfig` to `armPhoneAlarmIfNeeded` and refuse a `wakeAt` strictly after `phoneAlarmFiredFor`
  but within `minAlarmLead` of it, logged, not silent. Flagging a real limitation I found while reasoning
  through it: this guard's window is anchored to `phoneAlarmFiredFor` (a fixed instant), while a re-armed
  `wakeAt` is anchored to `now + minAlarmLead` (which drifts later every tick) - so it only actually catches a
  re-arm attempt on the very next tick after the missed firing, not one several ticks later once `now` has
  moved past the window. The PRIMARY fix (`morningAlarmAlreadyRang`) does not have this limitation for rules
  3/4/5/6, since `raw` is a FIXED instant for the whole night regardless of how many ticks pass - it stays
  spent forever once `phoneAlarmFiredFor` reaches or passes it. This second guard is a genuine backstop, not a
  complete independent fix, exactly as its own "second line of defence" framing implies.
- Scope note, not fixed, flagging for awareness rather than silently expanding J1.3's scope: the SAME
  stale-load race can also skip `saveLastNapAlarmFiredAt` for a mid-night rule 7 NAP firing (it sits behind the
  identical `firedPlan == null` bail-out in `PhoneAlarmReceiver.recordWakeOrNapFired`, guarding
  `wakeAlarmFiredAt` and `lastNapAlarmFiredAt` alike). `asleepNapTarget` (`WakeAlarm.kt`) has no equivalent to
  `morningAlarmAlreadyRang` at all - it is exempted from that guard by design (H8's own comment: "Rule 7's own
  targets are exempt... a nap is measured from an onset or a firing of its own") - so a nap whose attribution
  is skipped this way could in principle re-ring the same way the wake alarm did before this fix. The finding
  as written scopes the fix to `morningAlarmAlreadyRang` (rules 3/4/5/6) specifically and only "considers" the
  `armPhoneAlarmIfNeeded` guard as a general backstop; extending the primary fix to the nap side too is a
  larger, different change (asleepNapTarget's own anchor logic, not this function) that the brief did not ask
  for, so I left it alone rather than expanding scope unasked. Worth a dedicated look in a future pass.
- Verified both new regression tests (`ComputeWakeAlarmTest.kt`) fail against the pre-fix `morningAlarmAlreadyRang`
  (temporarily restored its old wakeAlarmFiredAt-only body while keeping the new signature, ran just those two
  tests, saw `AssertionFailedError` on both, 26 other tests in the file unaffected, then restored the real fix).
  Did not add a dedicated unit test for the `armPhoneAlarmIfNeeded` second-line guard itself: it is a private
  function that calls real Android `AlarmManager`/Context APIs and the project has no Robolectric, so (per the
  environment brief) only pure functions are unit-testable here - it is exercised only indirectly, through the
  app-level integration-style sequence tests, none of which broke.
