package com.nikita.sleepcycle.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

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
        replay.startNight("2026-09-20T23:00")
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

    // ---- J2 SHOULD FIX S4 (reviewer note, adversarial review of J1.1-J1.6): every fixture above this line
    // calls startNight and markAsleep at the SAME instant, per [nightAsleepAtEleven]'s own doc - exactly the
    // blind spot J1.4's re-anchored [NightReplay] exists to get away from (see its own class doc: a tick grid
    // anchored at the same instant as its own target tends to land ON that target's own lattice, never landing
    // NEAR it the way a real, independently-scheduled tick can). Only J1.4's four hand-picked
    // `TickScheduleRaceTest` cases actually use an offset; nothing sweeps the space between them, so a
    // DIFFERENT phase relationship this session's four hand-picked cases do not happen to hit is not guarded by
    // anything. This sweep is the general property every one of those hand-picked cases is really a single
    // sample of: whatever the tick grid's own phase against the target turns out to be, the armed target must
    // never move LATER than the first instant it was ever armed at for that stretch - it may need MULTIPLE
    // ticks to first compute a stable value (a tick landing before the owner is confirmed asleep sees no
    // stretch at all yet), but once armed, D8/J1.1 guarantee it only ever holds steady or gets PULLED FORWARD
    // by an actual firing, never pushed later while still pending.
    //
    // J3 SHOULD FIX 3 (reviewer note, 2026-09-21) DROPS offset 0 from the sweep below. `armedTargetsAfter`'s own
    // filter is `!armedAt.isBefore(from)` - inclusive, not strictly-after - so at offset 0 `markAsleepAt` equals
    // `startNight`'s own instant exactly, and the comment below ("excludes it cleanly") was simply wrong for
    // that one value: the start-night tick's own arming (a PROJECTED, not-yet-asleep target, fifteen minutes
    // later than the real target for the stretch) is included at offset 0, not excluded, and the invariant only
    // passed there because fifteen minutes of slack happened to be enough room. Reverting J1.1 and rerunning
    // confirmed the arithmetic: offsets 30s/60s/90s fail (the ones this sweep is really meant to catch), offset
    // 0 does not - direct confirmation that offset 0 was never doing this sweep's actual job. Every remaining
    // offset is strictly greater than zero, so `markAsleepAt` is strictly after `startNight`'s own instant and
    // the filter genuinely does exclude the start-night tick's own arming, as the comment now correctly claims.
    //
    // This invariant also only holds because this fixture never produces a NAP: a legitimate post-wake nap
    // target is always later than the morning target it follows (H2/D5/G8's own twenty extra minutes), and
    // would fail the "never moves later" assertion below on its own terms, not as a bug. Extending this fixture
    // to cover a nap without first re-scoping the assertion to "never moves later than the first target for the
    // SAME plan mode" would be a false failure, not a caught regression - do not weaken the assertion to make
    // that pass; re-scope it instead. ------------------------------------------------------------------------

    @ParameterizedTest(name = "offset {0}s between startNight and markAsleep")
    @ValueSource(longs = [30, 60, 90, 120, 150, 180, 210, 240, 270, 300])
    fun `J2 SHOULD FIX S4 - the armed morning target never moves later than the first target armed for this stretch, across a sweep of startNight-to-markAsleep offsets`(offsetSeconds: Long) {
        val replay = NightReplay(settings(cycles = 5))
        replay.startNight("2026-09-20T23:00:00")
        // Built from LocalDateTime, not Instant - instant()/NightReplay's own string API takes a zone-less
        // local time (ISO_LOCAL_DATE_TIME), and Instant.toString() always renders a trailing "Z" that the same
        // parser rejects.
        val markAsleepLocal = java.time.LocalDateTime.parse("2026-09-20T23:00:00").plusSeconds(offsetSeconds)
        val markAsleepAt = markAsleepLocal.toString()
        replay.markAsleep(markAsleepAt)

        // Anchored on markAsleepAt itself, not startNight's own 23:00:00 - startNight's own very first tick
        // runs BEFORE this mark is ever recorded (J1.4: marking does not itself tick), against an empty
        // segment list, and can arm its own short-lived PROJECTED-onset target in the meantime; that target
        // belongs to a different (NOT_YET_ASLEEP) stretch, not the one this sweep is about. Offsetting the
        // anchor to markAsleepAt itself excludes it cleanly for every offset actually swept here (all strictly
        // greater than zero, per the J3 SHOULD FIX 3 note above) - it did NOT exclude it at offset 0, which is
        // exactly why that value was dropped from the sweep rather than kept and left silently under-tested.
        replay.advanceTo(markAsleepLocal.plusSeconds(1200).toString())
        val armedSoFar = replay.armedTargetsAfter(markAsleepAt)
        assertTrue(armedSoFar.isNotEmpty(), "no target armed yet for offset ${offsetSeconds}s - ${replay.trace()}")
        val firstTarget = armedSoFar.first()

        // Advance well past the alarm's own instant (roughly 06:30 plus the offset) and well past every nap
        // that could possibly follow, and check every target ever armed for this stretch - none may fall after
        // the first one.
        replay.advanceTo("2026-09-21T08:30:00")
        val everyTarget = replay.armedTargetsAfter(markAsleepAt)
        assertTrue(
            everyTarget.all { !it.isAfter(firstTarget) },
            "a later target than the first one ($firstTarget) was armed for offset ${offsetSeconds}s: $everyTarget - ${replay.trace()}"
        )
    }
}
