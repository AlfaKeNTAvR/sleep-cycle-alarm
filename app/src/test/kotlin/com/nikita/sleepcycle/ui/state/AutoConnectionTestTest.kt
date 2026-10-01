package com.nikita.sleepcycle.ui.state

// File purpose: P2 - when the app runs "Test connection" on its own. Expected values are hand-worked against the
// 1 h re-check interval and the 2 min retry cooldown, never recomputed from the constants.

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class AutoConnectionTestDueTest {
    private val now: Instant = Instant.parse("2026-09-30T22:00:00Z")

    @Test
    fun `never tried is due`() {
        assertTrue(connectionTestIsDue(lastAttemptAt = null, now = now))
    }

    // Owner decision on the phone, 2026-09-30: he closed and reopened the app 25 min after a pass and it did not
    // re-check. Every open re-checks now, however recent the last pass; only the 2 min cooldown holds one off.
    @Test
    fun `an attempt 25 minutes ago is due on the next open`() {
        assertTrue(connectionTestIsDue(lastAttemptAt = Instant.parse("2026-09-30T21:35:00Z"), now = now))
    }

    @Test
    fun `an attempt started 30 seconds ago holds off another automatic one`() {
        assertFalse(connectionTestIsDue(lastAttemptAt = Instant.parse("2026-09-30T21:59:30Z"), now = now))
    }

    @Test
    fun `an attempt started 3 minutes ago no longer holds off a retry`() {
        assertTrue(connectionTestIsDue(lastAttemptAt = Instant.parse("2026-09-30T21:57:00Z"), now = now))
    }
}

class LastPassedAfterAttemptTest {
    private val yesterdayPass: Instant = Instant.parse("2026-09-29T23:00:00Z")
    private val attemptStartedAt: Instant = Instant.parse("2026-09-30T22:00:00Z")

    @Test
    fun `a passing attempt records when it started`() {
        assertEquals(attemptStartedAt, lastPassedAtAfterAttempt(yesterdayPass, passed = true, attemptStartedAt = attemptStartedAt))
    }

    @Test
    fun `a failed attempt, automatic or tapped, keeps the earlier pass - a failure warns but never greys out Start night on its own`() {
        assertEquals(yesterdayPass, lastPassedAtAfterAttempt(yesterdayPass, passed = false, attemptStartedAt = attemptStartedAt))
    }

    @Test
    fun `a failure with no earlier pass leaves none`() {
        assertNull(lastPassedAtAfterAttempt(null, passed = false, attemptStartedAt = attemptStartedAt))
    }
}
