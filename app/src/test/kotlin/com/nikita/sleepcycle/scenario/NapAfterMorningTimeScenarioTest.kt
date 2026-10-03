package com.nikita.sleepcycle.scenario

// File purpose: ISSUES.md #6 (deadline.md #3). Owner decision, 2026-10-03: a nap that rings at or after the
// latched morning-alarm time IS the morning alarm. It used to ring labelled "Nap alarm" while the engine, the
// Night screen and I'm up already treated it as the morning alarm, and night_summary (keyed on wakeAlarmFiredAt
// alone) said no wake alarm ever rang.
//
// The night: asleep at T, three cycles (morning alarm T + 4 h 30). Awake at T + 4 h 20 for two minutes, asleep
// again with the picked total all but slept (no cycle owed), so rule 7 arms a nap 20 minutes after that
// return to sleep: T + 4 h 42, twelve minutes after the morning time. That nap takes the morning alarm's slot.

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nikita.sleepcycle.alarm.AlarmLabel
import com.nikita.sleepcycle.alarm.EXTRA_ALARM_LABEL
import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.night.AppClock
import com.nikita.sleepcycle.night.ClockWarp
import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.SimulatedSleepEventKind
import com.nikita.sleepcycle.night.appendSimulatedSleepEvent
import com.nikita.sleepcycle.night.cancelTick
import com.nikita.sleepcycle.night.endNight
import com.nikita.sleepcycle.night.loadNightState
import com.nikita.sleepcycle.night.nowInstant
import com.nikita.sleepcycle.night.runImmediateTick
import com.nikita.sleepcycle.night.updateSimulatedSleepEvents
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
class NapAfterMorningTimeScenarioTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = runBlocking {
        endNight(context, nowInstant())
        cancelTick(context)
        AppClock.setWarp(null)
    }

    private fun minutes(count: Long): Duration = Duration.ofMinutes(count)

    @Test
    fun `a nap ringing after the morning time rings as the morning alarm and the summary counts it as one`() = runBlocking {
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val asleepAt = now.minus(minutes(263))
        updateSimulatedSleepEvents(context) { emptyList() }
        startNightAndWait(context, NightSettings(deadline = null, pickedCycles = 3), asleepAt, DebugOptions(simulatedBandData = true))
        updateSimulatedSleepEvents(context) { appendSimulatedSleepEvent(it, SimulatedSleepEventKind.ASLEEP, asleepAt) }
        runImmediateTick(context)
        assertEquals("the morning alarm, three cycles after falling asleep", asleepAt.plus(minutes(270)), loadNightState(context)?.morningAlarmAt)

        updateSimulatedSleepEvents(context) { appendSimulatedSleepEvent(it, SimulatedSleepEventKind.AWAKE, now.minus(minutes(3))) }
        updateSimulatedSleepEvents(context) { appendSimulatedSleepEvent(it, SimulatedSleepEventKind.ASLEEP, now.minus(minutes(1))) }
        runImmediateTick(context)

        val napAt = now.plus(minutes(19))
        assertEquals(AlarmMode.NAP, loadNightState(context)?.lastPlan?.mode)
        assertEquals(napAt, armedPhoneAlarmAt(context))
        val alarm = checkNotNull(armedAlarmIntent(context))
        assertEquals("it rings under the morning alarm's name", AlarmLabel.MORNING.name, alarm.getStringExtra(EXTRA_ALARM_LABEL))

        AppClock.setWarp(ClockWarp(1, Instant.now(), napAt))
        firePhoneAlarm(context, alarm)
        AppClock.setWarp(ClockWarp(1, Instant.now(), napAt.plus(minutes(25))))
        endNight(context, nowInstant())

        val summary = nightLogText(context).lineSequence().last { it.contains("\"night_summary\"") }
        assertTrue("in bed 25 min after the morning alarm: $summary", summary.contains("\"inBedAfterWakeAlarmMinutes\":\"25\""))
    }
}
