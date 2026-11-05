package com.nikita.sleepcycle.scenario

// File purpose: Q2 - the Night screen's alarm editor, confirmed, end to end through changeNightSettings: the
// phone alarm moves to the new plan (or is cancelled for No alarm), the night keeps running with its history,
// the log records old and new values, and a refused change leaves the armed alarm exactly as it was.

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nikita.sleepcycle.alarm.EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI
import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.night.AppClock
import com.nikita.sleepcycle.night.ClockWarp
import com.nikita.sleepcycle.night.NightSettingsChangeResult
import com.nikita.sleepcycle.night.NightSettingsRefusal
import com.nikita.sleepcycle.night.cancelTick
import com.nikita.sleepcycle.night.changeNightSettings
import com.nikita.sleepcycle.night.endNight
import com.nikita.sleepcycle.night.loadNightState
import com.nikita.sleepcycle.night.nowInstant
import com.nikita.sleepcycle.night.parseNightLogLine
import com.nikita.sleepcycle.night.pressImUp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChangeNightSettingsScenarioTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = EngineConfig()

    @After
    fun tearDown() = runBlocking {
        endNight(context, nowInstant())
        cancelTick(context)
        AppClock.setWarp(null)
    }

    /** No band data yet: the alarm is [at] + the fall-asleep estimate + [cycles] cycles. */
    private fun estimate(at: Instant, cycles: Long): Instant =
        at.plus(config.fallAsleepEstimate).plus(config.cycleLength.multipliedBy(cycles))

    private fun events(type: String) = nightLogText(context).lines().mapNotNull(::parseNightLogLine).filter { it.type == type }

    @Test
    fun `a shorter night moves the armed alarm at once and the night keeps running`() = runBlocking {
        val startedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        startNightAndWait(context, NightSettings(deadline = null, pickedCycles = 5), startedAt)
        assertEquals(estimate(startedAt, 5), armedPhoneAlarmAt(context))

        val changeAt = startedAt.plus(Duration.ofMinutes(10))
        val result = changeNightSettings(context, NightSettings(deadline = null, pickedCycles = 4), changeAt)

        assertTrue(result is NightSettingsChangeResult.Applied)
        assertEquals("the alarm moved to the new plan", estimate(changeAt, 4), armedPhoneAlarmAt(context))
        val state = checkNotNull(loadNightState(context))
        assertEquals("the same night, not a new one", startedAt, state.startedAt)
        assertEquals(4, state.settings.pickedCycles)
        assertEquals(estimate(changeAt, 4), state.lastPlan?.wakeAt)

        val changed = events("settings_changed").single()
        assertEquals("5", changed.fields["oldPickedCycles"])
        assertEquals("4", changed.fields["newPickedCycles"])
        assertEquals(estimate(startedAt, 5).toString(), changed.fields["oldWakeAt"])
        assertEquals(estimate(changeAt, 4).toString(), changed.fields["newWakeAt"])
        assertEquals("none", changed.fields["newDeadline"])
        assertEquals(1, events("night_start").size)
    }

    @Test
    fun `an earlier wake-by time caps the armed alarm`() = runBlocking {
        val startedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        startNightAndWait(context, NightSettings(deadline = startedAt.plus(Duration.ofHours(9)), pickedCycles = 5), startedAt)

        // 15 min + 4 cycles = 6 h 15 min fits before a 7 h wake-by time, 5 cycles do not.
        val deadline = startedAt.plus(Duration.ofHours(7))
        val result = changeNightSettings(context, NightSettings(deadline = deadline, pickedCycles = 5), startedAt)

        assertTrue(result is NightSettingsChangeResult.Applied)
        assertEquals(estimate(startedAt, 4), armedPhoneAlarmAt(context))
        assertEquals(deadline.toString(), events("settings_changed").single().fields["newDeadline"])
    }

    @Test
    fun `switching to No alarm cancels the armed alarm, and switching back arms one`() = runBlocking {
        val startedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        startNightAndWait(context, NightSettings(deadline = null, pickedCycles = 5), startedAt)
        assertNotNull(armedPhoneAlarmAt(context))

        changeNightSettings(context, NightSettings(deadline = null, pickedCycles = 5, noAlarm = true), startedAt)
        assertNull("No alarm leaves nothing armed", armedPhoneAlarmAt(context))
        assertEquals(AlarmMode.NO_ALARM, loadNightState(context)?.lastPlan?.mode)

        val backAt = startedAt.plus(Duration.ofMinutes(5))
        changeNightSettings(context, NightSettings(deadline = null, pickedCycles = 5), backAt)
        assertEquals(estimate(backAt, 5), armedPhoneAlarmAt(context))
        assertEquals(listOf("true", "false"), events("settings_changed").map { it.fields["newNoAlarm"] })
    }

    @Test
    fun `a wake-by time already passed is refused and the armed alarm stays`() = runBlocking {
        val startedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        startNightAndWait(context, NightSettings(deadline = null, pickedCycles = 5), startedAt)
        val armedBefore = armedPhoneAlarmAt(context)

        val result = changeNightSettings(context, NightSettings(deadline = startedAt.minusSeconds(60), pickedCycles = 5), startedAt)

        assertEquals(NightSettingsChangeResult.Refused(NightSettingsRefusal.DEADLINE_PASSED), result)
        assertEquals(armedBefore, armedPhoneAlarmAt(context))
        assertEquals(NightSettings(deadline = null, pickedCycles = 5), loadNightState(context)?.settings)
        assertEquals("deadline_passed", events("settings_change_refused").single().fields["cause"])
    }

    @Test
    fun `after the morning alarm rang a change is refused and the nudge stays armed`() = runBlocking {
        val startedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        startNightAndWait(context, NightSettings(deadline = null, pickedCycles = 5), startedAt)
        val morningAt = checkNotNull(armedPhoneAlarmAt(context))
        AppClock.setWarp(ClockWarp(1, Instant.now(), morningAt))
        firePhoneAlarm(context)
        val nudge = armedAlarmIntent(context, outOfBed = true)
        assertNotNull("the ring armed the nudge", nudge)

        val result = changeNightSettings(context, NightSettings(deadline = null, pickedCycles = 6), nowInstant())

        assertEquals(NightSettingsChangeResult.Refused(NightSettingsRefusal.MORNING_ALARM_RANG), result)
        // The test AlarmManager keeps a fired alarm listed; what matters is that nothing was armed in its place.
        assertEquals("the plan's slot is not re-armed", morningAt, armedPhoneAlarmAt(context))
        assertEquals(
            "the nudge is untouched",
            nudge?.getLongExtra(EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI, -1L),
            armedAlarmIntent(context, outOfBed = true)?.getLongExtra(EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI, -1L),
        )
        assertEquals(5, loadNightState(context)?.settings?.pickedCycles)
    }

    @Test
    fun `after I'm up a change is refused`() = runBlocking {
        val startedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        startNightAndWait(context, NightSettings(deadline = null, pickedCycles = 5), startedAt)
        assertTrue(pressImUp(context, nowInstant()))

        val result = changeNightSettings(context, NightSettings(deadline = null, pickedCycles = 4), nowInstant())

        assertEquals(NightSettingsChangeResult.Refused(NightSettingsRefusal.MORNING_ALARM_RANG), result)
        assertNull(armedPhoneAlarmAt(context))
    }

    @Test
    fun `the same settings change nothing and log nothing`() = runBlocking {
        val startedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        startNightAndWait(context, NightSettings(deadline = null, pickedCycles = 5), startedAt)

        val result = changeNightSettings(context, NightSettings(deadline = null, pickedCycles = 5), startedAt.plusSeconds(60))

        assertEquals(NightSettingsChangeResult.Unchanged, result)
        assertEquals(estimate(startedAt, 5), armedPhoneAlarmAt(context))
        assertTrue(events("settings_changed").isEmpty())
    }
}
