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

## All six J1 findings landed

J1.1 through J1.6 are all committed on `nikita/fix/overnight-hardening`: d594204, 7e73145, 12c9e51, ee9db3e,
8d1e74e, and the J1.6 commit right after this file's own update. Nothing was left undone or deferred. Two scope
notes are recorded above rather than silently expanded into: the mid-night nap side of J1.3's own stale-load
race (asleepNapTarget has no equivalent of morningAlarmAlreadyRang, noted under J1.3), and the honest relabeling
of two of J1.4's four regression tests once reverting the guard they were meant to pin left them passing
unchanged (noted under J1.4, with the real pinning tests named).
