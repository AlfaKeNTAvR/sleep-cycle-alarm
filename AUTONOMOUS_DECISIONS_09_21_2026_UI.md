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
