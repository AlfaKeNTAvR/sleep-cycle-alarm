package com.nikita.sleepcycle.night

import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.NightSummary
import com.nikita.sleepcycle.engine.StretchSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * The real night_start line from the night of 2026-09-18 (started 00:11 local, 5 cycles picked, 08:30
 * deadline), so the old-format fallback is tested against a log the app actually wrote, not a reconstruction.
 */
private const val REAL_NIGHT_START_LINE =
    """{"at":"2026-09-18T04:11:05.057916Z","type":"night_start","fields":{"pickedCycles":"5","deadline":"2026-09-18T12:30:00Z","simulatedBandData":"false","fastNight":"false","bandCommandMode":"SEND_TO_BAND"}}"""

/** That same night's night_end, in the only shape night_end had before it carried structured fields. */
private const val REAL_OLD_FORMAT_NIGHT_END_LINE =
    """{"at":"2026-09-18T12:17:31.710887Z","type":"night_end","fields":{"summary":"total=PT6H50M stretches=5"}}"""

class PastNightLogTest {
    private fun nightEndLine(summary: NightSummary, settings: NightSettings, at: Instant): String =
        formatNightLogLine(NightLogEvent(at, "night_end", encodeNightEndFields(summary, settings)))

    private fun nightStartLine(settings: NightSettings, at: Instant, fastNight: Boolean = false): String =
        formatNightLogLine(
            NightLogEvent(
                at, "night_start",
                mapOf(
                    "pickedCycles" to settings.pickedCycles.toString(),
                    "deadline" to (settings.deadline?.toString() ?: "none"),
                    "fastNight" to fastNight.toString()
                )
            )
        )

    private val twoStretchSummary = NightSummary(
        totalSleep = Duration.ofHours(5).plusMinutes(30),
        stretches = listOf(
            StretchSummary(
                onset = Instant.parse("2026-09-17T22:30:00Z"),
                end = Instant.parse("2026-09-18T01:00:00Z"),
                duration = Duration.ofMinutes(150),
                cycles = 1.7
            ),
            StretchSummary(
                onset = Instant.parse("2026-09-18T01:20:00Z"),
                end = Instant.parse("2026-09-18T04:20:00Z"),
                duration = Duration.ofMinutes(180),
                cycles = 2.0
            )
        )
    )

    private val deadlineSettings = NightSettings(
        deadline = Instant.parse("2026-09-18T06:30:00Z"),
        pickedCycles = 5
    )

    @Test
    fun `round trips a night summary through the night_end event`() {
        val endedAt = Instant.parse("2026-09-18T04:25:00Z")

        val parsed = parsePastNightLog(listOf(nightEndLine(twoStretchSummary, deadlineSettings, endedAt)))

        assertEquals(
            PastNightSummary.Detailed(
                totalSleep = Duration.ofHours(5).plusMinutes(30),
                stretches = listOf(
                    RecordedStretch(Instant.parse("2026-09-17T22:30:00Z"), Instant.parse("2026-09-18T01:00:00Z"), 1.7),
                    RecordedStretch(Instant.parse("2026-09-18T01:20:00Z"), Instant.parse("2026-09-18T04:20:00Z"), 2.0)
                )
            ),
            parsed.summary
        )
        assertEquals(endedAt, parsed.endedAt)
        assertEquals(Instant.parse("2026-09-18T06:30:00Z"), parsed.deadline)
        assertEquals(5, parsed.pickedCycles)
    }

    @Test
    fun `keeps the human-readable summary line alongside the structured fields`() {
        val fields = encodeNightEndFields(twoStretchSummary, deadlineSettings)

        assertEquals("total=PT5H30M stretches=2", fields["summary"])
    }

    @Test
    fun `a night with no deadline round trips as no deadline`() {
        val settings = NightSettings(deadline = null, pickedCycles = 4)

        val parsed = parsePastNightLog(listOf(nightEndLine(twoStretchSummary, settings, Instant.parse("2026-09-18T04:25:00Z"))))

        assertNull(parsed.deadline)
        assertEquals(4, parsed.pickedCycles)
    }

    @Test
    fun `a night that recorded no sleep round trips as an empty stretch list, not as missing`() {
        val emptySummary = NightSummary(Duration.ZERO, emptyList())

        val parsed = parsePastNightLog(listOf(nightEndLine(emptySummary, deadlineSettings, Instant.parse("2026-09-18T04:25:00Z"))))

        assertEquals(PastNightSummary.Detailed(Duration.ZERO, emptyList()), parsed.summary)
    }

    @Test
    fun `an old-format night_end falls back to the total and the stretch count it did record`() {
        val parsed = parsePastNightLog(listOf(REAL_NIGHT_START_LINE, REAL_OLD_FORMAT_NIGHT_END_LINE))

        assertEquals(
            PastNightSummary.TotalOnly(totalSleep = Duration.ofHours(6).plusMinutes(50), stretchCount = 5),
            parsed.summary
        )
    }

    @Test
    fun `an old-format night still reports the deadline and picked length from its night_start`() {
        val parsed = parsePastNightLog(listOf(REAL_NIGHT_START_LINE, REAL_OLD_FORMAT_NIGHT_END_LINE))

        assertEquals(Instant.parse("2026-09-18T12:30:00Z"), parsed.deadline)
        assertEquals(5, parsed.pickedCycles)
        assertEquals(Instant.parse("2026-09-18T04:11:05.057916Z"), parsed.startedAt)
        assertEquals(Instant.parse("2026-09-18T12:17:31.710887Z"), parsed.endedAt)
        assertEquals(false, parsed.fastNight)
    }

    @Test
    fun `a log with no night_end has no summary at all rather than an empty one`() {
        val parsed = parsePastNightLog(listOf(REAL_NIGHT_START_LINE))

        assertEquals(PastNightSummary.Missing, parsed.summary)
        assertNull(parsed.endedAt)
        assertEquals(5, parsed.pickedCycles)
    }

    @Test
    fun `a night_end whose structured stretches are corrupt falls back to its summary line`() {
        val corrupt = formatNightLogLine(
            NightLogEvent(
                Instant.parse("2026-09-18T04:25:00Z"), "night_end",
                mapOf("summary" to "total=PT6H50M stretches=5", "totalSleep" to "PT6H50M", "stretches" to "not json at all")
            )
        )

        val parsed = parsePastNightLog(listOf(corrupt))

        assertEquals(PastNightSummary.TotalOnly(Duration.ofHours(6).plusMinutes(50), 5), parsed.summary)
    }

    @Test
    fun `unparseable and unrelated lines are skipped rather than failing the whole log`() {
        val lines = listOf(
            "{ this line was truncated mid-write",
            "",
            """{"at":"2026-09-18T04:11:08Z","type":"tick","fields":{"scheduledFor":"immediate"}}""",
            REAL_NIGHT_START_LINE,
            REAL_OLD_FORMAT_NIGHT_END_LINE
        )

        val parsed = parsePastNightLog(lines)

        assertTrue(parsed.summary is PastNightSummary.TotalOnly)
        assertEquals(5, parsed.pickedCycles)
    }

    @Test
    fun `a fast simulated night is read back as a fast night`() {
        val settings = NightSettings(deadline = null, pickedCycles = 3)

        val parsed = parsePastNightLog(listOf(nightStartLine(settings, Instant.parse("2026-09-18T04:11:05Z"), fastNight = true)))

        assertEquals(true, parsed.fastNight)
    }

    @Test
    fun `an empty log reads as a night that recorded nothing`() {
        val parsed = parsePastNightLog(emptyList())

        assertEquals(PastNightSummary.Missing, parsed.summary)
        assertNull(parsed.startedAt)
        assertNull(parsed.endedAt)
        assertNull(parsed.deadline)
        assertNull(parsed.pickedCycles)
        assertEquals(false, parsed.fastNight)
    }
}
