package com.nikita.sleepcycle.ui.state

// File purpose: P2 - when the app runs "Test connection" on its own, with no tap. The ViewModel asks this on every
// app open (resume) outside a night; everything here is pure so the policy is unit-tested without Android.

import java.time.Duration
import java.time.Instant

/**
 * P2: how long a pass is shown as "Band connected (HH:mm)" in Before bed's corner (see BandStatus); older reads
 * "Not synced yet". It used to also be the age at which an app open re-checked; since the owner's decision on
 * 2026-09-30 every open re-checks (see [connectionTestIsDue]), so this now only bounds a screen left open.
 * Deliberately much shorter than [SETUP_CHECK_VALIDITY_WINDOW]: the night of 2026-09-30 started on a still-valid
 * pass from the previous evening while the band had already stopped syncing with Gadgetbridge.
 */
val CONNECTED_STATUS_MAX_AGE: Duration = Duration.ofHours(1)

/** P2: after any attempt (automatic or tapped), no automatic one starts again until this has passed, so bouncing in and out of the app does not fire a sync each time. A tap on "Test connection" ignores it. */
val AUTO_CONNECTION_TEST_COOLDOWN: Duration = Duration.ofMinutes(2)

/**
 * P2: true when the app should run the connection test by itself now - on every app open outside a night, unless
 * an attempt started within [AUTO_CONNECTION_TEST_COOLDOWN]. Owner decision, 2026-09-30: he reopened the app 25 min
 * after a pass and expected a fresh answer; a healthy check costs about 3-5 s (setup.jsonl, 11 passes 2.5-4.8 s).
 * Both instants are REAL time (T4 exception, same as lastSetupCheckPassedAt - see NightViewModel.runSetupCheckAction).
 */
fun connectionTestIsDue(lastAttemptAt: Instant?, now: Instant): Boolean =
    lastAttemptAt == null || !isWithin(lastAttemptAt, now, AUTO_CONNECTION_TEST_COOLDOWN)

/**
 * P2: the new [com.nikita.sleepcycle.night.AppSettings.lastSetupCheckPassedAt] after one attempt. A pass records
 * [attemptStartedAt]. A failure, automatic or tapped, keeps [previous] (owner decision, P2): a failed test warns
 * - in amber on Before bed (BandCheckStatus.Failed) and in Setup's report - but never on its own greys out a
 * "Start night" an earlier pass still allows. With no pass inside the 24 h window, the gate stays shut as before.
 * Before P2 a failed tapped test cleared the pass.
 */
fun lastPassedAtAfterAttempt(previous: Instant?, passed: Boolean, attemptStartedAt: Instant): Instant? =
    if (passed) attemptStartedAt else previous

/** True when [earlier] is not after [now] and at most [window] before it. */
private fun isWithin(earlier: Instant, now: Instant, window: Duration): Boolean {
    val elapsed = Duration.between(earlier, now)
    return !elapsed.isNegative && elapsed <= window
}
