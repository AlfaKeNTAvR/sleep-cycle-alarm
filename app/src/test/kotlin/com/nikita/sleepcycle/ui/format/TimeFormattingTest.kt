package com.nikita.sleepcycle.ui.format

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

private val testZone: ZoneId = ZoneId.of("Europe/Moscow")
private fun instant(text: String): Instant = LocalDateTime.parse(text).atZone(testZone).toInstant()

class FormatClockTimeTest {
    @Test fun `formats as 24-hour HH-mm`() {
        assertEquals("07:55", formatClockTime(instant("2026-09-17T07:55"), testZone))
        assertEquals("23:05", formatClockTime(instant("2026-09-17T23:05"), testZone))
    }
}

class FormatDurationTest {
    @Test fun `an hour and change formats as H h MM`() {
        assertEquals("7 h 30", formatDuration(Duration.ofHours(7).plusMinutes(30)))
        assertEquals("1 h 59", formatDuration(Duration.ofHours(1).plusMinutes(59)))
    }

    @Test fun `under an hour formats as MM min`() {
        assertEquals("20 min", formatDuration(Duration.ofMinutes(20)))
        assertEquals("0 min", formatDuration(Duration.ZERO))
    }

    @Test fun `minutes under ten are zero-padded`() {
        assertEquals("7 h 05", formatDuration(Duration.ofHours(7).plusMinutes(5)))
    }

    @Test fun `a negative duration formats as 0 min instead of going negative`() {
        assertEquals("0 min", formatDuration(Duration.ofMinutes(-5)))
    }
}

class FormatCyclesTest {
    @Test fun `formats to one decimal`() {
        assertEquals("1.3", formatCycles(1.3))
        assertEquals("2.0", formatCycles(2.0))
    }
}

class FormatHoursLabelTest {
    @Test fun `a whole hour has no fraction`() {
        assertEquals("6 h", formatHoursLabel(Duration.ofHours(6)))
        assertEquals("9 h", formatHoursLabel(Duration.ofHours(9)))
    }

    @Test fun `a half hour appends point five`() {
        assertEquals("4.5 h", formatHoursLabel(Duration.ofMinutes(4 * 60 + 30)))
        assertEquals("7.5 h", formatHoursLabel(Duration.ofMinutes(7 * 60 + 30)))
    }
}

class NextOccurrenceOfDeadlineTest {
    @Test fun `a time later today stays today`() {
        val now = instant("2026-09-17T20:00")
        val result = nextOccurrenceOfDeadline(LocalTime.of(23, 30), now, testZone)
        assertEquals(instant("2026-09-17T23:30"), result)
    }

    @Test fun `a time already passed today rolls to tomorrow`() {
        val now = instant("2026-09-17T08:00")
        val result = nextOccurrenceOfDeadline(LocalTime.of(7, 0), now, testZone)
        assertEquals(instant("2026-09-18T07:00"), result)
    }

    @Test fun `a time exactly equal to now rolls to tomorrow, not now`() {
        val now = instant("2026-09-17T08:30")
        val result = nextOccurrenceOfDeadline(LocalTime.of(8, 30), now, testZone)
        assertEquals(instant("2026-09-18T08:30"), result)
    }

    @Test fun `a clock time just after midnight, checked late at night, rolls into tomorrow`() {
        val now = instant("2026-09-17T23:50")
        val result = nextOccurrenceOfDeadline(LocalTime.of(0, 30), now, testZone)
        assertEquals(instant("2026-09-18T00:30"), result)
    }

    @Test fun `a clock time just after midnight, checked just after midnight, stays the same day`() {
        val now = instant("2026-09-18T00:10")
        val result = nextOccurrenceOfDeadline(LocalTime.of(0, 30), now, testZone)
        assertEquals(instant("2026-09-18T00:30"), result)
    }
}
