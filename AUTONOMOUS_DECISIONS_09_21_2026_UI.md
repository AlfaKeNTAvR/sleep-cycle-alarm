# Autonomous decisions - UI fixes for commit f90012e (09/21/2026)

Scope: clearing the Opus review findings on f90012e ("Show which alarm is coming on the night screen, and
why"). Owned files only: `app/src/main/kotlin/.../ui/`, `app/src/main/res/`, `app/src/test/kotlin/.../ui/`,
`README.md`, `docs/`. `AUTONOMOUS_DECISIONS_09_21_2026.md` already existed (another agent's file), hence this
`_UI` suffix per the task's own instruction.

## Must-fix 3's deadline decision

The review flagged that state A (`GoingToBedOrAsleep`) lost its only visible mention of the deadline when the
old timeline row was removed, leaving it buried mid-sentence in the engine's `reason` text - and the owner
said he uses the deadline to decide whether to go back to sleep.

**Decision: restore it as a plain caption line**, shown only in the ordinary sleep-length subtitle case
(`NightSubtitle.SLEEP_LENGTH`). Not shown when:
- the alarm already rang (`NightSubtitle.ALREADY_RANG`) - the deadline no longer matters once the night has
  moved past it into the already-rang state.
- the mode is deadline-only (`NightSubtitle.DEADLINE_ONLY`) - the subtitle already says "Alarm rings at the
  deadline", and the hero number above it already *is* the deadline, so naming it again would be redundant.

Implementation: `buildGoingToBedContent` dropped its unused `settings: NightSettings` and `view:
NightEngineView` parameters (must-fix 3's own dead-parameter fix) and gained a narrower `deadline: Instant?`
parameter instead - matching how `buildWokeUpContent` already takes `deadline` directly rather than the whole
settings object. The call site in `BuildNightUiState.kt` now passes `state.settings.deadline`. New string:
`night_a_deadline_caption` = "Deadline %1$s", rendered via `HeroNumeral`'s existing (previously unused)
`detail` slot.

This is the one judgement call in the must-fix list; flagging it here as instructed in case the owner would
rather the deadline stay dropped, or worded differently, or shown in more states.

## Should-fix 7's design: NightSubtitle enum

Went further than a minimal fix: rather than adding a `NightSubtitle` enum alongside the existing
`isDeadlineOnly: Boolean` and `alarmAlreadyRang: Boolean` fields (which would have left the "both true at
once" precedence question implicit in two places), `isDeadlineOnly` was removed entirely and replaced by the
enum, computed once in `buildGoingToBedContent` with an explicit precedence (`alarmAlreadyRang` wins). Kept
`alarmAlreadyRang` as its own field since it also drives should-fix 5's reason-text suppression and is
referenced by the existing H8 MISSING_TIME_LABEL logic and by tests - it's the source the enum is *derived*
from, not a second independent flag re-checked by the UI, so this doesn't reintroduce the bug being fixed.

## Should-fix 5's suppression point

Suppressed `reasonText` in the builder (`buildGoingToBedContent`: `reasonText = if (alarmAlreadyRang) null
else plan.reason`) rather than in the composable. This meant widening `NightScreenContent.GoingToBedOrAsleep
.reasonText` and `AlarmModeHeader`'s own `reasonText` parameter to nullable, with `AlarmModeHeader` leaving the
second line out entirely when null (not rendering it blank). `NapAsleep.reasonText` and `WokeUp.reasonText`
stayed non-null `String` - they have no already-rang concept, so there is nothing to suppress there. Did NOT
touch the engine's `describePlan` (another agent's file), per the explicit instruction.

## modeLabel nullability spread to NapAsleep too

Must-fix 1 only requires `GoingToBedOrAsleep.modeLabel` and `WokeUp.modeLabel` to go nullable (those are the
two states that can carry a null `wakeAt`). `NapAsleep.modeLabel` was made nullable too, purely because it
shares `alarmModeLabel`'s single, now-uniformly-nullable return type - not because a null value is expected to
reach `NapAsleep` in practice (its own KDoc still says why: this state is only reached with a genuine fresh nap
onset that has an armed `wakeAt`). This is a defensive consequence of one shared function, not a new state to
design for; flagging it in case the reviewer would rather see an unsafe `!!`/`checkNotNull` there instead to
keep the type honest about what's "supposed" to happen. Kept it nullable: a crash on an unexpected null is
worse than falling back to "no alarm armed" wording.

## Should-fix 6, 9, 10: done as scoped

- **6**: added a note to `NightUiState.kt`'s sealed-interface doc marking `reasonText` (on all three states) as
  the deliberate exception to the "wording lives in strings.xml" rule. Did not touch the engine's own KDoc in
  `Plan.kt` (`describePlan`) - flagging for the other agent/owner per the task instructions, since that file is
  outside this agent's ownership.
- **9**: rewrote `docs/design.md`'s states A and B (removed the dead timeline references, added the mode
  header) and `README.md`'s simulated-night walkthrough steps 8-10 (the timeline-watching instructions are
  replaced with watching the "of sleep" subtitle count down a cycle each round, plus a note about the mode
  header text appearing).
- **10**: rewrote `MISSING_TIME_LABEL`'s KDoc to describe the two defensive fallback branches that still reach
  it (`buildGoingToBedContent`'s `else`, `buildNapAsleepContent`'s `?:`), and to explicitly say the H8
  already-rang case and the must-fix-1 "no alarm armed" case no longer reach this dash.

## Left alone (other agent's files, or explicitly out of scope)

- `AlarmLabel.kt` (`app/src/main/kotlin/.../alarm/`) - not under `ui/`, not edited; only consumed
  (`alarmLabelFor`, `alarmLabelNameRes`, the `AlarmLabel` enum itself), no changes needed to it.
- Engine's `describePlan` KDoc in `Plan.kt` and its hardcoded-English reason strings - explicitly the other
  agent's file per the task instructions (should-fix 6's own note).
- Finding 4 (two-minute pull-forward mislabelling) and finding 8 (deleting `listWakeOptions`/`WakeOption`/
  `NightEngineView.wakeOptions`) - explicitly out of scope, not touched.
- `:engine:test` currently fails on `ComputeWakeAlarmTest > J1_3 ...` (2 failures) - this is inside the other
  agent's own concurrent, uncommitted edit to `WakeAlarm.kt` (git status shows it and `ComputeWakeAlarmTest.kt`
  both modified, mid-flight, presumably J1.3 in progress). Confirmed via `git log` that no new commits landed
  underneath this work between verify runs - it's a live, uncommitted state, not something this agent broke or
  should fix. All UI-owned verification (`:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`) is
  green on its own.

## Round 2 (09/21/2026) - second Opus review, must-fix 1 and 2, should-fix 3/4/5/7/8/9

New review pass on commits `32c03e0`/`67394d2` (this round 1 work), verdict NOT SATISFIED on two must-fixes.
Same ownership split as round 1. Commits: `139a92f` (must-fix 2), `6c6b822` (must-fix 1 + should-fix 3/4/5/8/9),
`fe5c357` (docs, should-fix 7 partial).

### Must-fix 2: corrected the round 1 deadline-caption reasoning

Round 1's decision above ("the deadline no longer matters once the night has moved past it into the
already-rang state") was wrong. `NightSubtitle.ALREADY_RANG` is only reachable while the deadline is still
ahead - a passed deadline sends the night to `FINISHED` first (the engine's own tick order checks that before
anything else). So ALREADY_RANG and a live deadline can coexist, and that is exactly the moment ("can I doze
another 20 minutes") the caption matters most. Fixed `BuildNightScreenContent.kt`'s `deadlineTimeLabel` to
suppress only for `DEADLINE_ONLY` (the one case where the caption is genuinely redundant - the subtitle and
hero number already say the deadline). Correcting this reasoning here, per the review's own instruction, rather
than leaving the round 1 entry silently wrong.

### Must-fix 1: the out-of-bed nudge, plumbed and rendered

The review traced a concrete false reading: any real alarm firing (morning or nap) arms the out-of-bed nudge
unconditionally, 15 minutes out, persisted in `OutOfBedNudgeStore.kt` - but the night screen's whole
mode-label derivation only ever asked "is a *plan* alarm armed" (`AlarmPlan.wakeAt`), never "is the nudge
armed", so every F5/H8 "no alarm armed" reading co-occurred with a real, ticking alarm the screen said nothing
about.

The plumbing did NOT need any file outside this agent's ownership - `OutOfBedNudgeStore.kt`'s
`readOutOfBedNudgePendingAt` was already a plain public read, so the fallback the task offered (dropping the
header line entirely) was not needed:

- `NightViewModel.kt` (owned, `ui/`): added a `pendingOutOfBedNudgeAt: MutableStateFlow<Instant?>`, re-read on
  the same ticker cadence as `now` ([watchScreenVisibilityTicker]) and once more in `refreshStatuses()` on
  resume - the nudge is armed by `PhoneAlarmReceiver` in the background, outside every Flow this ViewModel
  already observes, so polling is the only way the screen finds out (the same pattern
  `currentPermissionStatus`/`isGadgetbridgeInstalled` already use for other Android-only reads). Threading it
  through the top-level `combine()` needed a small trick: kotlinx.coroutines' `combine` only has fixed-arity
  overloads up to 5 flows, and both `CoreInputs`'s and the outer join's own combine calls were already at 5, so
  it rides bundled with `errorMessage` as a `Pair` rather than becoming a 6th argument or a renamed data class.
- `BuildUiState.kt` / `BuildNightUiState.kt` (owned, `ui/state/`): new `pendingOutOfBedNudgeAt: Instant? = null`
  parameter threaded straight through, default null so every existing caller (including every test) keeps
  compiling unchanged.
- `BuildNightScreenContent.kt`: new `applyPendingOutOfBedNudge(content, pendingOutOfBedNudgeAt, now, zone)`,
  applied once after `buildNightUiState`'s own `when` block rather than threaded into every content builder -
  only `GoingToBedOrAsleep` and `WokeUp` can ever reach a null `modeLabel` in the first place (`NapAsleep`
  cannot, should-fix 5 pins this), and a stale/past `pendingOutOfBedNudgeAt` is explicitly rejected
  (`isAfter(now)`) so a nudge that already fired or was cancelled can never resurrect a dead alarm on screen.
- `NightUiState.kt`: new `modeLabelTimeLabel: String? = null` field on both affected content variants.
- `AlarmModeHeader.kt` + new string `night_alarm_mode_header_with_time` ("%1$s at %2$s"): the header now reads
  "Out-of-bed nudge at 07:15" instead of "No alarm armed" whenever the override applies - a bare label name
  would not have been enough, since (unlike the morning/nap alarm) the nudge's time is not shown anywhere else
  on the screen.

**What the header now says, in plain words**: in the nudge-pending case (e.g. the morning alarm rang at 07:00,
the nudge is armed for 07:15, the band confirms AWAKE at 07:05 with nothing left for the sliding nap to arm),
the header reads **"Out-of-bed nudge at 07:15"** instead of the old, false **"No alarm armed"**. The
already-rang case between the ring and the pre-nudge check (band still ASLEEP) gets the same treatment. Once
the nudge is cancelled or has fired (a stale/past `pendingOutOfBedNudgeAt`, or none persisted at all), the
header correctly falls back to "No alarm armed" again.

### Should-fix 3, 4, 5: strengthened the tests must-fix 1 and 2 depend on

Done as scoped: rewrote the narrating test to the H8 sliding-nap case (mode NAP, `wakeAt == morningAlarmAt`,
band AWAKE, asserts `MORNING`); added an end-to-end `buildNightUiState` test with a real deadline reaching the
already-rang caption; added a test pinning `NapAsleep.modeLabel == NAP` for the ASLEEP-with-armed-`wakeAt`
case. Also added direct tests for the must-fix 1 override itself (pending nudge in states A and C, and a
stale/past nudge NOT resurrecting the label) since should-fix 3/4/5 were about existing gaps, not the new code
path.

### Should-fix 8: reworded the nap-card description

Changed `night_c_nap_description` from "Alarm rings 20 min after you fall asleep, before deep sleep." (present
tense, read as a running promise) to "If you fall back asleep, a nap alarm rings 20 min later, before deep
sleep." (conditional). Reworded rather than suppressed: the card shows regardless of whether a nap is currently
armed (it is the same card either way), and the explanation is still useful context - it just needed to stop
asserting something happening right now.

### Should-fix 9: added `NightSubtitle.UNKNOWN`

The defensive branch (partially restored state file: `wakeAt` and `morningAlarmAt` both null) used to fall
through to `SLEEP_LENGTH`'s "X h of sleep" caption, next to the dash and "no alarm armed" - a promise nothing
backs up. Added a fourth enum case that renders no subtitle line at all, the same "assert nothing rather than
something false" choice `MISSING_TIME_LABEL` already makes for the hero time in this exact branch.

### Should-fix 7: wording fixed, the "step after 11" addition NOT done - flagging why

Fixed steps 9/10's wait-time wording exactly as specified (the debug options are ignored by
`resolveEngineConfig`, so a round needs roughly an hour of virtual sleep / a minute of real time at 60x, not
"a few seconds").

Did NOT add the requested step after 11 ("presses Stop and returns to the Night screen, so the walkthrough
actually reaches the already-rang header and the restored deadline caption"). Traced the engine's own gating
before writing it (`WakeAlarm.kt`'s `computeWakeAlarm`, read-only - not this agent's file to edit) and found
the review's assumption does not hold for the walkthrough as currently written: `computeWakeAlarm` explicitly
exempts `PlanRule.NAP` from the `morningAlarmAlreadyRang` check (`if (rule != PlanRule.NAP && ...)`), so a nap
ring - which is the *only* kind of ring this walkthrough's chosen path ever lets happen (steps 9-10 deliberately
toggle Asleep off/on to burn the budget into nap territory *before* the FULL_CYCLES/DEADLINE_ONLY morning alarm
ever reaches its own real target) - cannot produce `GoingToBedOrAsleep.alarmAlreadyRang`. Tapping Stop after a
nap ring lands back on a nap-related state, not state A's already-rang header.

Reaching that header (and the deadline caption alongside it) needs the FULL_CYCLES/DEADLINE_ONLY alarm to ring
for real while the band still reads ASLEEP - i.e. NOT toggling Asleep off in steps 9-10, letting the alarm's
real 4.5 h target arrive instead. That is a materially different walkthrough branch (different pacing, and it
would need its own deadline set up front to also exercise the caption), not a one-line insertion after step
11. Given I cannot run the app to verify a rewritten walkthrough on-device (phone disconnected, no adb per this
task's constraints), and given writing an unverified doc change is worse than leaving a gap, I left this open
for the owner: either (a) add a short second walkthrough branch/offshoot for this specific case, or (b) decide
the existing nap-only path is enough and the already-rang header is adequately covered by the unit tests
(should-fix 4 above) instead of the manual walkthrough.

### Out of scope, not attempted (per the task)

- Finding 6 (`plan.wakeAt` rendered as the coming alarm with no confirmation arming actually succeeded) -
  lives in `NightOrchestrator.kt`, the other agent's file.
- Finding 10 (state C forces the alarm time label to null on an H8 sliding nap, showing no time at all) -
  pre-existing, left for later per the task's own instruction.

### Verify

Full verify (`:engine:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`) was BUILD SUCCESSFUL with
530 total tests (398 app + 132 engine), zero failures, zero Kotlin compiler warnings, once the other agent's
concurrent `WakeAlarm.kt`/`NightReplay.kt` work had landed cleanly (it was mid-flight with 6 failing engine
tests at the start of this session - not this agent's files, not touched).

## Round 3 (09/21/2026) - third Opus review, verdict SATISFIED, clearing the should-fix list

Verdict was SATISFIED - all four should-fixes are non-blocking but real and cheap per the task. Baseline before
this round: 536 tests green (`./gradlew :engine:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`).
Same ownership split as rounds 1/2. Worked in the order the task specified (fix 2 first - the only one with a
runtime cost), verified green after each.

### Fix 2: the ticker's file read moved off the main thread

`readOutOfBedNudgePendingAt` (`OutOfBedNudgeStore.kt`) is a synchronous `File.exists()` + `readText()`, called
from `NightViewModel.watchScreenVisibilityTicker`'s 500 ms-floored loop and once more from `refreshStatuses()`
on resume - both run on `viewModelScope`, i.e. `Dispatchers.Main.immediate`. At the debug screen's 600x speed
that is two blocking main-thread disk reads a second, on every screen, not just the Night screen.

Extracted one `private suspend fun refreshPendingOutOfBedNudge()` that wraps the read in
`withContext(Dispatchers.IO)`, called from both sites: directly in the ticker's own coroutine, and via
`viewModelScope.launch { }` from `refreshStatuses()` (which is not itself suspend - it is called from
`onResumed()`, a plain Activity-lifecycle callback). `resolveInitialScreen()` (the other caller of
`refreshStatuses()`) does not wait for this launch to finish: the screen it resolves does not depend on
`pendingOutOfBedNudgeAt`, so firing it off async is safe.

Also deleted the KDoc's "the same pattern `currentPermissionStatus`/`isGadgetbridgeInstalled` already use"
claim on `pendingOutOfBedNudgeAt` (the review's own note: it was wrong - those two are PackageManager/
AlarmManager queries run only on resume, never on a ticker) and replaced it with the actual reasoning: a disk
read, on a ticker, needs `Dispatchers.IO` in a way a one-shot system-service query does not.

Verify: BUILD SUCCESSFUL, 536 tests, 0 failures, 0 Kotlin compiler warnings.

### Fix 3: the out-of-bed nudge was one deletable argument from reverting forever

`pendingOutOfBedNudgeAt` defaulted to `null` in both `buildUiState` and `buildNightUiState`. Deleting the
argument at either call site (`NightViewModel.kt` or `BuildUiState.kt`'s own call into `buildNightUiState`)
compiled clean and left all 536 tests green, silently reverting the header to "No alarm armed" forever - no
test crossed the `buildUiState` hop the way `EndNightFlowWiringTest` already does for `endingNight`.

Two changes, per the task's own two-part ask:
- Removed the default value on both `pendingOutOfBedNudgeAt` parameters (`BuildUiState.kt`,
  `BuildNightUiState.kt`). A dropped argument is now a compile error, not something only a test can catch.
  Updated every call site that relied on the default: 4 direct calls in `BuildNightUiStateTest.kt` (now pass
  `pendingOutOfBedNudgeAt = null` explicitly) and `BuildUiStateTest.kt`'s own `state()` test helper (gained a
  `pendingOutOfBedNudgeAt: String? = null` parameter, kept defaulted since it's a test helper, not the
  production function).
- Added `OutOfBedNudgeWiringTest` to `BuildUiStateTest.kt`, next to `EndNightFlowWiringTest`, following the
  same pattern: goes through `buildUiState` itself (not `buildNightUiState` directly), asserts a pending nudge
  reaches `uiState.night.content.modeLabel`/`modeLabelTimeLabel`.

Verify: BUILD SUCCESSFUL, 539 tests (536 baseline + 1 new test + 2 from the other agent's concurrent, still
uncommitted `NightOrchestrator.kt`/`DeadBandPlanTest.kt` work), 0 failures, 0 Kotlin compiler warnings. One
`:app:lintAnalyzeDebug` run failed mid-session with an internal lint/PSI crash while parsing the other agent's
in-progress `NightOrchestrator.kt` edit ("AsyncExecutionService... must not return null" - a Windows-lock-style
race, not a real finding), and a `:app:testDebugUnitTest` run separately caught 3 `NapAlarmCountingTest`
failures in the same concurrently-edited file's dependency graph (`app/src/test/kotlin/.../night/`, not owned
by this agent) - both cleared on retry with no changes on this agent's side, confirming they were transient
races against the other agent's live, uncommitted edit, not caused by this fix.

### Fix 4: the silent swallow in clearOutOfBedNudgePendingAt

`clearOutOfBedNudgePendingAt` ignored `File.delete()`'s return value, unlike its sibling
`saveOutOfBedNudgePendingAt`'s own check-and-log. Now load-bearing per the review: every OTHER stale-nudge path
self-heals through `applyPendingOutOfBedNudge`'s `isAfter(now)` guard, but a failed delete on the nap-supersede
(`NightOrchestrator.kt`) or pre-check-cancel (`OutOfBedPreNudgeCheck.kt`) paths leaves a future instant on disk
with no alarm behind it, so `isAfter(now)` stays true and the header keeps asserting a nudge time until the
night ends.

All three call sites (`NightController.kt`, `PhoneAlarmReceiver.kt`, `NightOrchestrator.kt`,
`OutOfBedPreNudgeCheck.kt` - four, not three) are outside this agent's ownership for this round, so per the
task's own fallback instruction the check-and-log moved into `OutOfBedNudgeStore.kt` itself:
`clearOutOfBedNudgePendingAt` now returns `Boolean` (true if the file is gone, whether it was deleted just now
or was already absent) and logs an error naming the consequence ("a stale future instant may linger on screen
until the night ends") when `delete()` returns false. Every call site still ignores the return value - Kotlin
allows discarding a non-Unit expression statement with no warning, so this compiles unchanged and needed no
edits outside this agent's ownership.

**Flagging for the file owners**: the store can log the failure, but only a call site can actually react to
it (retry, or surface something to the owner) - that decision still belongs to whoever owns
`NightController.kt`/`PhoneAlarmReceiver.kt`/`NightOrchestrator.kt`/`OutOfBedPreNudgeCheck.kt`.

No test added: `OutOfBedNudgeStore.kt` takes `Context` directly (`context.filesDir`) and this codebase has no
Robolectric (or other Android test harness) dependency - confirmed via `WarpedNightSequenceTest.kt`'s and
`ClockWarpTransitionsTest.kt`'s own file-header notes, both already documenting this exact gap for other
Context-based stores. Verified by code inspection instead, matching the established pattern.

### Nit: atomic write for the same file the UI now reads on a ticker

`saveOutOfBedNudgePendingAt` used plain truncate-then-write (`File.writeText`); the review noted the UI now
reads this file every ticker cadence (fix 2 above), so a read landing mid-write sees a truncated, unparsable
file and logs a spurious "failed to read... treating as absent" error - harmless (self-heals next tick) but
noisy in logcat. Judged this trivial and fixed it: write to a sibling `.tmp` file, then `File.renameTo` over
the real one, both within the same app-private directory so the rename is a single atomic filesystem operation
on Android's Linux kernel (unlike Windows, `rename()` on Linux replaces the target atomically - this path only
ever runs on-device, never in a JVM unit test, so no Windows caveat applies). Removes the race window entirely
rather than continuing to just tolerate it.

### Nit: AlarmModeHeader.kt's unenforced time-label invariant - skipped, not trivial

The review noted `AlarmModeHeader.kt:41-46` formats any label with a time when one is present, but nothing in
the type system stops a future caller from passing a time alongside a non-nudge label the KDoc says never
carries one. Skipped: enforcing this needs either a sealed type that pairs each `AlarmLabel` with its own
optional time (a real signature change touching every content builder) or a runtime `require()` (only catches
it via a test, not the compiler) - neither is a one-line fix, so it did not meet the task's own "trivial" bar.

### Nit: BuildNightScreenContent.kt:76's overstated comment - fixed

"the one moment this caption matters most" overstated how often the already-rang-plus-live-deadline overlap is
actually reached: fix 5's own investigation below confirms it needs the band to under-count a cycle, which the
synthetic debug simulator never does - genuinely rare on a real night too, not "the one moment". Reworded to
say what is actually true: this is the moment the caption is most useful, on the (rare) nights that reach it.

### Fix 1: the header now also outraces an armed plan alarm, not just a null modeLabel

`applyPendingOutOfBedNudge` only ever fired when `modeLabel == null`, so a pending nudge stayed invisible
whenever ANY plan alarm was armed - even one that rings after the nudge. Traced sequence from the task: deadline
07:30, 4.5 h picked, onset 23:00, morning alarm fires at 03:30; owner taps Stop and stays in bed; band keeps
reading ASLEEP; plan becomes DEADLINE_ONLY (`wakeAt` = 07:30, label MORNING); the same firing armed a nudge for
03:45. Neither the nap-supersede path (no nap armed) nor the pre-nudge check (fails open on a stale/failed
sync) intervened, so the header showed "Morning alarm / 07:30" for the whole 15 minutes even though the nudge,
not the 07:30 alarm, actually rang next - wrong per `AlarmModeHeader`'s own contract ("which alarm is coming
next", `AlarmModeHeader.kt:27`).

Fix: `applyPendingOutOfBedNudge` gained a `planWakeAt: Instant?` parameter (`plan.wakeAt`, passed from
`buildNightUiState`) and now overrides whenever `planWakeAt == null` (the old condition, unchanged) OR
`planWakeAt.isAfter(pendingOutOfBedNudgeAt)` - i.e. whenever the nudge genuinely rings first. Pinned both
directions in `BuildNightUiStateTest.kt`: `the pending nudge overrides an armed plan alarm when the nudge
rings first` (the task's own traced sequence, DEADLINE_ONLY wakeAt 07:30 vs nudge 03:45) and `an armed plan
alarm wins over the pending nudge when the plan alarm rings first` (wakeAt 03:30 vs a later nudge at 07:45,
modeLabel/modeLabelTimeLabel must stay untouched).

Verify: BUILD SUCCESSFUL, 543 tests (539 + 2 new should-fix-1 tests + 2 more from the other agent's concurrent
work), 0 failures, 0 Kotlin compiler warnings. Two more transient races against the same concurrent,
uncommitted `engine/`/`night/` work this session: one `:app:lintAnalyzeDebug` internal PSI crash and one
`:engine:compileKotlin` type-mismatch in `WakeAlarm.kt` (a file mid-edit by the other agent, not touched by
this agent) - both cleared on a bare retry with no changes on this agent's side.
