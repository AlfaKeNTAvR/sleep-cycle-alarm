package com.nikita.sleepcycle.engine

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * D5: naps after the main wake alarm has fired, capped at [MAX_NAP_ALARMS]. F2 SUPERSEDES the original spec:
 * napAlarmsUsed is incremented by the app layer (PhoneAlarmReceiver) only once a nap alarm actually FIRES, not
 * when it is armed - this test threads it through by hand exactly that way (it stays at its old value for
 * every tick between a nap being armed and that same nap's own wakeAt actually arriving), to walk the whole
 * capped sequence through the pure engine alone. F5 is exercised as a side effect: every AWAKE-state tick
 * below arms nothing (`wakeAt == null`) once the wake alarm has fired, whatever the cap says. Separate from
 * rule 7's own mid-night nap (ChooseModeTest, ExtraNightScenariosTest's "survives a second awakening" case),
 * which this does not touch.
 */
class PostWakeNapTest {
    private val config = EngineConfig()
    private val setting = settings(cycles = 3)

    private fun plan(
        segments: List<SleepSegment>, now: String, previous: AlarmPlan?, wakeAlarmFiredAt: Instant?, napAlarmsUsed: Int,
        lastNapAlarmFiredAt: Instant? = null, phoneAlarmFiredFor: Instant? = null
    ) = computeAlarmPlan(
        segments, setting, instant(now), previous?.wakeAt, testZone, config, wakeAlarmFiredAt, napAlarmsUsed, lastNapAlarmFiredAt,
        phoneAlarmFiredFor
    )

    @Test fun `a first nap, a second nap, and a genuinely new third return to sleep FINISHES the night`() {
        // The main wake alarm already fired at 07:00 (mode FULL_CYCLES, the picked total all used).
        val wakeAlarmFired = AlarmPlan(AlarmMode.FULL_CYCLES, instant("2026-09-17T07:00"), 3, instant("2026-09-17T02:30"), false, "r")

        // 07:05: still awake, unconfirmed. F5: the AWAKE safety net arms nothing of its own once the wake
        // alarm has fired - mode is still NAP (chooseMode's own rule 7 test is untouched), but there is no
        // alarm to show for it and nothing for the app to ever count.
        val stillAwake = plan(
            listOf(
                segment("2026-09-16T22:30", "2026-09-17T07:00", SegmentKind.LIGHT),
                segment("2026-09-17T07:00", "2026-09-17T07:05", SegmentKind.AWAKE)
            ),
            "2026-09-17T07:05", wakeAlarmFired, wakeAlarmFiredAt = instant("2026-09-17T07:00"), napAlarmsUsed = 0
        )
        assertEquals(AlarmMode.NAP, stillAwake.mode)
        assertEquals(null, stillAwake.wakeAt)

        // 07:11: falls back asleep - the first D5 nap, onset 07:10, alarm at 07:30. Nothing has FIRED yet, so
        // napAlarmsUsed is still 0 - it only becomes 1 once this 07:30 alarm actually rings.
        val firstNapArmed = plan(
            listOf(
                segment("2026-09-16T22:30", "2026-09-17T07:00", SegmentKind.LIGHT),
                segment("2026-09-17T07:00", "2026-09-17T07:10", SegmentKind.AWAKE),
                segment("2026-09-17T07:10", "2026-09-17T07:11", SegmentKind.LIGHT)
            ),
            "2026-09-17T07:11", stillAwake, wakeAlarmFiredAt = instant("2026-09-17T07:00"), napAlarmsUsed = 0
        )
        assertEquals(AlarmMode.NAP, firstNapArmed.mode)
        assertEquals("07:30", formatTime(firstNapArmed.wakeAt!!, testZone))

        // 07:15: still the SAME pending nap, still hasn't fired - napAlarmsUsed is still 0, and the alarm holds.
        val firstNapHeld = plan(
            listOf(
                segment("2026-09-16T22:30", "2026-09-17T07:00", SegmentKind.LIGHT),
                segment("2026-09-17T07:00", "2026-09-17T07:10", SegmentKind.AWAKE),
                segment("2026-09-17T07:10", "2026-09-17T07:15", SegmentKind.LIGHT)
            ),
            "2026-09-17T07:15", firstNapArmed, wakeAlarmFiredAt = instant("2026-09-17T07:00"), napAlarmsUsed = 0
        )
        assertEquals(AlarmMode.NAP, firstNapHeld.mode)
        assertEquals("07:30", formatTime(firstNapHeld.wakeAt!!, testZone))

        // 07:30: the first nap alarm FIRES - PhoneAlarmReceiver records it and napAlarmsUsed becomes 1 from
        // here on. Owner still unconfirmed, briefly awake again; F5 again arms nothing.
        val secondAwake = plan(
            listOf(
                segment("2026-09-16T22:30", "2026-09-17T07:00", SegmentKind.LIGHT),
                segment("2026-09-17T07:00", "2026-09-17T07:10", SegmentKind.AWAKE),
                segment("2026-09-17T07:10", "2026-09-17T07:30", SegmentKind.LIGHT),
                segment("2026-09-17T07:30", "2026-09-17T07:33", SegmentKind.AWAKE)
            ),
            "2026-09-17T07:33", firstNapHeld, wakeAlarmFiredAt = instant("2026-09-17T07:00"), napAlarmsUsed = 1
        )
        assertEquals(AlarmMode.NAP, secondAwake.mode)
        assertEquals(null, secondAwake.wakeAt)

        // 07:36: falls back asleep again - the SECOND D5 nap, onset 07:35, alarm at 07:55. napAlarmsUsed is
        // still 1: this second nap has been armed, not yet fired.
        val secondNapArmed = plan(
            listOf(
                segment("2026-09-16T22:30", "2026-09-17T07:00", SegmentKind.LIGHT),
                segment("2026-09-17T07:00", "2026-09-17T07:10", SegmentKind.AWAKE),
                segment("2026-09-17T07:10", "2026-09-17T07:30", SegmentKind.LIGHT),
                segment("2026-09-17T07:30", "2026-09-17T07:35", SegmentKind.AWAKE),
                segment("2026-09-17T07:35", "2026-09-17T07:36", SegmentKind.LIGHT)
            ),
            "2026-09-17T07:36", secondAwake, wakeAlarmFiredAt = instant("2026-09-17T07:00"), napAlarmsUsed = 1
        )
        assertEquals(AlarmMode.NAP, secondNapArmed.mode)
        assertEquals("07:55", formatTime(secondNapArmed.wakeAt!!, testZone))

        // 07:40: still the SAME second nap, still pending - napAlarmsUsed is still 1, and the alarm holds.
        val secondNapHeld = plan(
            listOf(
                segment("2026-09-16T22:30", "2026-09-17T07:00", SegmentKind.LIGHT),
                segment("2026-09-17T07:00", "2026-09-17T07:10", SegmentKind.AWAKE),
                segment("2026-09-17T07:10", "2026-09-17T07:30", SegmentKind.LIGHT),
                segment("2026-09-17T07:30", "2026-09-17T07:35", SegmentKind.AWAKE),
                segment("2026-09-17T07:35", "2026-09-17T07:40", SegmentKind.LIGHT)
            ),
            "2026-09-17T07:40", secondNapArmed, wakeAlarmFiredAt = instant("2026-09-17T07:00"), napAlarmsUsed = 1
        )
        assertEquals(AlarmMode.NAP, secondNapHeld.mode)
        assertEquals("07:55", formatTime(secondNapHeld.wakeAt!!, testZone))

        // 07:55: the second nap alarm FIRES - napAlarmsUsed becomes MAX_NAP_ALARMS (2) from here on. Owner
        // still unconfirmed, awake again - F5 still arms nothing, whether or not the cap is spent.
        val thirdAwake = plan(
            listOf(
                segment("2026-09-16T22:30", "2026-09-17T07:00", SegmentKind.LIGHT),
                segment("2026-09-17T07:00", "2026-09-17T07:10", SegmentKind.AWAKE),
                segment("2026-09-17T07:10", "2026-09-17T07:30", SegmentKind.LIGHT),
                segment("2026-09-17T07:30", "2026-09-17T07:35", SegmentKind.AWAKE),
                segment("2026-09-17T07:35", "2026-09-17T07:55", SegmentKind.LIGHT),
                segment("2026-09-17T07:55", "2026-09-17T07:58", SegmentKind.AWAKE)
            ),
            "2026-09-17T07:58", secondNapHeld, wakeAlarmFiredAt = instant("2026-09-17T07:00"), napAlarmsUsed = MAX_NAP_ALARMS
        )
        assertEquals(AlarmMode.NAP, thirdAwake.mode)
        assertEquals(null, thirdAwake.wakeAt)

        // 08:01: falls asleep a THIRD time - a genuinely new return to sleep with the cap already spent, and
        // no deadline to fall back on (F4), so the night FINISHES outright instead of arming a third nap.
        val thirdReturnToSleep = plan(
            listOf(
                segment("2026-09-16T22:30", "2026-09-17T07:00", SegmentKind.LIGHT),
                segment("2026-09-17T07:00", "2026-09-17T07:10", SegmentKind.AWAKE),
                segment("2026-09-17T07:10", "2026-09-17T07:30", SegmentKind.LIGHT),
                segment("2026-09-17T07:30", "2026-09-17T07:35", SegmentKind.AWAKE),
                segment("2026-09-17T07:35", "2026-09-17T07:55", SegmentKind.LIGHT),
                segment("2026-09-17T07:55", "2026-09-17T08:00", SegmentKind.AWAKE),
                segment("2026-09-17T08:00", "2026-09-17T08:01", SegmentKind.LIGHT)
            ),
            "2026-09-17T08:01", thirdAwake, wakeAlarmFiredAt = instant("2026-09-17T07:00"), napAlarmsUsed = MAX_NAP_ALARMS
        )
        assertEquals(AlarmMode.FINISHED, thirdReturnToSleep.mode)
        assertEquals(null, thirdReturnToSleep.wakeAt)
    }

    @Test fun `F4 the same third return to sleep keeps a deadline alarm instead of finishing when one is still ahead`() {
        val deadlineSetting = settings(deadline = "2026-09-17T08:30", cycles = 3)
        fun planWithDeadline(segments: List<SleepSegment>, now: String, previous: AlarmPlan?, wakeAlarmFiredAt: Instant?, napAlarmsUsed: Int) =
            computeAlarmPlan(
                segments, deadlineSetting, instant(now), previous?.wakeAt, testZone, config, wakeAlarmFiredAt, napAlarmsUsed,
                lastNapAlarmFiredAt = null, phoneAlarmFiredFor = null
            )

        val secondNapHeld = AlarmPlan(AlarmMode.NAP, instant("2026-09-17T07:55"), 0, instant("2026-09-17T07:35"), false, "r")
        val thirdAwake = planWithDeadline(
            listOf(
                segment("2026-09-16T22:30", "2026-09-17T07:00", SegmentKind.LIGHT),
                segment("2026-09-17T07:00", "2026-09-17T07:10", SegmentKind.AWAKE),
                segment("2026-09-17T07:10", "2026-09-17T07:30", SegmentKind.LIGHT),
                segment("2026-09-17T07:30", "2026-09-17T07:35", SegmentKind.AWAKE),
                segment("2026-09-17T07:35", "2026-09-17T07:55", SegmentKind.LIGHT),
                segment("2026-09-17T07:55", "2026-09-17T07:58", SegmentKind.AWAKE)
            ),
            "2026-09-17T07:58", secondNapHeld, wakeAlarmFiredAt = instant("2026-09-17T07:00"), napAlarmsUsed = MAX_NAP_ALARMS
        )
        assertEquals(AlarmMode.NAP, thirdAwake.mode)
        assertEquals(null, thirdAwake.wakeAt)

        val thirdReturnToSleep = planWithDeadline(
            listOf(
                segment("2026-09-16T22:30", "2026-09-17T07:00", SegmentKind.LIGHT),
                segment("2026-09-17T07:00", "2026-09-17T07:10", SegmentKind.AWAKE),
                segment("2026-09-17T07:10", "2026-09-17T07:30", SegmentKind.LIGHT),
                segment("2026-09-17T07:30", "2026-09-17T07:35", SegmentKind.AWAKE),
                segment("2026-09-17T07:35", "2026-09-17T07:55", SegmentKind.LIGHT),
                segment("2026-09-17T07:55", "2026-09-17T08:00", SegmentKind.AWAKE),
                segment("2026-09-17T08:00", "2026-09-17T08:01", SegmentKind.LIGHT)
            ),
            "2026-09-17T08:01", thirdAwake, wakeAlarmFiredAt = instant("2026-09-17T07:00"), napAlarmsUsed = MAX_NAP_ALARMS
        )
        assertEquals(AlarmMode.DEADLINE_ONLY, thirdReturnToSleep.mode)
        assertEquals(instant("2026-09-17T08:30"), thirdReturnToSleep.wakeAt)
    }
}
