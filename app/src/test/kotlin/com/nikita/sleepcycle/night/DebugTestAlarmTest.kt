package com.nikita.sleepcycle.night

// File purpose: D1 - the debug "Ring phone alarm in 1 min" button must never be schedulable while a real
// night is active, since it used to reuse the real alarm's request code and silently replace it.

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DebugTestAlarmTest {
    @Test
    fun `the test alarm is allowed when no night is active`() {
        assertTrue(isDebugTestAlarmAllowed(nightActive = false))
    }

    @Test
    fun `the test alarm is refused while a night is active`() {
        assertFalse(isDebugTestAlarmAllowed(nightActive = true))
    }
}
