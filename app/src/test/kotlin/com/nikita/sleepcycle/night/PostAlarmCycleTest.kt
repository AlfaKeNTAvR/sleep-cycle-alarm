package com.nikita.sleepcycle.night

// File purpose: P3 (owner spec, 2026-09-30) - the post-alarm cycle as the owner described it: an alarm rings,
// he stops it, the out-of-bed nudge rings 10 minutes later unless he presses "Nap 20 min", in which case a nap
// alarm rings 20 minutes after the press instead, and the same cycle repeats after every ring, without limit.
// Every expected time below is worked out by hand from that description, not recomputed from EngineConfig.

import com.nikita.sleepcycle.engine.EngineConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class PostAlarmCycleTest {
    private val config = EngineConfig()

    private fun at(time: String): Instant = Instant.parse("2026-09-30T${time}:00Z")
    private fun nudge(time: String) = PendingFollowUp(FollowUpKind.NUDGE, at(time))
    private fun nap(time: String) = PendingFollowUp(FollowUpKind.NAP, at(time))

    @Test fun `stopping the morning alarm at 07_03 arms the nudge for 07_13`() {
        val afterStop = nextFollowUp(PostAlarmEvent.RingEnded(at("07:03")), pending = nudge("07:19"), deadline = null, config = config)

        assertEquals(nudge("07:13"), afterStop)
    }

    @Test fun `pressing Nap at 07_05 replaces the pending nudge with a nap alarm at 07_25`() {
        val afterPress = nextFollowUp(PostAlarmEvent.NapPressed(at("07:05")), pending = nudge("07:13"), deadline = null, config = config)

        assertEquals(nap("07:25"), afterPress)
    }

    @Test fun `the nap alarm ringing at 07_25 and stopped at 07_26 puts the nudge back for 07_36`() {
        val afterRing = nextFollowUp(PostAlarmEvent.AlarmFired(at("07:25")), pending = nap("07:25"), deadline = null, config = config)
        val afterStop = nextFollowUp(PostAlarmEvent.RingEnded(at("07:26")), pending = afterRing, deadline = null, config = config)

        assertEquals(nudge("07:36"), afterStop)
    }

    @Test fun `an alarm ringing at 07_00 that nobody stops still has a nudge behind it at 07_19`() {
        // The ring stops itself after 9 min (07:09); the nudge is 10 min after that. Armed at the firing, so a
        // ring whose end is never seen (the ring service died) still leaves a nudge armed.
        val afterRing = nextFollowUp(PostAlarmEvent.AlarmFired(at("07:00")), pending = null, deadline = null, config = config)

        assertEquals(nudge("07:19"), afterRing)
    }

    @Test fun `a nap chosen while the alarm is still ringing survives the Stop that follows`() {
        // Ring at 07:00, Nap pressed on the Night screen at 07:01 (nap at 07:21), Stop pressed at 07:02.
        val afterStop = nextFollowUp(PostAlarmEvent.RingEnded(at("07:02")), pending = nap("07:21"), deadline = null, config = config)

        assertEquals(nap("07:21"), afterStop)
    }

    @Test fun `pressing I'm up at 08_04 before the 08_19 alarm arms the nudge for 08_14, and Nap then works at once`() {
        // The 2026-10-02 morning: awake at 08:00, wanting a nap without waiting for the 08:19 alarm to ring.
        val afterImUp = nextFollowUp(PostAlarmEvent.ImUpPressed(at("08:04")), pending = null, deadline = null, config = config)
        assertEquals(nudge("08:14"), afterImUp)

        val afterNap = nextFollowUp(PostAlarmEvent.NapPressed(at("08:05")), pending = afterImUp, deadline = null, config = config)
        assertEquals(nap("08:25"), afterNap)
    }

    @Test fun `pressing I'm up replaces a nudge left over from an earlier nap alarm`() {
        // A pre-wake nap rang at 05:00 and its nudge is set for 05:19; I'm up at 05:02 starts a fresh cycle.
        val afterImUp = nextFollowUp(PostAlarmEvent.ImUpPressed(at("05:02")), pending = nudge("05:19"), deadline = null, config = config)

        assertEquals(nudge("05:12"), afterImUp)
    }

    @Test fun `pressing I'm up at 07_12 during his own 07_25 nap ends the nap and arms the nudge for 07_22`() {
        // Owner spec, 2026-10-02: once napping, the screen offers I'm up rather than End night.
        val afterImUp = nextFollowUp(PostAlarmEvent.ImUpPressed(at("07:12")), pending = nap("07:25"), deadline = null, config = config)

        assertEquals(nudge("07:22"), afterImUp)
    }

    @Test fun `a second Nap press while a nap is already armed does not push it later`() {
        val afterPress = nextFollowUp(PostAlarmEvent.NapPressed(at("07:10")), pending = nap("07:25"), deadline = null, config = config)

        assertEquals(nap("07:25"), afterPress)
    }

    @Test fun `a Nap press with nothing pending arms nothing`() {
        // The night was ended, or no alarm has rung yet: there is no nudge for a nap to replace.
        val afterPress = nextFollowUp(PostAlarmEvent.NapPressed(at("03:10")), pending = null, deadline = null, config = config)

        assertEquals(null, afterPress)
    }

    @Test fun `a nap pressed at 07_15 with a 07_30 wake-by deadline rings at the deadline, not at 07_35`() {
        val afterPress = nextFollowUp(PostAlarmEvent.NapPressed(at("07:15")), pending = nudge("07:20"), deadline = at("07:30"), config = config)

        assertEquals(nap("07:30"), afterPress)
    }

    @Test fun `a nap pressed at 07_15 after a 07_00 deadline already passed gets its full 20 min`() {
        // The deadline already rang and the owner chose to nap anyway; capping at a past instant would ring at once.
        val afterPress = nextFollowUp(PostAlarmEvent.NapPressed(at("07:15")), pending = nudge("07:20"), deadline = at("07:00"), config = config)

        assertEquals(nap("07:35"), afterPress)
    }

    @Test fun `a fourth nap in a row is still granted - there is no cap on naps the owner chooses`() {
        // Morning alarm 07:00, stopped 07:01; then four rounds of: Nap pressed 1 min after the stop, the nap
        // rings 20 min later and is stopped 1 min after that.
        var pending: PendingFollowUp? = null
        fun apply(event: PostAlarmEvent) { pending = nextFollowUp(event, pending, deadline = null, config = config) }
        apply(PostAlarmEvent.AlarmFired(at("07:00")))
        apply(PostAlarmEvent.RingEnded(at("07:01")))
        // (pressed, nap rings, stopped)
        val rounds = listOf(
            Triple("07:02", "07:22", "07:23"),
            Triple("07:24", "07:44", "07:45"),
            Triple("07:46", "08:06", "08:07"),
            Triple("08:08", "08:28", "08:29"),
        )
        for ((pressed, rings, stopped) in rounds) {
            apply(PostAlarmEvent.NapPressed(at(pressed)))
            assertEquals(nap(rings), pending)
            apply(PostAlarmEvent.AlarmFired(at(rings)))
            apply(PostAlarmEvent.RingEnded(at(stopped)))
        }

        assertEquals(nudge("08:39"), pending)
    }

    @Test fun `with no Nap pressed the nudge repeats 10 min after each ring ends`() {
        // Morning alarm 07:00 stopped 07:01 -> nudge 07:11; that nudge stopped 07:12 -> 07:22; the next one is
        // left to ring out its own 9 min (07:22 to 07:31) -> 07:41.
        var pending: PendingFollowUp? = null
        fun apply(event: PostAlarmEvent) { pending = nextFollowUp(event, pending, deadline = null, config = config) }
        apply(PostAlarmEvent.AlarmFired(at("07:00")))
        apply(PostAlarmEvent.RingEnded(at("07:01")))
        assertEquals(nudge("07:11"), pending)
        apply(PostAlarmEvent.AlarmFired(at("07:11")))
        apply(PostAlarmEvent.RingEnded(at("07:12")))
        assertEquals(nudge("07:22"), pending)
        apply(PostAlarmEvent.AlarmFired(at("07:22")))
        apply(PostAlarmEvent.RingEnded(at("07:31")))

        assertEquals(nudge("07:41"), pending)
    }
}
