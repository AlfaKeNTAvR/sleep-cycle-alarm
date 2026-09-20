package com.nikita.sleepcycle.night

// File purpose: D6 - the pure half of "I'm awake" ending the night. NightController.endNight cannot be
// exercised directly in a JVM test (it needs a real Context for file I/O), but the one fact it records that
// this task cares about - awakeConfirmedAt - is applied through this pure function, so that part is directly
// testable without one.

import com.nikita.sleepcycle.engine.NightSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class ApplyAwakeConfirmationTest {
    private val state = NightState(
        startedAt = Instant.parse("2026-09-16T21:00:00Z"),
        settings = NightSettings(deadline = null, pickedCycles = 5),
        lastPlan = null,
        lastSyncAt = null,
        lastSyncOk = null,
        lastSegments = emptyList(),
        lastExportFileModifiedAt = null,
        lastSyncFailureCause = null
    )

    @Test
    fun `D6 I'm awake records awakeConfirmedAt onto the state`() {
        val confirmedAt = Instant.parse("2026-09-17T05:12:00Z")

        val result = applyAwakeConfirmation(state, confirmedAt)

        assertEquals(confirmedAt, result?.awakeConfirmedAt)
    }

    @Test
    fun `every other way of ending the night leaves awakeConfirmedAt null`() {
        val result = applyAwakeConfirmation(state, awakeConfirmedAt = null)

        assertNull(result?.awakeConfirmedAt)
        assertEquals(state, result)
    }

    @Test
    fun `no night in progress stays null either way`() {
        assertNull(applyAwakeConfirmation(null, Instant.parse("2026-09-17T05:12:00Z")))
        assertNull(applyAwakeConfirmation(null, null))
    }
}
