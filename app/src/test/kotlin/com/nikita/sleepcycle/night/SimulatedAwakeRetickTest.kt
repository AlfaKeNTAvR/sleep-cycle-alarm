package com.nikita.sleepcycle.night

// File purpose: phone test, 2026-10-02 - the owner switched the Asleep chip off, went to the media app and came
// back. Coming back ran an immediate tick, which re-armed the ordinary 15 minute cadence over the re-tick booked
// for when the awake mark passes the engine's 1 minute floor, so the awakening was never seen and the fade never
// started again. Every tick now books its next one no later than that instant itself.

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class SimulatedAwakeRetickTest {
    private val wokeAt = Instant.parse("2026-10-03T05:30:00Z")
    private val oneMinute = Duration.ofMinutes(1)
    private val events = listOf(
        SimulatedSleepEvent(SimulatedSleepEventKind.ASLEEP, Instant.parse("2026-10-03T02:45:46Z")),
        SimulatedSleepEvent(SimulatedSleepEventKind.AWAKE, wokeAt),
    )

    @Test fun `a fresh awake mark books a tick just after it passes the 1 minute floor`() {
        assertEquals(Instant.parse("2026-10-03T05:31:02Z"), simulatedAwakeSettlesAt(events, wokeAt.plusSeconds(8), oneMinute))
    }

    @Test fun `an awake mark already past the floor books nothing`() {
        assertNull(simulatedAwakeSettlesAt(events, wokeAt.plusSeconds(90), oneMinute))
    }

    @Test fun `asleep as the latest mark books nothing`() {
        assertNull(simulatedAwakeSettlesAt(events.take(1), wokeAt, oneMinute))
    }

    @Test fun `the next tick is the earlier of the ordinary one and the awake re-tick`() {
        val ordinary = Instant.parse("2026-10-03T05:45:08Z")
        val settles = Instant.parse("2026-10-03T05:31:02Z")
        assertEquals(settles, earliestTickAt(ordinary, settles))
        assertEquals(ordinary, earliestTickAt(ordinary, null))
    }

    @Test fun `a night that is over books no tick at all`() {
        assertNull(earliestTickAt(null, Instant.parse("2026-10-03T05:31:02Z")))
    }
}
