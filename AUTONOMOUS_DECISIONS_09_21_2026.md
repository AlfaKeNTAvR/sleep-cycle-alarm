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

## J1.4 (landed) - session resumed after a usage-limit reset; J1.1-J1.3 were already committed

Picked up after the limit reset. Re-ran the verify command first, per the resumed instructions, confirming the
512-test baseline (another agent's two commits, 32c03e0 and 67394d2, had landed on `ui/`/`res/`/docs files only
in the meantime - untouched here, no reason to touch them).

- Re-anchored `NightReplay.kt`: `startNight(at)` is now the one explicit first tick; `markAsleep`/`markAwake`
  only record the mark and no longer trigger a tick of their own (previously `mark()` called `runTick()`
  immediately after recording). Added an optional per-call `syncDuration: Duration` to `advanceTo`/`openApp`:
  a tick that becomes due during that call reads its own input snapshot (segments, wakeAlarmFiredAt,
  phoneAlarmFiredFor, etc.) at the instant it starts, via a new `PendingTick` holding pattern, but does not
  commit (arm/latch/save/reschedule) until `syncDuration` later - mirroring FIX1's own real trade-off (state
  loaded and decisionNow sampled before the sync's own I/O runs). Added `openApp(at, syncDuration)` for an
  immediate, off-schedule tick (NightController.runImmediateTick's own equivalent).
- Verified the re-anchoring alone does not change `AlarmSequenceReplayTest.kt`'s own 5 existing tests: updated
  its `nightAsleepAtEleven` helper to call `startNight` then `markAsleep` (same instant), reran, all 5 still
  pass. Traced why by hand first: the very FIRST mark's own old "immediate tick" always saw a ZERO-LENGTH
  segment (the mark's own instant to itself), which `normalizeSegments`/`clipToNow` already drop - so that
  first auto-tick was already a no-op for every existing test, and removing it changes nothing. Later marks'
  own auto-ticks were NOT no-ops (they saw real, non-zero segments) - removing THOSE is J1.4's real behavioural
  change, and it did not break any assertion because every existing test only asserts AFTER a further
  `advanceTo` call, which naturally reaches the next real scheduled tick anyway.
- Added `TickScheduleRaceTest.kt` with the four regression cases the brief asked for. Getting deterministic,
  non-coincidental numbers for each required hand-tracing the real `nextSyncDelay` schedule (normalSyncDelay 15
  min, frequentSyncDelay 5 min once inside `nearAlarmSyncWindow`) rather than picking round numbers - a tick
  grid anchored at a clean instant (e.g. exactly on the hour) tends to land EXACTLY on a target that is itself a
  multiple of 5 minutes away (cycles are 90 min, naps 20 min, both multiples of 5), which is precisely the
  "lattice" trap J1.4 exists to get away from. The owner's own traced numbers (night started 22:53:30, onset
  23:00, 5 cycles) reproduce a genuine 06:28:30 tick, 90 s before the 06:30 target, directly from the real
  schedule - no manual grid needed.
- **A test that cannot fail is worse than no test - found one of my own, fixed it rather than shipping it.**
  While satisfying the resumed instructions' explicit requirement to verify each test against a reverted guard,
  I found that my own "the same lead-window tick with a slow sync rings twice, but never a third time" test
  (aimed at pinning J1.3's own `phoneAlarmFiredFor` threading in `morningAlarmAlreadyRang`) PASSED UNCHANGED
  when I reverted ONLY that guard (keeping J1.1 applied). Root cause: in this scenario attribution never
  actually fails - `lastPlan.wakeAt` always matches whatever fires, because a stale, mid-sync tick's own
  computed value never actually DIVERGES from what is already armed (H1/H8's own "defer to the still-pending
  target" design, working exactly as intended, keeps the VALUE identical across ticks right up until it is
  known to be spent) - so the PRE-EXISTING `wakeAlarmFiredAt`-only check (H8, predating J1.3 entirely) already
  bounds the loop to two rings on its own, with no need for `phoneAlarmFiredFor` in this specific case. I spent
  real effort trying to construct a scenario with a genuine ATTRIBUTION MISMATCH (a firing whose `lastPlan.wakeAt`
  actually differs from `firedFor`, not just a stale re-arm of the same value) - the literal case
  `recordWakeOrNapFired`'s own mismatch-and-skip branch exists for - and concluded it cannot be built
  deterministically at the whole-sequence level in this harness: every code path in this codebase that could
  produce a DIFFERENT value while a target is still future-dated is deliberately guarded against by H1/H8
  (returning the SAME pending value instead), so a genuine mismatch needs the RECEIVER's own disk read to race
  a concurrent tick's save at sub-instant granularity - true concurrency an instant-ordered, single-threaded
  replay cannot express without inventing a second "in-flight, undoable" channel this task did not ask for.
  Renamed the test from "J1_3 replay" to "J1_1 replay" and rewrote its docstring to say exactly this, in the
  test file itself, not just here - so the test's own name never claims more than what was verified. J1.3's own
  `phoneAlarmFiredFor` threading remains directly and unambiguously pinned by the two `ComputeWakeAlarmTest.kt`
  tests added during J1.3's own commit (`J1_3 a morning target is still spent when only phoneAlarmFiredFor
  recorded the firing` and its LATER-of-the-two sibling), both already verified to fail pre-J1.3 at that time.
- Same check on the "waking 4 minutes before the alarm, opens the app 90 s before it" test: it was meant to
  also pin J1.2's window-widening in `firedAlarmIsWakeAlarm` (the firing here is NAP-mode, going through that
  exact check), but reverting J1.2 alone (J1.1 still applied) also left it passing unchanged - because J1.1
  keeps the firing landing EXACTLY at `morningAlarmAt`, and H8's ORIGINAL exact-equality check already succeeds
  on an exact match; J1.2's window only matters once there is an actual few-seconds-to-few-minutes SHIFT, which
  J1.1 is precisely what prevents. Renamed from "J1_1 and J1_2 replay" to "J1_1 replay" and said so in its own
  comment, pointing at `NapAlarmCountingTest.kt`'s own direct J1.2 regression tests instead. Verified both
  renamed tests still genuinely fail when J1.1 itself is reverted (they do - see the run before this one).
  Confirmed the OTHER two tests ("a scheduled tick landing inside minAlarmLead before the morning alarm" and
  "the nap equivalent") are exactly what their names say: both fail when J1.1 is reverted, both pass with it
  restored, no relabeling needed.
- The nap-equivalent test needed `pickedCycles=1`, which `validateSettings` rejects (`allowedCycleCounts` is
  {3,4,5,6} only) - switched to `cycles=3` with a deadline placed close enough that `isNapEligible`'s own
  deadline branch (`referenceOnset + cycleLength` overruns the deadline) makes the return-to-sleep NAP-eligible
  regardless of `owedCycles`, without needing an invalid picked-cycle count.
- Every revert-and-verify in this section was done the same way each time: save the fixed file to a scratch
  path outside the repo, apply the minimal revert (kept the J1.3-era signature/parameter so only the ONE guard
  under test changed, everything else still compiled), ran the specific test(s), captured the pass/fail result,
  then restored the saved fixed file and re-ran the full `:engine:test` to confirm the repo was back to a clean
  state before moving on.
- Final verify: `BUILD SUCCESSFUL`, 517 tests, 0 failures, 0 skipped, no Kotlin compiler warnings from
  `compileKotlin`/`compileTestKotlin` in either module.

## J1.5 (landed)

- `resolveSyncOutcome` (NightOrchestrator.kt) was ALREADY the pure, named function the finding asked for - it
  just had no test and nothing downstream ever refused to re-plan from what it returned. Added the actual
  decision as a SEPARATE pure function, `shouldKeepPreviousPlan(outcome, previousPlan, phoneAlarmFiredFor)`,
  rather than folding it into `resolveSyncOutcome` itself: `resolveSyncOutcome` decides what THIS sync
  produced (fresh data, kept-stale data, or a failure), which is a fact about the sync; whether to actually USE
  that fact to re-plan is a SEPARATE decision that also needs the previous plan and the fire-time bookkeeping,
  neither of which `resolveSyncOutcome` has or should need.
- Wired it into `runNightTickLocked`: when true, skip `computeAlarmPlan` entirely and reuse `state.lastPlan`,
  logging a dedicated `dead_band_keep_plan` event (mode/cause/wakeAt) so this is visible in the night log as a
  deliberate skip, not silently identical output that could be mistaken for a coincidence. `scheduleNextTick`
  still runs off the kept plan afterward, so the tick schedule itself is untouched - only the PLAN stops
  updating while the band stays dead.
- Extended the guard beyond the finding's own literal wording: `previousPlan?.wakeAt != null` is not quite
  enough by itself, because the ALREADY-FIRED case would otherwise freeze the plan forever even after the
  protected alarm has already rung (PhoneAlarmReceiver fires independently of ticks, so the freeze's own job is
  already done at that point) - added `phoneAlarmFiredFor` so the freeze specifically ends once the KEPT plan's
  own `wakeAt` matches it. Decided this without asking: it is a strict narrowing of when the freeze applies (a
  smaller behavioural footprint than the literal reading, never a larger one), and leaving it out would have
  meant logging "keeping the plan" every single tick for the rest of a dead-band night even long after the one
  alarm it was protecting had already rung, which reads as actively misleading in the log.
- Added `DeadBandPlanTest.kt` (7 cases: failed sync + armed plan keeps it, successful sync never keeps it, no
  previous plan, a FINISHED previous plan with no real alarm, the already-fired case ending the freeze, a
  DIFFERENT earlier firing not ending it, stale-data treated the same as an outright failure) - all pass, and
  since `shouldKeepPreviousPlan` is a brand new function (nothing to revert to a "before" state), the tests
  themselves directly exercise every branch of its own logic rather than pinning a prior defect.
- Also added `DeadBandDriftTest.kt` (engine module, pure, no Android) to prove the underlying MECHANISM this
  guards against actually exists, independent of the app-layer fix: three `computeAlarmPlan` calls with
  IDENTICAL (frozen) AWAKE-ending segments and only `now` advancing (00:40, 01:40, 05:40) produce a `wakeAt`
  that keeps sliding later by roughly however far `now` itself moved, never settling - confirming this is not a
  computeAlarmPlan bug (projecting `now + fallAsleepEstimate` while AWAKE is correct, documented behaviour for
  a band that IS still reporting) but a consequence of the APP re-feeding stale data into it on every tick,
  which is exactly the mechanism `shouldKeepPreviousPlan` interrupts. This is the closest this environment's
  testing constraints (pure functions only, no Robolectric) allow to a true end-to-end regression test for a
  fix that lives in `runNightTickLocked`, which itself needs a real `Context` to unit test directly.
- Test-count discrepancies noted throughout this session (517 expected vs. slightly higher counts observed a
  few times) trace to the concurrent UI-review agent's own commits landing test files on `ui/` in between my
  own runs - never anything under `night/` or `engine/`, confirmed by `git status` before every commit in this
  session.

## J1.6 (landed)

- `syncAndReadBandData`/`readBandData`'s own `since` parameter is used for TWO genuinely different purposes
  across its two call sites: NightOrchestrator.kt's real per-tick sync (where `since` means "the night's own
  official start", and the finding's own bug lives) and SetupCheck.kt's pre-night connectivity check (where
  `since` means "how far back to look for any recent data at all", a 1-day lookback already, unrelated to any
  particular night). Kept `readBandData`'s own signature and behaviour completely untouched and made BOTH the
  widened query window and the clip specific to the NightOrchestrator.kt path (`syncOrFail`/`resolveSyncOutcome`)
  - the smallest fix that does not touch the setup-check path's own, already-correct 1-day lookback at all.
- Split the fix into two pure pieces on purpose, one per file, per their own natural owners: `BAND_QUERY_LOOKBACK`
  (2 hours, the audit's own suggested figure) widens `syncOrFail`'s own query in NightOrchestrator.kt;
  `clipSegmentsToNightStart` (BandSampleMapping.kt, next to the file's other pure row/segment functions) does
  the actual clipping, called once in `resolveSyncOutcome`'s fresh-success branch. The failed/stale branches
  reuse `state.lastSegments`, which is always ALREADY the clipped output of an earlier pass through the success
  branch - clipping again there would be harmless but redundant, so left out.
- Checked `clipToNow` (Intervals.kt, engine) and `followsAwakening` (Stretches.kt, engine) first, per the
  brief's own instruction. Neither double-handles this: `clipToNow` clips the OTHER end of a segment (to `now`,
  the ceiling) and runs entirely inside the engine, with no floor-side equivalent at all - this fix adds
  exactly that missing floor clip (to the night's own start), one layer earlier, in the app's own band-reading
  code, before segments ever reach the engine. `followsAwakening` is about how an ALREADY-BUILT stretch gets
  interpreted (rule 6 eligibility), unrelated to which raw segments get INCLUDED in the first place.
- Regression coverage: 6 new cases in `BandSampleMappingTest.kt`, including the owner's own exact traced numbers
  (dozed at 22:50, tapped Start night at 23:05 - clips to 23:05-23:30, not dropped and not left at 22:50 or
  23:30) plus the boundary cases (entirely before, entirely after, ending exactly at the start, starting
  exactly at the start, multiple segments clipped independently). `clipSegmentsToNightStart` is a brand new
  function, so - like J1.5's `shouldKeepPreviousPlan` - these tests exercise its own logic directly rather than
  pinning a prior defect; there is no "before" version of this specific function to revert to and compare
  against. Did not attempt to test the SQL query's own widened `since` value end to end: that needs a real (or
  in-memory) SQLite database this environment has no Robolectric to provide, and the mechanism itself -
  `TIMESTAMP >= ?` against an epoch-seconds cutoff - is simple, deterministic SQL with no branch or edge case
  of its own to hide a bug in; the actual behavioural contract worth testing is entirely in the clip, which is
  covered.
- Final verify: `BUILD SUCCESSFUL`, 536 tests, 0 failures, 0 skipped.

## J2: adversarial review of J1.1-J1.6, four must-fix findings (session started 2026-09-21, same day)

Working the adversarial Opus review's four must-fix findings against the six J1 commits above. Two of the
four (must-fix 1, must-fix 4) are genuine REGRESSIONS J1.5 and J1.4/J1.3's own interaction introduced - the
branch was worse than before on those paths going into this session. Owned scope: `engine/`,
`NightOrchestrator.kt`, `alarm/`, and their test files. Did not touch `ui/`, `res/`, `OutOfBedNudgeStore.kt`,
`ui/` tests, `README.md`, `docs/` - another agent is concurrently editing those (confirmed live: a
`:app:testDebugUnitTest` run mid-session hit a transient `NO_VALUE_FOR_PARAMETER` compile error in
`ui/state/BuildUiStateTest.kt` while that agent's own `BuildUiState.kt` signature change was in flight; a
later rerun with no changes on my side passed clean once their own fix landed - noted here rather than
treated as my own regression, since `git status` throughout confirmed I never touched a `ui/` file).

### Must-fix 1 (landed) - the frozen-plan-forever regression

- Added `now: Instant` to `shouldKeepPreviousPlan` and required `wakeAt.isAfter(now)` alongside the existing
  `!outcome.syncOk && wakeAt != phoneAlarmFiredFor` - exactly as specified. Single production call site
  (`runNightTickLocked`) passes `decisionNow`, matching every other decision this tick makes.
- Verified by hand that this does not reopen a DIFFERENT problem: since `armPhoneAlarmIfNeeded` is called
  every tick regardless of which branch produced `plan` (kept-frozen or freshly re-planned), a frozen plan
  whose arm attempt keeps failing (permission revoked) still gets retried every tick while frozen - the new
  `now` guard only decides whether to re-PLAN from stale data, never whether to retry ARMING. This is also
  the answer to the reviewer's S5 note (the docstring said "already armed" when the code only ever knew
  "wakeAt non-null") - rewrote that paragraph to say plainly that this function does not know whether arming
  ever succeeded, and that the retry loop in `armPhoneAlarmIfNeeded` is what actually covers that case, not
  this guard.
- Added 2 new regression cases to `DeadBandPlanTest.kt` (a plan whose alarm time has passed, and the exact
  boundary - `now == wakeAt` releases the freeze, one nanosecond before it does not) as part of the same
  pass that also folded the two duplicate pre-existing cases the reviewer's S2 flagged (`:22`/`:58` in the
  original file both asserted the identical call under different names - "a failed sync with a real armed
  alarm keeps it" and "stale data is treated the same as a failed sync" - folded into one test, noted in its
  own comment why).
- Verified the regression tests fail without the fix: temporarily stripped `&& wakeAt.isAfter(now)` back out
  of the guard in the real file (not a separate scratch copy - simpler to revert-run-restore in place for a
  one-line guard, restored immediately after), ran `DeadBandPlanTest` alone, saw both new tests fail with
  `AssertionFailedError` (`DeadBandPlanTest.kt:76` and `:89`), the other 7 still passed. Restored the fix,
  reran, all 9 green.
- Did NOT add the S3 sync-failure mode to `NightReplay`/a sequence-level case that would have caught this
  bug end to end - flagged as a SHOULD FIX, left for a later pass if time allows once the four must-fixes and
  remaining SHOULD FIXes are through; `DeadBandPlanTest`'s own new cases plus the existing `DeadBandDriftTest`
  (engine, proving the underlying drift mechanism) are the coverage this pass adds instead.

### Must-fix 2 (landed) - reverting J1.2's spurious extra ring

- Verified the reviewer's central factual claim myself before touching anything, per the brief's own
  instruction: read `PhoneAlarmReceiver.recordRealAlarmFired` directly - `firedFor` is
  `Instant.ofEpochMilli(intent.getLongExtra(EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI, -1L))`, the instant the
  alarm was ARMED for (written by `schedulePhoneAlarm` at arm time), never anything derived from when the
  receiver's `onReceive` actually ran. Confirmed: ordinary AlarmManager delivery jitter genuinely cannot move
  `firedFor` by any amount, so J1.2's whole premise (widening the window to tolerate jitter) was solving a
  problem that could not occur through the mechanism it named. The ONE thing that legitimately could move a
  firing off its armed target - `pullForwardIfTooSoon`, pre-J1.1 - was already fixed by J1.1 itself.
- Reverted `firedAlarmIsWakeAlarm` to H8's original exact equality (`firedFor == morningAlarmAt`), removed the
  now-unused `WAKE_ALARM_FIRE_TOLERANCE` constant, and rewrote the doc comment to explain the revert in place
  (J1.2's own reasoning kept, immediately followed by "J2 must-fix 2 REVERTS this" and why) rather than
  deleting the history - so a future reader hitting the same "let's widen this for jitter" instinct sees why
  it was already tried and backed out.
- Found and fixed the SAME window logic duplicated in `NightReplay.kt` (the engine test harness's own mirror
  of `firedAlarmIsWakeAlarm`, kept in sync by convention since the harness cannot import app-layer code) -
  reverted it identically. Not part of the reviewer's literal finding, but leaving it un-reverted would mean
  the harness and production disagree on this exact predicate the very next time someone uses it.
- Rewrote the four J1.2 tests in `NapAlarmCountingTest.kt` to pin exact equality instead of the window (one
  minute after morningAlarmAt is now a genuine nap, not the wake alarm; the old tolerance boundary is a nap;
  one second after is already a nap; `alarmLabelFor` now labels it NAP not MORNING) rather than deleting them
  outright - same fixture instants, opposite expected outcome, so a future revert-the-revert attempt trips
  these immediately.
- Verified all four rewritten tests fail without the fix: temporarily restored the J1.2 window body in place
  (3-minute tolerance, matching the original), ran `NapAlarmCountingTest` alone, all 4 new cases failed with
  `AssertionFailedError` (`:71`, `:76`, `:83`, `:88`), the other 7 (H8/F6's own pre-existing cases) unaffected.
  Restored the fix, reran clean.
- Did not find any other test in the repo relying on the J1.2 window's behaviour (`WholeMorningSequenceTest`,
  `WarpedNightSequenceTest` both call the real `firedAlarmIsWakeAlarm` directly and only ever fire exactly at
  `morningAlarmAt` in their own fixtures, per J1.1's own guarantee that a genuine deferred morning alarm never
  lands anywhere else) - full suite confirms this, nothing else broke.
- Verify after must-fix 1 + 2 together: `BUILD SUCCESSFUL`, 539 tests (536 baseline + 2 must-fix-1 cases + 0
  net change on must-fix-2's 4-for-4 swap; the +1 beyond that traces to the concurrent UI agent's own test
  additions landing mid-session, confirmed via `git status` - nothing under `night/`/`engine/`/`alarm/`), 0
  failures, 0 errors.
- Committing must-fix 1 and must-fix 2 TOGETHER in one commit rather than two: both touch overlapping regions
  of `NightOrchestrator.kt` and I worked must-fix 2 immediately after verifying must-fix 1 green without
  committing in between (should have committed first - noting the process slip rather than silently moving
  on). From must-fix 3 onward, committing before starting the next fix, as instructed.

### Must-fix 3 (landed) - unbounded 3am nap re-ring, symmetrical fix to J1.3

- Threaded `phoneAlarmFiredFor: Instant?` through `napAlarm` into `asleepNapTarget` (WakeAlarm.kt), exactly
  mirroring how `computeWakeAlarm` already threads it into `morningAlarmAlreadyRang` for the FULL_CYCLES/
  DEADLINE_ONLY side (J1.3). `computeWakeAlarm` already had the parameter in scope (added for J1.3 in the
  morning path), so no public signature changed - only the two private functions between it and
  `asleepNapTarget`.
- `asleepNapTarget` now anchors on `laterOf(lastNapAlarmFiredAt, phoneAlarmFiredFor)` rather than
  `lastNapAlarmFiredAt` alone, reusing the same `laterOf` helper J1.3 already added (WakeAlarm.kt, private,
  file-scoped) rather than duplicating it.
- Traced why this is safe against the concern that `phoneAlarmFiredFor` can belong to the MAIN wake alarm's
  own firing, not just a nap: the existing `!latestFired.isBefore(referenceOnset)` check already requires
  the marker to be AT OR AFTER this stretch's own onset to count - and a wake alarm firing that happened
  BEFORE the owner fell back asleep (the ordinary case: alarm rings, owner is awake, returns to sleep some
  time later) is by construction before the new stretch's own onset, so it is excluded the same way an
  earlier nap's own firing already was pre-fix. No new test needed for this specific interaction since the
  existing "not before the reference onset" H2 test already exercises the identical guard on the
  lastNapAlarmFiredAt side, and the reasoning is symmetric.
- Added 2 regression cases to `ComputeWakeAlarmTest.kt`: one reproducing the owner's traced 03:05-nap-fires-
  mid-sync sequence directly (`lastNapAlarmFiredAt` null, `phoneAlarmFiredFor` 03:05, expects the target
  anchored on 03:05 + napLength = 03:25, not a re-pulled-forward 03:10), one confirming the later-of logic
  specifically (an earlier, unrelated `phoneAlarmFiredFor` must not override a later `lastNapAlarmFiredAt`).
- Verified the first new test fails without the fix (temporarily reverted `asleepNapTarget` to read
  `lastNapAlarmFiredAt` alone, ran `ComputeWakeAlarmTest`, saw `AssertionFailedError` at line 311, the other
  30 including the second new test unaffected - the second test's own expected result does not depend on
  `phoneAlarmFiredFor` mattering at all when `lastNapAlarmFiredAt` is already later, so it correctly stayed
  green through the revert; that is by design, not a gap - it pins the "later-of, no regression on the
  ordinary case" half separately from the "phoneAlarmFiredFor alone can carry it" half the first test pins).
  Restored the fix, reran clean.

### Must-fix 4 (landed) - re-arming a target that fired during the arming tick's own sync

- In `armPhoneAlarmIfNeeded` (NightOrchestrator.kt), re-sampled the clock with a fresh `nowInstant()` call
  right before the one comparison that decides whether `wakeAt` is still ahead of "now" (`shouldArmPhoneAlarm`),
  instead of reusing `now` (`decisionNow`, sampled once at tick start per FIX1). Left every other use of `now`
  in the same function (log timestamps, the J1.3 second-line-of-defence window check) untouched, exactly as
  specified - this is a real-time safety check, not a decision-consistency one, and only that one check needed
  the correction.
- Verified this cannot reopen FIX1's own bug: FIX1's whole point was that a tick's DATA and PLAN must be built
  from one consistent, non-decreasing instant across a racing pair of ticks - nothing here touches the plan
  itself, only whether an arm attempt for an ALREADY-COMPUTED plan is still valid by the time it actually runs,
  which by definition happens strictly after `decisionNow` (the sync that produced this tick's plan already
  ran before `armPhoneAlarmIfNeeded` is ever called) - so `armTimeNow >= decisionNow` always, and re-reading it
  can only make the safety check MORE conservative (refuse arming something that has since gone stale), never
  less.
- Extended `NightReplay.kt`'s own mirror the same way: `armPhoneAlarmIfNeeded`'s past-check now reads
  `PendingTick.commitAt` (this tick's own real commit instant, after its sync) instead of `PendingTick.startedAt`
  - `armings` (which tick armed something) still records `startedAt`, since that identifies WHICH tick did the
  arming, not when the safety check itself ran.
- Rewrote `TickScheduleRaceTest.kt`'s "rings twice, but never a third time" test - the one the finding named
  directly, and the fifth instance of this project's own recurring failure mode (a green test pinning a live
  defect as correct) - to expect exactly one ring instead of two, renamed to `J2 must-fix 4 replay`, and its
  docstring rewritten to explain what used to happen and why it no longer does, rather than silently changing
  the assertion with no trace of the history.
- Verified BOTH directions by hand: (1) ran the test file BEFORE touching the test's own assertions, with only
  the production+harness fix applied - it failed exactly as expected (`expected: <[..., ...]> but was: <[...]>`,
  one firing where the old assertion expected two), confirming the fix actually changes this scenario's outcome;
  (2) after rewriting the assertions to expect one ring, temporarily reverted ONLY the harness's own
  `commitAt`-vs-`startedAt` change (kept the rewritten test), reran, saw `AssertionFailedError` at the same
  line the two-firing assertion used to sit at - confirming the NEW test genuinely pins the NEW fix, not
  something that would pass regardless. Restored both, full suite green.
- Did not add a dedicated production-level unit test for `armPhoneAlarmIfNeeded`'s own fresh-clock-read line:
  it is a private function calling real Android `AlarmManager`/`Context` APIs with no Robolectric available in
  this environment (same constraint noted throughout J1.3's own entry above) - `TickScheduleRaceTest.kt`'s
  engine-level harness mirror is the closest reachable regression coverage, exactly as it already was for the
  J1.4 four-case suite this same file holds.

### The J1.5 staleness-vs-failure question (recorded per the brief's own request, not fixed)

The brief flagged that J1.5's freeze (`shouldKeepPreviousPlan`) triggers on `!outcome.syncOk`, which is true
for BOTH an outright sync failure AND merely-stale data (`resolveSyncOutcome`'s own stale-data branch, e.g. an
export file that has not changed since the last successful read) - so an ordinary first tick or two of a
perfectly healthy night, before the band has had a chance to report anything new yet, can also freeze at the
zero-data projection. Considered, not changed, for these reasons:
- The freeze (post must-fix 1) is now bounded to `wakeAt.isAfter(now)` - it can only ever hold a plan whose own
  alarm is still genuinely pending, and it releases itself the moment that stops being true, falling through to
  the ordinary re-plan path. A stale-data freeze on an early tick, before any real alarm has even been computed
  yet, is far less consequential than the dead-band case J1.5 targets: `previousPlan` at that point is
  typically the zero-data projection ITSELF (a `wakeAt` computed from no real sleep data at all), so "freezing"
  it just means one extra tick's worth of staying on the same not-yet-informed guess rather than recomputing an
  equally uninformed one from a `now` that has moved a few minutes further - not a materially worse outcome.
- Distinguishing failure from staleness would need `shouldKeepPreviousPlan` (or its caller) to see
  `SyncOutcome.failureCause`, which IS already available (non-null only on genuine failure, per
  `resolveSyncOutcome`'s own doc) - so the plumbing exists and this is a real, buildable option, not
  hypothetical. Chose not to make it because the reviewer's own must-fix 1 trace (the actual reported bug) is
  itself a FAILURE case (sync fails outright, band and phone both dead) - narrowing the freeze to
  `failureCause != null` would not have changed that scenario's outcome at all, and would add a second
  behavioural branch to a function that must-fix 1 already changed once this session, for a benefit (skipping
  a freeze on stale-but-not-failed early-night data) that the freeze's own `now`-bound already mostly covers.
- Recommend revisiting if a future night log ever shows the freeze actually firing repeatedly on stale (not
  failed) data in a way that visibly delays a plan settling - not reproduced or observed in this session, so
  left as a documented open question rather than spending scope on an unconfirmed problem.

### SHOULD FIX pass (after the four must-fixes)

**S1 (landed) - deleted the J1.3 second-line-of-defence guard in `armPhoneAlarmIfNeeded`.** Traced both
scenarios it was meant to backstop by hand: the morning-path re-ring always recomputes an EXACT match
(`wakeAt == firedFor`), which the exact-equality check right above it already catches, never reaching this
window at all; the mid-night nap re-ring (pre-must-fix-3) produces `now + minAlarmLead`, which by the time a
tick gets around to recomputing an overdue nap sits MORE than `minAlarmLead` past the original firing (tick
cadence plus sync delay push `now` well beyond the window before this ever runs) - so it missed that case too,
every time, exactly as the reviewer traced. Deleted rather than re-expressed: must-fix 3 (this session) and
J1.3's own `morningAlarmAlreadyRang` already close both re-ring shapes at their real source, so there is
nothing left for a "close but not exact" window to usefully catch, only a real false-positive risk (refusing a
legitimately close but unrelated alarm). Removed the now-unused `config: EngineConfig` parameter from
`armPhoneAlarmIfNeeded` along with it (its only remaining use). Mirrored the removal in `NightReplay.kt`'s own
harness copy. Full suite unaffected - nothing was actually relying on this guard's behaviour, confirming it
was dead weight.

**S2 (landed, folded into must-fix 1's own commit) - see the must-fix 1 entry above:** the two duplicate
`DeadBandPlanTest` cases were folded into one with a comment explaining why, and the frozen-plan-must-not-
outlive-its-own-alarm-time case was added as part of adding `now` to `shouldKeepPreviousPlan`.

**S3 (not attempted, documented tradeoff) - `NightReplay` cannot model a failed or stale sync at all.**
Considered adding a sync-failure mode to the harness so `must-fix 1`'s freeze-forever bug could be caught at
the sequence level, not just by `DeadBandPlanTest`'s own direct unit tests of `shouldKeepPreviousPlan`. Did
not attempt it: `shouldKeepPreviousPlan` is purely an APP-layer decision (`NightOrchestrator.kt`) that never
touches `computeAlarmPlan` or anything else the engine-side `NightReplay` harness currently models - giving the
harness a "sync failed" concept would mean either (a) duplicating the app-layer freeze decision into engine
test code, which the harness has deliberately avoided doing for anything outside the engine's own observable
surface (its own class doc: every rule it restates has a NAMED app-layer counterpart, kept in sync by
convention, and this one lives in a different layer entirely), or (b) inventing a new cross-layer harness this
task did not ask for. `DeadBandPlanTest`'s own 9 cases (7 original + 2 must-fix-1 additions) already give
`shouldKeepPreviousPlan` itself direct, complete branch coverage, including the exact boundary must-fix 1
closes; the sequence-level gap is real but lower-value than it looks once the pure-function coverage is this
complete. Left as a known limitation, matching how J1.5's own entry above already flagged the identical
constraint for `runNightTickLocked` as a whole.

**S4 (landed) - parametrised sweep in `AlarmSequenceReplayTest.kt`.** Added one `@ParameterizedTest` over
offsets 0-300s in 30s steps between `startNight` and `markAsleep`, asserting the armed morning target never
moves later than the first instant armed for that stretch. Anchored the "first target" read on the
`markAsleep` instant itself, not `startNight`'s own 23:00 - `startNight`'s own very first tick runs against an
EMPTY segment list (before `markAsleep` is ever called, per J1.4's own no-longer-ticks-on-mark design) and can
arm a short-lived, unrelated PROJECTED-onset target of its own in the meantime; anchoring later excludes it
cleanly via `armedTargetsAfter`'s existing `>=` filter. Verified this sweep is not vacuous: temporarily
reverted `pullForwardIfTooSoon` (WakeAlarm.kt) to its pre-J1.1 body, reran - 3 of the 11 offsets (30s, 60s, 90s)
failed, plus two of the file's own PRE-EXISTING tests, confirming the sweep catches real regressions the four
hand-picked `TickScheduleRaceTest` cases do not happen to hit on their own. Restored the fix, full suite green.
Needed `java.time.LocalDateTime` arithmetic directly (not `Instant.plusSeconds().toString()`) to build each
offset instant string - `NightReplay`'s own `instant()` helper parses a zone-less local time
(`ISO_LOCAL_DATE_TIME`), and `Instant.toString()` always renders a trailing `Z` the same parser rejects.

### Final verify (all four must-fixes plus the SHOULD FIX pass)

`JAVA_HOME=.../jdk-21.0.12.1+1 ANDROID_HOME=.../sdk ./gradlew :engine:test :app:testDebugUnitTest :app:lintDebug
:app:assembleDebug` - `BUILD SUCCESSFUL`, 0 test failures, 0 errors across every test class in both modules.
Hit the documented transient Windows `R.jar` file lock once while forcing a clean recompile to double-check for
Kotlin compiler warnings (`--rerun-tasks`, contending with the concurrent UI agent's own build) - retried per
the environment brief; the `:engine` module's own forced recompile (no R.jar/Android dependency, so unaffected
by the lock) came back completely clean, zero `w:` lines from either `compileKotlin` or `compileTestKotlin`.
Every actual (non-cached) `:app:compileDebugKotlin`/`:app:compileDebugUnitTestKotlin` execution observed
throughout this whole session likewise printed zero `w:` lines - no evidence of any compiler warning from any
change in this session's scope, in either module.

## All six J1 findings landed

J1.1 through J1.6 are all committed on `nikita/fix/overnight-hardening`: d594204, 7e73145, 12c9e51, ee9db3e,
8d1e74e, and the J1.6 commit right after this file's own update. Nothing was left undone or deferred. Two scope
notes are recorded above rather than silently expanded into: the mid-night nap side of J1.3's own stale-load
race (asleepNapTarget has no equivalent of morningAlarmAlreadyRang, noted under J1.3), and the honest relabeling
of two of J1.4's four regression tests once reverting the guard they were meant to pin left them passing
unchanged (noted under J1.4, with the real pinning tests named).

## J3 round (closing Opus review of the overnight-hardening branch, landed)

### Must-fix (verified failing without the fix, output below)

**Verified the reviewer's own reasoning before touching code.** Traced `pullForwardIfTooSoon` (WakeAlarm.kt)
myself: `minAlarmLead` defaults to 2 min, `ceilToWholeMinute` can add up to another minute, so D8's own
pull-forward target sits `decisionNow + 120..180s`. Traced `syncOrFail`'s own doc (two 60s timeouts) - a dead
sync can cost close to that much. Reviewer's numbers (decisionNow 07:41:57, pulled-forward target 07:44:00,
commit at 07:44:01) check out exactly against `pullForwardIfTooSoon`'s real arithmetic - confirmed with the
regression test below before writing any production fix.

**Fix, exactly as specified:** `armPhoneAlarmIfNeeded` (NightOrchestrator.kt) now reads
`readPhoneAlarmFiredFor(context)` fresh, right before both the exact-match early return and the
`shouldArmPhoneAlarm` call, falling back to `state.phoneAlarmFiredFor` only if the fresh read itself comes back
null (transient I/O failure, never throws per PhoneAlarmFiredStore.kt's own doc) - a defensive floor, not
required by the reviewer's own spec, added so a flaky read can never regress below the pre-J3 behaviour. `now`
(`decisionNow`) is the clock in both checks, unchanged from FIX1's own convention - the commit-time clock
re-read J2 must-fix 4 added is gone entirely.

**Regression test lives in the engine module** (`TickScheduleRaceTest.kt`'s new `J3 replay` case), not the app
module, because `armPhoneAlarmIfNeeded` is `private fun (context: Context, ...)` - no Context-based app-layer
harness exists in this codebase to drive it end-to-end (checked: no test anywhere calls `runNightTick`
directly). `NightReplay.kt` mirrors the function instead, per its own established pattern.

**Confirmed the test fails without the fix.** Temporarily reverted `NightReplay.armPhoneAlarmIfNeeded` and
`commitPendingTick` to the pre-J3 (J2 must-fix 4) shape - commit-time clock re-read (`pending.commitAt`) instead
of `pending.startedAt`, everything else unchanged - reran `TickScheduleRaceTest`, restored immediately after
capturing output:

```
TickScheduleRaceTest > J3 replay - a never-fired pull-forward target survives an expensive dead sync and still rings() FAILED
    org.opentest4j.AssertionFailedError at TickScheduleRaceTest.kt:136
...
plan FULL_CYCLES wakeAt=2026-09-21T04:44:00Z
 ==> expected: <[2026-09-21T04:44:00Z]> but was: <[]>
5 tests completed, 1 failed
```

Exactly one test failed (the new one) - the pre-existing `J2 must-fix 4 replay` test (the duplicate-ring
property) still passed under the reverted code, confirming that test was never accidentally depending on the
J3 change and still correctly pins its own property either way. The failure shows the target was computed
correctly (04:44:00Z = 07:44:00 local) but never armed (`armedTargetsAfter` returns `[]`) - the missed-ring
regression, reproduced exactly. Restored the fix, reran, `BUILD SUCCESSFUL`.

### SHOULD FIX 2 (landed, folded into the must-fix's own commit)

The previous round's S3 decision (directly above, in the J2 section) declined a sync-failure mode for
`NightReplay`, reasoning the app-layer freeze decision was out of the engine harness's own scope and branch
coverage of `shouldKeepPreviousPlan` was already complete. The reviewer measured this wrong: reverting must-fix
1 and must-fix 3 each failed unit tests only, zero sequence-level tests, despite both being live defects -
branch coverage cannot catch a guard whose INPUTS are wrong (`shouldKeepPreviousPlan` had no `now` parameter at
all pre-must-fix-1). Added `syncFails: Boolean` to `NightReplay.advanceTo`/`openApp`, a private
`shouldKeepPreviousPlan` mirror, and a `lastSyncedSegments` snapshot (updated only on a successful sync,
mirroring `resolveSyncOutcome`'s own `state.lastSegments` reuse on a failed/stale one) - this needed to be built
anyway to construct the J3 regression test itself (the dead-band freeze IS part of that scenario:
`shouldKeepPreviousPlan` correctly releases once `wakeAt` is no longer ahead of `now`, letting the recompute and
pull-forward run), so building it earned both the should-fix and the must-fix's own test in one pass. Also
added `batteryDies()` (clears the armed alarm and the ordinary tick schedule without ever firing, modeling
total power loss - BootReceiver's own precondition, not its body, since BootReceiver correctly declining to
re-arm a past target was never the bug in question).

### SHOULD FIX 3 (landed) - `AlarmSequenceReplayTest.kt` offset-0 slack

Confirmed the bug by reading, not by re-running the revert (the reviewer already measured this): `armedTargetsAfter`'s
filter is `!armedAt.isBefore(from)`, inclusive - at offset 0 `markAsleepAt` equals `startNight`'s own instant
exactly, so the start-night tick's own PROJECTED (not-yet-asleep) target is included, not excluded as the old
comment claimed, and the invariant only passed there on 15 minutes of borrowed slack. Chose "drop 0 from the
value source" over "advance the anchor by a nanosecond" (the reviewer offered either) - simpler, and offset 0
was never doing this sweep's actual job (the reviewer's own revert-and-measure showed 30s/60s/90s catch the
regression, 0 does not). Corrected the comment's false "excludes it cleanly" claim and added the nap-fixture
caveat sentence the reviewer asked for. Reran the 10 remaining offsets after the change - all still green.

### SHOULD FIX 4 (landed) - stale cross-reference in `TickScheduleRaceTest.kt`

Confirmed the reviewer's claim before rewording: `NapAlarmCountingTest.kt`'s test, formerly named `J1_2 a NAP
firing one minute after the latched morning alarm is still the wake alarm`, is now named `J2 must-fix 2 - a NAP
firing one minute after the latched morning alarm is a genuine nap, not the wake alarm` - the assertion really
was inverted when J2 must-fix 2 reverted J1.2's window. Reworded the naming note to say the window was reverted
and point at the current test names, doc-only change.

### SHOULD FIX 5 (landed, no code change, per the reviewer's own instruction) - `WakeAlarm.kt` docstring

Verified the reviewer's counter-trace against the real code before rewriting anything: awakening at 06:28,
back asleep 06:29:30 (clears `minAwakening`, so the new stretch's own `referenceOnset` is 06:29:30), the still-
pending latched morning alarm at 06:30 then fires normally - 06:30 is at-or-after 06:29:30, so
`asleepNapTarget`'s own "not before the reference onset" check does NOT exclude it, contradicting the old
docstring's claim. Confirmed the actual safety property instead: `laterOf` can only push the anchor later than
or equal to `referenceOnset`, never earlier, and the function always returns a real instant, never null - so
the worst case is a shorter nap, never a spurious or missed ring. Rewrote the docstring to claim the bound, not
the exclusion. No behaviour change, matching the reviewer's own "no code change is needed."

### SHOULD FIX 6 (landed) - "dead band" renamed to "staleness" for `shouldKeepPreviousPlan`

Interpreted "rename the function" narrowly rather than literally: `shouldKeepPreviousPlan` itself never said
"dead band" in its own name, so a full identifier rename would cascade into `NightReplay.kt`'s mirror,
`DeadBandPlanTest.kt`, and `DeadBandDriftTest.kt` (engine-level, demonstrates the underlying drift bug this
guard exists to interrupt - a different, legitimate test purpose, left untouched) without addressing the
reviewer's actual complaint. What genuinely said "dead band" as a NAME, not prose: the log event string
(`dead_band_keep_plan`) and the guard's own general-framing sentence ("The dead band this guards against:").
Renamed the log event to `stale_sync_keep_plan` (no test asserted the old string - checked first) and added a
framing correction paragraph naming the staleness-vs-fault distinction explicitly, without deleting the
original owner-traced dead-band scenario narrative right below it (which is a real, true account of one
specific cause of staleness, not the guard's only trigger - left as originally written, extended rather than
rewritten, per the "preserve existing comments" rule). Did not touch `DeadBandPlanTest.kt`'s file name or its
own header comment - out of scope for a MEDIUM-severity doc/naming fix under this round's time budget; flagged
here rather than silently expanded into.

### Nits (both landed)

- NightOrchestrator.kt:411's "already carries a real armed alarm" phrasing reworded to "still has a non-null
  `wakeAt` of its own", with an explicit forward-pointer to the S5 paragraph further down - so that paragraph
  now reads as the promised correction rather than a contradiction of the sentence just above it.
- NightOrchestrator.kt:550's arm-refusal cause string now interpolates `$now` explicitly. This became almost
  automatic once the must-fix removed the second (commit-time) clock - the comparison instant and the log
  timestamp are the same value now - but written out explicitly in the string itself anyway, per the reviewer's
  own ask, rather than left implicit.

### Final verify

`JAVA_HOME=.../jdk-21.0.12.1+1 ANDROID_HOME=.../sdk ./gradlew :engine:test :app:testDebugUnitTest :app:lintDebug
:app:assembleDebug` - `BUILD SUCCESSFUL`, 554 tests, 0 failures, 0 errors, 0 skipped (baseline 554, net +1: the
new `J3 replay` regression test, -1: `AlarmSequenceReplayTest`'s offset-0 sweep case dropped per SHOULD FIX 3).
Hit the documented transient Windows `R.jar` file lock once while forcing a `--rerun-tasks` recompile to double
-check for compiler warnings; retried per the environment brief, still locked on the retry (a concurrent holder,
not this session's own doing), so warnings were instead confirmed clean via a narrower forced recompile of just
the touched modules' own Kotlin compile tasks (`:engine:compileKotlin :engine:compileTestKotlin
:app:compileDebugKotlin :app:compileDebugUnitTestKotlin --rerun-tasks`, unaffected by the R.jar lock since none
of those four tasks touch it) - zero `w:` lines. The ordinary (non-`--rerun-tasks`) verify command above ran
clean on every invocation throughout this session, incremental or not.

## J4 round (marker J4, final cleanup pass after two independent sign-offs, same branch)

Two reviewers had already signed off on the logic itself (Opus SATISFIED, Fable SAFE TO SLEEP ON) before this
round started; nothing here was blocking. Five items: one residual ring-behaviour gap, one unnoted behaviour
change, three comments arguing against the code beside them, stale spec/decision docs, and a handful of small
ones. Worked in the order given, item 1 first, verifying after each.

### Item 1 (landed) - the residual double ring

Verified the reviewer's own reasoning before implementing: `wakeAt == previousPlan?.wakeAt` can only be true
for a RE-arm of an unchanged target (the ordinary "nothing changed this tick" case), never for D8's own
pull-forward recovery, which by construction always produces a NEW instant (`now + minAlarmLead`, never the
spent target it replaces) - confirmed by reading `pullForwardIfTooSoon` and the J3 traced sequence in
NightOrchestrator.kt directly, not just taking the claim on faith. Extracted the one-line guard into its own
pure, `internal` function (`shouldRefuseStaleRearm`), matching this codebase's own established pattern
(`shouldArmPhoneAlarm`) for making an Android-Context-bound decision unit-testable without Robolectric (which
this repo does not have - see WarpedNightSequenceTest.kt's own header for why that matters here).

Also fixed the write-side half at its source, per the task's own suggestion: `PhoneAlarmFiredStore.kt`'s
`writeInstantFile` now writes to a temp file and renames over the real one, identical to the fix
`OutOfBedNudgeStore.kt` already had. This removes the torn-read window outright rather than only tolerating it
via the read-side guard - both are in, since the read-side guard is still needed for the "read lands just
before the write, not mid-write" half of the race, which an atomic rename cannot close on its own.

Regression test: added `J4 replay` to `TickScheduleRaceTest.kt` (engine module), reusing the existing
`nightOwnerTraced()` lattice and NightReplay's own documented commit-first tie-break to model the race
deterministically - a tick's commit landing at the EXACT same instant its own already-armed target fires.
Verified failing without the fix by hand: commented out the new guard line in `NightReplay.kt`'s own
`armPhoneAlarmIfNeeded` mirror, ran `:engine:test --tests TickScheduleRaceTest`, got exactly one failure
(`J4 replay`, `armings` contained a stale re-arm entry the assertion says must not exist), all six other tests
in the file still green, restored the line immediately. Also added four direct unit tests of
`shouldRefuseStaleRearm` itself to `PhoneAlarmArmingTest.kt` (no Context needed, pure function).

Decision: `nowInstant()`, not the tick's own `decisionNow`, is the correct clock to compare against here - it
is deliberately a SECOND, independent real-clock read at the exact point arming runs, mirroring what J2
must-fix 4 tried and J3 had to correct (re-reading the clock at the wrong SCOPE, comparing against every
decision in the tick, not just this one guard). Scoping the fresh clock read to only this one comparison, and
only when the target is unchanged, is what keeps it from reopening J3's own regression.

### Item 2 (landed) - the unnoted behaviour change: decided to keep the nudge

Traced the reviewer's ordering by hand against the current code (post-J3) and confirmed it: J3 made the
fired-marker match at `wakeAt == phoneAlarmFiredForNow` read LIVE, which is correct for the ring-count property
it exists for, but that same live read also flows into `napAlarmArmed`, the ONLY input
`cancelNudgeIfSupersededByNap`'s guard reads. For a nap that fires DURING its own tick's sync (the ordinary
shape for a mid-night rule 7 nap under real sync latency, not an edge case), the match now resolves to true at
commit time, and `napSupersedesPendingNudge` reads that as "a fresh nap was armed this tick" - cancelling the
SAME nap's own fresh nudge, armed by the SAME firing the match just confirmed.

Decision: the nudge must survive. Reasoning, in order of weight:
1. The out-of-bed nudge's whole job (D4) is to get the owner out of bed when they are awake and not getting up
   after an alarm. Losing it silently after every mid-night nap that happens to fire mid-sync is a real,
   recurring loss of function, not a cosmetic one - and mid-sync firings are not rare on a real (non-simulated)
   night, where a sync can cost real seconds to tens of seconds.
2. The thing being "superseded" is not a stale nudge from an earlier, now-irrelevant alarm (H7.2's actual
   intended case, which this fix does not touch) - it is that SAME nap's own nudge, which the receiver had
   already armed moments before, at fire time. There is nothing left here worth trading away for.
3. `armPhoneAlarmIfNeeded`'s own return value has exactly one reader (checked directly - grepped for
   `napAlarmArmed` and for calls to the function), so narrowing what "true" means costs nothing elsewhere.

Implemented as: the marker-match branch now returns `false` instead of `true` - still never re-arms (unchanged,
an early return before `schedulePhoneAlarm` either way), but no longer counts as a fresh arm for the nudge
guard. Updated the FIX4 docstring on `armPhoneAlarmIfNeeded` and the H7.2 docstring on
`napSupersedesPendingNudge` to state the corrected contract explicitly, per the task's own instruction not to
leave a kept behaviour implicit if going the other way - the same applies here in reverse: the CHANGED
behaviour, and why, is now explicit in both places a future reader would check.

Regression test: added `J4 nudge replay` to `TickScheduleRaceTest.kt`, extending `NightReplay.kt` to model
`pendingNudgeAt` and a reimplementation of `napSupersedesPendingNudge`/`cancelNudgeIfSupersededByNap` (the
engine module cannot import the app module's `internal` originals - same constraint as every other mirror in
that file). First attempt used too wide an `advanceTo` window and picked up two more ticks than intended,
applying the injected sync duration to three ticks instead of one and producing a second, unrelated nap target
- caught by the trace output in the assertion failure message, narrowed the window to land just past the
intended tick's own commit and before the next scheduled one (same technique `J2 must-fix 4 replay` already
uses). Verified failing without the fix by hand: temporarily made the marker-match branch return `true` again
(pre-J4 behaviour) in the `NightReplay.kt` mirror, ran the test, got exactly the expected failure
(`pendingNudgeAt` was `null`, expected the nap's own fresh nudge instant), `J2 must-fix 4 replay` in the same
file still green (confirming the ring-count property is independent of this fix), restored the fix immediately.

### Item 3 (landed) - three comments corrected in place

a. NightOrchestrator.kt's J2-must-fix-4 KDoc paragraph (near the top of `armPhoneAlarmIfNeeded`'s own doc)
   marked SUPERSEDED BY J3 in place, not deleted - it still describes reasoning later paragraphs refer back to.
b. The "kept, not deleted" sentence corrected: it named the wrong paragraph ("below" when it meant the short
   mention above, now marked superseded per (a)), and asserted a roughly twenty-line call-site comment block
   (with its own worked trace) was kept when J3's own diff (checked directly with `git show`) shows it was in
   fact deleted outright. Corrected the direction and said plainly that the paragraph immediately following is
   a fresh recap written for J3's own doc, not the original text carried forward.
c. The `stale_sync_keep_plan` log line's cause string reworded from "already has a real alarm armed" (asserting
   something `shouldKeepPreviousPlan`'s own S5 doc says this guard cannot know) to "still pending (unfired,
   still ahead of now)" - accurate on a night where the exact-alarm permission was revoked and arming never
   actually succeeded.

### Item 4 (landed) - specs and decisions.md

`docs/engine-spec.md`: corrected the `minAlarmLead` table row, the "Pull-forward rule" paragraph, and the
"Deadline 30 s ahead" worked example - all three stated or relied on the pre-J1.1 rule (pulls forward anything
within `minAlarmLead` of `now`) rather than the current one (only a target at or before `now`). Read
`pullForwardIfTooSoon` directly to confirm the corrected wording before writing it, per the task's own
instruction not to infer.

`docs/decisions.md`: added a new "Overnight hardening" section (J1-J4) with one entry per decision a future
reader must not silently reverse: the pull-forward scope (J1.1), the attribution window tried and reverted
(J1.2/J2 must-fix 2), both spent-target checks keying on the later of their own marker and `phoneAlarmFiredFor`
(J1.3/J2 must-fix 3), the stale-sync freeze and its bound (J1.5/J2 must-fix 1/S6 rename), the band query
lookback (J1.6), and this round's own two J4 decisions (the stale-rearm guard, and keeping the nudge). Kept
each entry to the decision and its reason, in the style of the entries already there, and did not touch any
existing entry's own wording (H8's section and earlier are untouched).

### Item 5 (landed, all three)

- `NightReplay.kt`'s dangling `[armTimeNow]` KDoc link in the J3 paragraph reworded to plain text. Note: this
  round's own item-1 fix happens to add a genuinely new parameter named `armTimeNow` to the SAME function for
  an unrelated reason, which would have made the old dangling reference silently "resolve" again - to the wrong
  symbol, with the wrong historical meaning attached. Worth flagging: a dangling link and a wrongly-resolving
  link look identical in an IDE's "no error" sense; only reading the prose catches the second kind.
- `AlarmSequenceReplayTest.kt`: added a note before the offset sweep's `@ValueSource` stating that only
  30/60/90 were confirmed by hand (revert-and-rerun) to fail without J1.1, and that the remaining seven offsets
  (120-300) are broader lattice-phase coverage of the same "never moves later" invariant, not individually
  confirmed regression cases - so a future reader does not read the whole ten-value sweep as equally strong
  evidence for the pull-forward rule specifically.
- `DeadBandPlanTest.kt` renamed to `StaleSyncPlanTest.kt` (`git mv`, class name and header comment updated to
  match). Decided this was a clean rename, unlike the broader "dead band" terminology rename SHOULD FIX 6
  explicitly declined earlier the same night: nothing outside the file references the class by identifier
  (checked directly with grep across all `.kt` files), so nothing cascades. The two historical
  `AUTONOMOUS_DECISIONS_09_21_2026*.md` files still say `DeadBandPlanTest` in their own past-tense entries -
  left untouched, since those are point-in-time records of what was decided when, not living docs (same
  principle as decisions.md's own superseded-in-place sections, applied to a file name instead of a rule).

### Final verify

`JAVA_HOME=.../jdk-21.0.12.1+1 ANDROID_HOME=.../sdk ./gradlew :engine:test :app:testDebugUnitTest :app:lintDebug
:app:assembleDebug` - `BUILD SUCCESSFUL`, 560 tests, 0 failures, 0 errors, 0 skipped (baseline 554, net +6: four
new `shouldRefuseStaleRearm` unit tests in `PhoneAlarmArmingTest.kt`, plus `J4 replay` and `J4 nudge replay` in
`TickScheduleRaceTest.kt`). No `R.jar` lock encountered this round. Zero Kotlin compiler warnings on every
invocation, including a standalone `:app:compileDebugKotlin` run before the first full verify.
