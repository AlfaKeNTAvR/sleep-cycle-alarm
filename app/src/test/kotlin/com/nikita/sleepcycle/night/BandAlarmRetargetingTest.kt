package com.nikita.sleepcycle.night

// File purpose: the band-write filter from night 1 (BandAlarmRetargeting.kt). The engine keeps projecting a
// new onset 15 min ahead every time the band marks a short awakening, which walked the band alarm 77 min
// across the night; the band must instead keep the time it already has until a real onset moves it.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.AlarmPlan
import com.nikita.sleepcycle.engine.SleepState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private val ZONE_UTC: ZoneId = ZoneOffset.UTC
private val NOW = Instant.parse("2026-09-18T06:34:00Z")

class BandAlarmRetargetingTest {

    @Test
    fun `with nothing on the band yet, a projected onset still sends the first alarm`() {
        val plan = planAt(AlarmMode.FULL_CYCLES, at(11, 19), projected = true)

        val desired = resolveDesiredBandAlarm(plan, SleepState.NOT_YET_ASLEEP, heldCommitment = null, now = NOW, zone = ZONE_UTC)

        assertEquals(at(11, 19), desired, "the first alarm of the night must exist, projected onset or not")
        assertFalse(isBandAlarmHeld(plan, desired))
    }

    @Test
    fun `awake with an alarm already on the band, the band keeps its own time`() {
        // Night 1, 06:34:33Z: the band marked a short awakening, the engine projected a new onset and dropped
        // to 3 cycles, and the alarm was walked from 07:50 back to 07:19. This is the tick that must do nothing.
        val plan = planAt(AlarmMode.FULL_CYCLES, at(11, 19), projected = true)
        val held = BandAlarmCommitment(BAND_ALARM_TITLE, 7, 50, NOW.minusSeconds(900))

        val desired = resolveDesiredBandAlarm(plan, SleepState.AWAKE, held, NOW, ZONE_UTC)

        assertEquals(at(7, 50), desired, "held at what the band already carries, in local minutes")
        assertTrue(isBandAlarmHeld(plan, desired))
    }

    @Test
    fun `not yet asleep with an alarm already on the band also holds`() {
        val plan = planAt(AlarmMode.FULL_CYCLES, at(12, 6), projected = true)
        val held = BandAlarmCommitment(BAND_ALARM_TITLE, 7, 56, NOW.minusSeconds(300))

        val desired = resolveDesiredBandAlarm(plan, SleepState.NOT_YET_ASLEEP, held, NOW, ZONE_UTC)

        assertEquals(at(7, 56), desired)
    }

    @Test
    fun `asleep on a real onset moves the alarm, which is the only thing that ever should`() {
        val plan = planAt(AlarmMode.FULL_CYCLES, at(11, 53), projected = false)
        val held = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 6, NOW.minusSeconds(900))

        val desired = resolveDesiredBandAlarm(plan, SleepState.ASLEEP, held, NOW, ZONE_UTC)

        assertEquals(at(11, 53), desired)
        assertFalse(isBandAlarmHeld(plan, desired))
    }

    @Test
    fun `a nap while awake keeps sliding, because a frozen nap alarm would be in the past by the time sleep returns`() {
        val plan = planAt(AlarmMode.NAP, NOW.plusSeconds(1200), projected = true)
        val held = BandAlarmCommitment(BAND_ALARM_TITLE, 6, 40, NOW.minusSeconds(900))

        val desired = resolveDesiredBandAlarm(plan, SleepState.AWAKE, held, NOW, ZONE_UTC)

        assertEquals(NOW.plusSeconds(1200), desired)
    }

    @Test
    fun `a held time that has already passed is released, so the rest of the night still gets an alarm`() {
        val plan = planAt(AlarmMode.FULL_CYCLES, at(12, 6), projected = true)
        val held = BandAlarmCommitment(BAND_ALARM_TITLE, 6, 0, NOW.minusSeconds(3600))
        // 06:00 is behind NOW (06:34), so the band would next buzz at it almost a full day from now - further
        // out than any night this app can plan, which is how a passed hold is told apart from a live one.
        val desired = resolveDesiredBandAlarm(plan, SleepState.AWAKE, held, NOW, ZONE_UTC)

        assertEquals(at(12, 6), desired)
    }

    @Test
    fun `a night that crosses midnight still holds, because the wrapped minute is within one plannable night`() {
        val lateEvening = Instant.parse("2026-09-17T23:50:00Z")
        val plan = planAt(AlarmMode.FULL_CYCLES, Instant.parse("2026-09-18T08:05:00Z"), projected = true)
        val held = BandAlarmCommitment(BAND_ALARM_TITLE, 7, 30, lateEvening.minusSeconds(300))

        val desired = resolveDesiredBandAlarm(plan, SleepState.NOT_YET_ASLEEP, held, lateEvening, ZONE_UTC)

        assertEquals(Instant.parse("2026-09-18T07:30:00Z"), desired)
    }

    @Test
    fun `FINISHED still wants no alarm at all, held commitment or not`() {
        val plan = planAt(AlarmMode.FINISHED, null, projected = false)
        val held = BandAlarmCommitment(BAND_ALARM_TITLE, 8, 17, NOW.minusSeconds(900))

        assertEquals(null, resolveDesiredBandAlarm(plan, SleepState.AWAKE, held, NOW, ZONE_UTC))
    }

    @Test
    fun `a whole awake stretch of night 1 produces no band write at all`() {
        // The real sequence from 06:34:33Z to 07:34:28Z: projected onsets sliding forward, cycles flipping
        // between 3 and 2, five ticks, five different plans - and one unchanged band alarm.
        val held = BandAlarmCommitment(BAND_ALARM_TITLE, 7, 50, NOW.minusSeconds(900))
        val plannedTargets = listOf(at(11, 19), at(11, 34), at(11, 56), at(12, 11), at(12, 19))

        plannedTargets.forEachIndexed { index, target ->
            val plan = planAt(AlarmMode.FULL_CYCLES, target, projected = true)
            val tickNow = NOW.plusSeconds(index * 900L)

            val desired = resolveDesiredBandAlarm(plan, SleepState.AWAKE, held, tickNow, ZONE_UTC)

            assertEquals(at(7, 50), desired, "tick $index: the band keeps 07:50 the whole time")
        }
    }

    @Test
    fun `a held minute earlier in the day than now resolves to tomorrow only when it is genuinely ahead`() {
        // 23:50 is still ahead of 06:34 today, so it resolves to today's 23:50, not tomorrow's.
        assertEquals(at(23, 50), nextLocalOccurrence(23, 50, NOW, ZONE_UTC))
        // 06:00 already passed today, so it resolves to tomorrow.
        assertEquals(Instant.parse("2026-09-19T06:00:00Z"), nextLocalOccurrence(6, 0, NOW, ZONE_UTC))
        // Exactly now resolves to now, not tomorrow.
        assertEquals(NOW, nextLocalOccurrence(6, 34, NOW, ZONE_UTC))
    }

    private fun at(hour: Int, minute: Int): Instant =
        Instant.parse("2026-09-18T%02d:%02d:00Z".format(hour, minute))

    private fun planAt(mode: AlarmMode, bandAlarm: Instant?, projected: Boolean): AlarmPlan = AlarmPlan(
        mode = mode,
        bandAlarm = bandAlarm,
        phoneAlarm = null,
        cycles = 3,
        referenceOnset = NOW.plusSeconds(900),
        onsetIsProjected = projected,
        reason = "test"
    )
}
