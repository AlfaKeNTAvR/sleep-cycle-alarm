package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The whole alarm sequence of a night, replayed tick by tick through [NightReplay] - the plans, the arming,
 * and the alarms actually RINGING - rather than one [computeAlarmPlan] call at a time. Every other engine
 * test asks "what does this one call return"; these ask "what does the owner's night actually do", which is
 * the only level the two defects below are visible at:
 *
 *  - the morning alarm rings, and is then re-armed a couple of minutes later on every following tick, ringing
 *    again and again (the owner's own report: "it automatically shifts the alarm a couple of minutes forward,
 *    every single time");
 *  - waking a few minutes BEFORE the morning alarm replaces it with rule 7's sliding AWAKE nap, which then
 *    stops permanently the moment the morning alarm's own time passes - so no alarm ever rings at all (the
 *    owner's own report: "the alarm never fired").
 */
class AlarmSequenceReplayTest {
    /** 23:00, five cycles, no deadline: the morning alarm lands at 06:30. */
    private fun nightAsleepAtEleven(deadline: String? = null): NightReplay {
        val replay = NightReplay(settings(deadline = deadline, cycles = 5))
        replay.markAsleep("2026-09-20T23:00")
        return replay
    }

    @Test fun `the morning alarm rings exactly once and is never re-armed a couple of minutes later`() {
        val replay = nightAsleepAtEleven()

        // Asleep straight through the alarm and well past it, with nothing else happening.
        replay.advanceTo("2026-09-21T07:30")

        assertEquals(listOf(instant("2026-09-21T06:30")), replay.firings.map { it.firedFor }, replay.trace())
        assertEquals(emptyList<java.time.Instant>(), replay.armedTargetsAfter("2026-09-21T06:31"), replay.trace())
    }

    @Test fun `marking awake after the morning alarm arms nothing more, however many ticks follow`() {
        val replay = nightAsleepAtEleven()
        replay.advanceTo("2026-09-21T06:31")

        replay.markAwake("2026-09-21T06:32")
        replay.advanceTo("2026-09-21T07:30")

        assertEquals(listOf(instant("2026-09-21T06:30")), replay.firings.map { it.firedFor }, replay.trace())
        assertEquals(emptyList<java.time.Instant>(), replay.armedTargetsAfter("2026-09-21T06:31"), replay.trace())
        assertNull(replay.lastPlan?.wakeAt, replay.trace())
    }

    @Test fun `waking a few minutes BEFORE the morning alarm still lets it ring, at its own time`() {
        val replay = nightAsleepAtEleven()
        replay.advanceTo("2026-09-21T06:19")

        // Awake at 06:20, ten minutes before the 06:30 alarm, and still lying there.
        replay.markAwake("2026-09-21T06:20")
        replay.advanceTo("2026-09-21T07:10")

        assertEquals(listOf(instant("2026-09-21T06:30")), replay.firings.map { it.firedFor }, replay.trace())
        // Attributed to the MAIN wake alarm, not counted as one of the two naps.
        assertEquals(instant("2026-09-21T06:30"), replay.wakeAlarmFiredAt, replay.trace())
        assertEquals(0, replay.napAlarmsUsed, replay.trace())
    }

    @Test fun `a genuine return to sleep arms a nap that holds its target across ticks and actually rings`() {
        val replay = nightAsleepAtEleven()
        replay.advanceTo("2026-09-21T06:31")
        replay.markAwake("2026-09-21T06:32")
        replay.advanceTo("2026-09-21T06:45")

        // Asleep again at 06:46: rule 7's nap, 20 minutes on from that onset.
        replay.markAsleep("2026-09-21T06:46")

        // Four ticks pass before it is due: the target must hold steady at 07:06, never slide out of reach.
        replay.advanceTo("2026-09-21T07:05")
        assertEquals(listOf(instant("2026-09-21T07:06")), replay.armedTargetsAfter("2026-09-21T06:46"), replay.trace())
        assertEquals(emptyList<NightReplay.Firing>(), replay.firingsAfter("2026-09-21T06:31"), replay.trace())

        replay.advanceTo("2026-09-21T07:07")
        assertEquals(
            listOf(instant("2026-09-21T07:06")),
            replay.firingsAfter("2026-09-21T06:31").map { it.firedFor },
            replay.trace()
        )
        assertEquals(AlarmMode.NAP, replay.firingsAfter("2026-09-21T06:31").single().mode, replay.trace())
        assertEquals(1, replay.napAlarmsUsed, replay.trace())
    }

    @Test fun `D5 two nap alarms ring, and the night then finishes instead of arming a third`() {
        val replay = nightAsleepAtEleven()
        replay.advanceTo("2026-09-21T06:31")
        replay.markAwake("2026-09-21T06:32")
        replay.advanceTo("2026-09-21T06:45")
        replay.markAsleep("2026-09-21T06:46")

        // Slept straight through both naps: the first rings at 07:06, H2's fresh 20 minutes rings at 07:26.
        replay.advanceTo("2026-09-21T07:40")

        assertEquals(
            listOf(instant("2026-09-21T07:06"), instant("2026-09-21T07:26")),
            replay.firingsAfter("2026-09-21T06:31").map { it.firedFor },
            replay.trace()
        )
        assertEquals(MAX_NAP_ALARMS, replay.napAlarmsUsed, replay.trace())
        assertEquals(AlarmMode.FINISHED, replay.lastPlan?.mode, replay.trace())
    }

    @Test fun `a deadline night rings the morning alarm, then a deadline-capped nap, then finishes`() {
        val replay = nightAsleepAtEleven(deadline = "2026-09-21T07:00")
        replay.advanceTo("2026-09-21T06:31")
        replay.markAwake("2026-09-21T06:32")
        replay.advanceTo("2026-09-21T06:45")
        replay.markAsleep("2026-09-21T06:46")
        replay.advanceTo("2026-09-21T07:30")

        assertEquals(
            listOf(instant("2026-09-21T06:30"), instant("2026-09-21T07:00")),
            replay.firings.map { it.firedFor },
            replay.trace()
        )
        assertEquals(AlarmMode.FINISHED, replay.lastPlan?.mode, replay.trace())
        assertEquals(emptyList<java.time.Instant>(), replay.armedTargetsAfter("2026-09-21T07:01"), replay.trace())
    }
}
