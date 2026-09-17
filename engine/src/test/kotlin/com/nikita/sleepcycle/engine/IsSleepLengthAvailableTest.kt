package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IsSleepLengthAvailableTest {
    @Test fun `allows exact deadline and rejects overrun`() {
        assertTrue(isSleepLengthAvailable(3, instant("2026-09-17T00:00"), instant("2026-09-17T04:45"), EngineConfig()))
        assertFalse(isSleepLengthAvailable(3, instant("2026-09-17T00:00"), instant("2026-09-17T04:44"), EngineConfig()))
        assertTrue(isSleepLengthAvailable(6, instant("2026-09-17T00:00"), null, EngineConfig()))
    }
}
