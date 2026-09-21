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
