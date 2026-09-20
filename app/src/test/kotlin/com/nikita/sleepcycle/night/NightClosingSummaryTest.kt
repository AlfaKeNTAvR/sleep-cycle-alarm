package com.nikita.sleepcycle.night

// File purpose: unit tests for NightClosingSummary.kt, built from the owner's real night-20260920-0104.jsonl
// (night_start at 05:04:20.013670Z, four awakenings of 4/11/4/7 minutes read off night_end's own stretches,
// phone_alarm_fired at 13:46:00.062006Z, night_end at 14:14:40.996704Z) - the exact night this task exists
// because of, so a pass here is a pass against the real complaint, not just a synthetic fixture.

import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.engine.SegmentKind
import com.nikita.sleepcycle.engine.SleepSegment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class NightClosingSummaryTest {
    private val config = EngineConfig()
    private fun at(text: String) = Instant.parse(text)

    // The real night's five stretches and four AWAKE marks between them, exactly as night_end recorded the
    // stretches and the "data" events recorded the AWAKE segments.
    private val realNightSegments = listOf(
        SleepSegment(at("2026-09-20T05:39:00Z"), at("2026-09-20T06:00:00Z"), SegmentKind.LIGHT),
        SleepSegment(at("2026-09-20T06:00:00Z"), at("2026-09-20T06:15:00Z"), SegmentKind.DEEP),
        SleepSegment(at("2026-09-20T06:15:00Z"), at("2026-09-20T06:17:00Z"), SegmentKind.AWAKE),
        SleepSegment(at("2026-09-20T06:19:00Z"), at("2026-09-20T09:35:00Z"), SegmentKind.LIGHT),
        SleepSegment(at("2026-09-20T09:35:00Z"), at("2026-09-20T09:40:00Z"), SegmentKind.AWAKE),
        SleepSegment(at("2026-09-20T09:46:00Z"), at("2026-09-20T10:07:00Z"), SegmentKind.LIGHT),
        SleepSegment(at("2026-09-20T10:07:00Z"), at("2026-09-20T10:11:00Z"), SegmentKind.AWAKE),
        SleepSegment(at("2026-09-20T10:11:00Z"), at("2026-09-20T10:24:00Z"), SegmentKind.LIGHT),
        SleepSegment(at("2026-09-20T10:24:00Z"), at("2026-09-20T10:31:00Z"), SegmentKind.AWAKE),
        SleepSegment(at("2026-09-20T10:31:00Z"), at("2026-09-20T13:31:00Z"), SegmentKind.LIGHT),
        // The final AWAKE mark the band recorded, which never resumed into sleep before the night ended.
        SleepSegment(at("2026-09-20T13:31:00Z"), at("2026-09-20T13:34:00Z"), SegmentKind.AWAKE)
    )
    private val startedAt = at("2026-09-20T05:04:20.013670Z")
    private val wakeAlarmFiredAt = at("2026-09-20T13:46:00.062006Z")
    private val endedAt = at("2026-09-20T14:14:40.996704Z")

    @Test
    fun `the real night's fall-asleep latency matches the first stretch's onset minus night_start`() {
        val summary = buildNightClosingSummary(startedAt, realNightSegments, endedAt, config, wakeAlarmFiredAt)

        assertEquals(Duration.ofMinutes(34), summary.fellAsleepAfter, "05:39:00 minus 05:04:20.013670, truncated to whole minutes")
    }

    @Test
    fun `the real night's four awakenings come back with the exact durations the owner had to compute by hand`() {
        val summary = buildNightClosingSummary(startedAt, realNightSegments, endedAt, config, wakeAlarmFiredAt)

        assertEquals(4, summary.awakenings.size)
        assertEquals(listOf(4L, 11L, 4L, 7L), summary.awakenings.map { it.duration.toMinutes() })
    }

    @Test
    fun `none of the four completed awakenings are after the wake alarm, which fired long after all of them`() {
        val summary = buildNightClosingSummary(startedAt, realNightSegments, endedAt, config, wakeAlarmFiredAt)

        assertEquals(listOf(false, false, false, false), summary.awakenings.map { it.afterWakeAlarm })
    }

    @Test
    fun `the real night's time in bed after the wake alarm is about 28 minutes, the number this task exists to answer`() {
        val summary = buildNightClosingSummary(startedAt, realNightSegments, endedAt, config, wakeAlarmFiredAt)

        assertEquals(Duration.ofMinutes(28), summary.inBedAfterWakeAlarm, "14:14:40.996704 minus 13:46:00.062006, truncated to whole minutes")
    }

    @Test
    fun `the final open awakening - waking up and staying awake - is not counted as a fifth completed awakening`() {
        val summary = buildNightClosingSummary(startedAt, realNightSegments, endedAt, config, wakeAlarmFiredAt)

        assertEquals(4, summary.awakenings.size, "the 13:31 wake-up has no following stretch, so it is the ending, not a 5th awakening")
    }

    @Test
    fun `a wake alarm that never fired leaves inBedAfterWakeAlarm null rather than a bogus duration`() {
        val summary = buildNightClosingSummary(startedAt, realNightSegments, endedAt, config, wakeAlarmFiredAt = null)

        assertNull(summary.inBedAfterWakeAlarm)
    }

    @Test
    fun `a night that never recorded any sleep leaves fellAsleepAfter null, not zero`() {
        val summary = buildNightClosingSummary(startedAt, emptyList(), endedAt, config, wakeAlarmFiredAt = null)

        assertNull(summary.fellAsleepAfter)
        assertEquals(emptyList<AwakeningEvent>(), summary.awakenings)
    }

    @Test
    fun `encoded fields round trip the real night's numbers as the night_summary event would log them`() {
        val summary = buildNightClosingSummary(startedAt, realNightSegments, endedAt, config, wakeAlarmFiredAt)

        val fields = encodeNightClosingSummaryFields(summary)

        assertEquals("34", fields["fellAsleepAfterMinutes"])
        assertEquals("4", fields["awakeningCount"])
        assertEquals("4,11,4,7", fields["awakeningDurationsMinutes"])
        assertEquals("0", fields["awakeningsAfterWakeAlarmCount"])
        assertEquals("28", fields["inBedAfterWakeAlarmMinutes"])
    }

    @Test
    fun `encoded fields use empty strings, not the word null, for an absent duration`() {
        val summary = buildNightClosingSummary(startedAt, emptyList(), endedAt, config, wakeAlarmFiredAt = null)

        val fields = encodeNightClosingSummaryFields(summary)

        assertEquals("", fields["fellAsleepAfterMinutes"])
        assertEquals("", fields["inBedAfterWakeAlarmMinutes"])
        assertEquals("0", fields["awakeningCount"])
        assertEquals("", fields["awakeningDurationsMinutes"])
    }
}
