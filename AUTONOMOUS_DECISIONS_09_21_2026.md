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
