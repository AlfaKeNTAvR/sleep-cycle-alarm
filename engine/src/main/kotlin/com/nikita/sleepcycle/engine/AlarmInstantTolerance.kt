package com.nikita.sleepcycle.engine

import java.time.Duration
import java.time.Instant

/**
 * M3 (owner-reported, 2026-09-21): the owner's own objection to M2's fix, verbatim - "This is very fragile.
 * You should have done a subtraction and checking whether the difference was bigger than a certain number."
 * M2 closed the 2026-09-21/22 double-ring by truncating AppClock.now() (app/night/AppClock.kt) to whole
 * milliseconds at its one source, so every "did this already fire" comparison downstream of it normally agrees
 * exactly again - see docs/decisions.md's M2 record. That truncation STAYS (it is why every comparison below
 * usually matches exactly and this tolerance usually does no work at all), but exact equality on an instant
 * that has already survived one lossy round trip (AlarmManager's own epoch-milli granularity) is brittle by
 * construction: if any future clock seam, persistence format, or refactor ever leaks sub-millisecond precision
 * again, exact equality silently reopens the identical double-ring, with nothing in the code to say why it must
 * not. [sameAlarmInstant] and [firedAtOrBefore] are the one place that rule now lives, used by every "is this
 * the alarm I already armed/rang for" comparison in the app, so a future leak degrades gracefully (a same-alarm
 * match a microsecond off still matches) instead of breaking silently again.
 *
 * [ALARM_INSTANT_TOLERANCE] is one second, chosen to sit in the gap between two thresholds it must never cross:
 * - It must comfortably absorb the precision loss it guards against, which is sub-millisecond (a JVM
 *   `Instant.now()` call carries microsecond precision; AlarmManager's own round trip truncates to whole
 *   milliseconds). One second is about a thousand times larger, with room to spare for a future seam that
 *   leaks more than a single clock call's worth.
 * - It must stay far below the smallest gap between two genuinely DIFFERENT alarm targets, or this helper
 *   would confuse them - collapsing two real, distinct alarms into "the same one" is a worse failure than the
 *   one M3 exists to prevent. The owner's own night log has consecutive targets exactly one minute apart
 *   (`02:04:00Z` then `02:05:00Z`, both produced by `pullForwardIfTooSoon`'s `ceilToWholeMinute` rounding, in
 *   this same file), so one minute is the observed floor on this app's own targets. One second is sixty times
 *   under it.
 *
 * **This is not J1.2's mistake, repeated.** J1.2 (docs/decisions.md's J1.2/J2 record) put a THREE-MINUTE
 * window on `firedAlarmIsWakeAlarm`'s own exact-equality check, on the theory that AlarmManager delivery
 * jitter could delay a deferred morning alarm's own firing past its exact armed instant. J2 reverted it for
 * two reasons that both still hold here: the theory was false (the fired marker is read back from the alarm's
 * own armed-FOR extra, never from when the receiver actually ran, so delivery jitter cannot move it at all),
 * and at three minutes the window was wide enough to also swallow a genuinely different, unrelated firing
 * landing by coincidence inside it - a real mid-night nap misattributed as the wake alarm, never counted, and
 * rung a second time. This tolerance is not that window under a new name: it is not here to forgive a late,
 * jittered, or deliberately moved alarm - D8's pull-forward and the deadline cap are what decide when an alarm
 * is supposed to ring, and this helper never touches that decision. It exists only to forgive a REPRESENTATION
 * difference between two values that are supposed to name the exact same instant, at a size (1 s) a hundred
 * and eighty times smaller than the window J2 already proved is wide enough to misfire, and sixty times
 * smaller than the smallest real gap this app ever produces between two different targets.
 *
 * Lives here, not in [EngineConfig]: every constant in [EngineConfig] is a real tunable, deliberately scaled
 * down together on a fast debug night so a whole simulated night still fits the same relative proportions (see
 * `resolveEngineConfig`). This tolerance is the opposite of a tunable - it exists to absorb a fixed, real-world
 * precision loss (a JVM clock call's own sub-millisecond jitter, AlarmManager's own fixed epoch-milli
 * granularity) that does not shrink or grow with simulated night speed. Scaling it down on a fast debug night
 * the way [EngineConfig] scales its own durations would make it SMALLER than the precision loss it exists to
 * absorb, defeating it exactly when the fast-night test suite exercises this comparison the most - keeping it
 * fixed and separate from [EngineConfig] is what keeps that from happening.
 */
val ALARM_INSTANT_TOLERANCE: Duration = Duration.ofSeconds(1)

/**
 * M3: whether [a] and [b] name the same alarm instant, tolerant of up to (but strictly less than)
 * [ALARM_INSTANT_TOLERANCE] of representation drift in either direction - the tolerant replacement for every
 * exact `==`/`!=` comparison that used to ask "is this the same instant I already armed/rang for" (see
 * [ALARM_INSTANT_TOLERANCE]'s own doc for why exact equality was fragile and what this size is chosen to do
 * and not do). The boundary itself (a gap of exactly [ALARM_INSTANT_TOLERANCE]) counts as NOT the same instant,
 * matching `NapAlarmCountingTest`'s own pinned "one second after the morning alarm is already a genuine nap"
 * case - only a gap strictly smaller than the tolerance is a representation difference, not a different alarm.
 * `false` whenever either argument is null: every call site already guards nullability itself (a genuinely
 * absent fired marker means "never fired", not "matches everything"), so this never treats "unknown" as "same".
 */
fun sameAlarmInstant(a: Instant?, b: Instant?): Boolean =
    a != null && b != null && Duration.between(a, b).abs() < ALARM_INSTANT_TOLERANCE

/**
 * M3: whether [candidate] should be treated as already fired against [firedMarker] - the tolerant replacement
 * for the ordering-shaped "already rang" checks (`WakeAlarm.kt`'s `morningAlarmAlreadyRang` and
 * `asleepNapTarget`'s own `napAlreadyFiredForThisOnset`), which compare a freshly computed target against a
 * fired marker rather than two values that are supposed to be bit-for-bit identical. Unlike [sameAlarmInstant]
 * this is deliberately ONE-DIRECTIONAL: [candidate] at or before [firedMarker], by any margin, is already
 * "fired" with no tolerance needed - a target well in the past is unambiguously spent, exactly as the
 * pre-tolerance `!raw.isAfter(latestFired)`/`!latestFired.isBefore(referenceOnset)` checks already treated it.
 * The tolerance only widens the OTHER side: [candidate] up to (but not including) [ALARM_INSTANT_TOLERANCE]
 * AFTER [firedMarker] still counts as fired, which is what a [candidate] that is really the same instant as
 * [firedMarker] but carries a sub-millisecond remainder needs. `false` when [firedMarker] is null (nothing has
 * fired yet, so nothing can be "already fired for").
 */
fun firedAtOrBefore(candidate: Instant, firedMarker: Instant?): Boolean =
    firedMarker != null && candidate.isBefore(firedMarker.plus(ALARM_INSTANT_TOLERANCE))
