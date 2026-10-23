package com.nikita.sleepcycle.night

// File purpose: PER-151 - the "should the Are you up? ask go up now" rule (AreYouUpAsk.kt's
// findAwakeningToAskAreYouUp), on hand-built band marks: only a No-alarm night, only after 6 h asleep, only
// once an awakening has lasted 15 min, never after I'm up, and once per awakening.

import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.engine.SegmentKind
import com.nikita.sleepcycle.engine.SleepSegment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class AreYouUpRuleTest {
    private val config = EngineConfig()

    /** A clock time on the night of 22 to 23 October: from 18:00 on it is the evening before. */
    private fun at(clockTime: String): Instant {
        val day = if (clockTime >= "18:00") "2026-10-22" else "2026-10-23"
        return Instant.parse("${day}T${clockTime}:00Z")
    }

    private fun asleep(from: String, to: String) = SleepSegment(at(from), at(to), SegmentKind.LIGHT)
    private fun awake(from: String, to: String) = SleepSegment(at(from), at(to), SegmentKind.AWAKE)

    /** 6 h 30 min asleep, 23:30 to 06:00, then awake from 06:00 for as long as [awakeUntil]. */
    private fun longNightThenAwake(awakeUntil: String) = listOf(asleep("23:30", "06:00"), awake("06:00", awakeUntil))

    private fun find(
        segments: List<SleepSegment>,
        now: String,
        noAlarmNight: Boolean = true,
        imUpPressed: Boolean = false,
        previousAsk: AreYouUpAsk? = null,
    ): Instant? = findAwakeningToAskAreYouUp(noAlarmNight, imUpPressed, previousAsk, segments, at(now), config)

    @Test
    fun `asks once a morning awakening after 6 h asleep has lasted 15 min`() {
        assertEquals(at("06:00"), find(longNightThenAwake("06:15"), now = "06:16"))
    }

    @Test
    fun `does not ask while the awakening is shorter than 15 min`() {
        assertNull(find(longNightThenAwake("06:14"), now = "06:16"))
    }

    @Test
    fun `does not ask before 6 h asleep in total`() {
        val segments = listOf(asleep("00:30", "06:00"), awake("06:00", "06:30"))

        assertNull(find(segments, now = "06:31"))
    }

    @Test
    fun `counts the sleep of every stretch that night toward the 6 h`() {
        val segments = listOf(
            asleep("23:00", "02:00"), awake("02:00", "02:20"),
            asleep("02:20", "05:20"), awake("05:20", "05:40"),
        )

        assertEquals(at("05:20"), find(segments, now = "05:41"))
    }

    @Test
    fun `does not ask while asleep`() {
        assertNull(find(listOf(asleep("23:30", "07:00")), now = "07:01"))
    }

    @Test
    fun `never asks on a night with a morning alarm`() {
        assertNull(find(longNightThenAwake("06:30"), now = "06:31", noAlarmNight = false))
    }

    @Test
    fun `never asks once I'm up has been pressed`() {
        assertNull(find(longNightThenAwake("06:30"), now = "06:31", imUpPressed = true))
    }

    @Test
    fun `an ask still open is not raised a second time`() {
        val open = AreYouUpAsk(awakeningStartedAt = at("06:00"), askedAt = at("06:16"))

        assertNull(find(longNightThenAwake("06:45"), now = "06:46", previousAsk = open))
    }

    @Test
    fun `Still sleeping is not asked again for the same awakening`() {
        val answered = AreYouUpAsk(at("06:00"), at("06:16"), AreYouUpAnswer.STILL_SLEEPING, at("06:17"))

        assertNull(find(longNightThenAwake("06:45"), now = "06:46", previousAsk = answered))
    }

    @Test
    fun `after Still sleeping, a later separate awakening of 15 min is asked again`() {
        val answered = AreYouUpAsk(at("06:00"), at("06:16"), AreYouUpAnswer.STILL_SLEEPING, at("06:17"))
        val segments = listOf(asleep("23:30", "06:00"), awake("06:00", "06:20"), asleep("06:20", "07:30"), awake("07:30", "07:45"))

        assertEquals(at("07:30"), find(segments, now = "07:46", previousAsk = answered))
    }

    @Test
    fun `after Still sleeping, a later awakening still under 15 min is not asked`() {
        val answered = AreYouUpAsk(at("06:00"), at("06:16"), AreYouUpAnswer.STILL_SLEEPING, at("06:17"))
        val segments = listOf(asleep("23:30", "06:00"), awake("06:00", "06:20"), asleep("06:20", "07:30"), awake("07:30", "07:40"))

        assertNull(find(segments, now = "07:41", previousAsk = answered))
    }
}
