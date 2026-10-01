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
    fun `never passed and never tried is due`() {
        assertTrue(connectionTestIsDue(lastPassedAt = null, lastAttemptAt = null, now = now))
    }

    @Test
    fun `passed 25 hours ago is due`() {
        assertTrue(connectionTestIsDue(lastPassedAt = Instant.parse("2026-09-29T21:00:00Z"), lastAttemptAt = null, now = now))
    }

    @Test
    fun `passed 2 hours ago is due even though Start night would still accept it - the band may have gone silent since`() {
        assertTrue(connectionTestIsDue(lastPassedAt = Instant.parse("2026-09-30T20:00:00Z"), lastAttemptAt = null, now = now))
    }

    @Test
    fun `passed 30 minutes ago is not due`() {
        assertFalse(connectionTestIsDue(lastPassedAt = Instant.parse("2026-09-30T21:30:00Z"), lastAttemptAt = null, now = now))
    }

    @Test
    fun `an attempt started 30 seconds ago holds off another automatic one`() {
        assertFalse(connectionTestIsDue(lastPassedAt = null, lastAttemptAt = Instant.parse("2026-09-30T21:59:30Z"), now = now))
    }

    @Test
    fun `an attempt started 3 minutes ago no longer holds off a retry`() {
        assertTrue(connectionTestIsDue(lastPassedAt = null, lastAttemptAt = Instant.parse("2026-09-30T21:57:00Z"), now = now))
    }

    @Test
    fun `a pass timestamp in the future (clock skew) is not trusted, so a test is due`() {
        assertTrue(connectionTestIsDue(lastPassedAt = Instant.parse("2026-09-30T22:05:00Z"), lastAttemptAt = null, now = now))
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
