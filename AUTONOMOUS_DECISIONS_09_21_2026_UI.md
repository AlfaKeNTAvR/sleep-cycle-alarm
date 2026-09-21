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
