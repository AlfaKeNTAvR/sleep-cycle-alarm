package com.nikita.sleepcycle.scenario

// File purpose: what happens the moment the phone alarm fires.
//  - ISSUES.md #3 (round2 #2): the ring starts FIRST. PhoneAlarmReceiver used to write the fired markers, arm
//    the nudge and log before it started AlarmRingService, so a process kill in between left a night that
//    believed its alarm had rung, with no sound.
//  - ISSUES.md #5 (round2 #7): a fired-marker write that fails must not turn the spent morning alarm into a
//    fresh alarm two minutes out on every tick.

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.nikita.sleepcycle.alarm.AlarmRingService
import com.nikita.sleepcycle.alarm.EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI
import com.nikita.sleepcycle.alarm.EXTRA_RING_AUTO_STOP_AFTER_MILLIS
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.night.AppClock
import com.nikita.sleepcycle.night.ClockWarp
import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.SimulatedSleepEventKind
import com.nikita.sleepcycle.night.appendSimulatedSleepEvent
import com.nikita.sleepcycle.night.cancelTick
import com.nikita.sleepcycle.night.endNight
import com.nikita.sleepcycle.night.nowInstant
import com.nikita.sleepcycle.night.readPhoneAlarmFiredFor
import com.nikita.sleepcycle.night.runImmediateTick
import com.nikita.sleepcycle.night.updateSimulatedSleepEvents
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmFiringScenarioTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = runBlocking {
        endNight(context, nowInstant())
        cancelTick(context)
        AppClock.setWarp(null)
    }

    /** Notes what the night had already written to disk at the moment the ring service was asked to start. */
    private class RingWatchingContext(base: Context) : ContextWrapper(base) {
        var ringIntent: Intent? = null
        var firedMarkerWhenRingStarted: Instant? = null

        override fun startForegroundService(service: Intent): ComponentName? {
            if (service.component?.className == AlarmRingService::class.java.name && ringIntent == null) {
                ringIntent = service
                firedMarkerWhenRingStarted = readPhoneAlarmFiredFor(baseContext)
            }
            return super.startForegroundService(service)
        }
    }

    /** A simulated night asleep since [onset]: five cycles, so the morning alarm is onset + 7.5 h. */
    private fun startNightAsleepSince(onset: Instant) = runBlocking {
        updateSimulatedSleepEvents(context) { emptyList() }
        startNightAndWait(context, NightSettings(deadline = null, pickedCycles = 5), onset, DebugOptions(simulatedBandData = true))
        updateSimulatedSleepEvents(context) { appendSimulatedSleepEvent(it, SimulatedSleepEventKind.ASLEEP, onset) }
        runImmediateTick(context)
    }

    @Test
    fun `the ring starts before any of the firing's bookkeeping is written`() = runBlocking {
        val onset = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        startNightAsleepSince(onset)
        val alarm = checkNotNull(armedAlarmIntent(context))
        val morningAt = Instant.ofEpochMilli(alarm.getLongExtra(EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI, -1L))
        AppClock.setWarp(ClockWarp(1, Instant.now(), morningAt))

        val watching = RingWatchingContext(context)
        firePhoneAlarm(watching, alarm)

        val ring = checkNotNull(watching.ringIntent) { "the ring service was never started" }
        assertNull("nothing was marked fired before the ring started", watching.firedMarkerWhenRingStarted)
        assertEquals("the 9 minute auto-stop reaches the ring", Duration.ofMinutes(9).toMillis(), ring.getLongExtra(EXTRA_RING_AUTO_STOP_AFTER_MILLIS, -1L))
        assertEquals("the bookkeeping still runs, after the ring", morningAt, readPhoneAlarmFiredFor(context))
    }

    @Test
    fun `a morning alarm whose fired markers could not be saved is not rung again two minutes later`() = runBlocking {
        val onset = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        startNightAsleepSince(onset)
        val alarm = checkNotNull(armedAlarmIntent(context))
        val morningAt = Instant.ofEpochMilli(alarm.getLongExtra(EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI, -1L))
        assertEquals(onset.plus(Duration.ofMinutes(450)).toEpochMilli(), morningAt.toEpochMilli())

        // Every fired-marker write fails: a directory sits where each store writes its temp file first.
        File(context.filesDir, "phone_alarm_fired.txt.tmp").mkdirs()
        File(context.filesDir, "wake_alarm_fired.txt.tmp").mkdirs()
        AppClock.setWarp(ClockWarp(1, Instant.now(), morningAt))
        firePhoneAlarm(context, alarm)
        assertTrue(nightLogText(context).contains("failed to persist phoneAlarmFiredFor"))

        // Still asleep three minutes later: the spent morning target must not come back as a fresh alarm.
        AppClock.setWarp(ClockWarp(1, Instant.now(), morningAt.plus(Duration.ofMinutes(3))))
        runImmediateTick(context)
        assertNull("the morning alarm that already rang is not armed again", armedPhoneAlarmAt(context))
    }
}
