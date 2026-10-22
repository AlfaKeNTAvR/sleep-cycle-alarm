package com.nikita.sleepcycle.scenario

// File purpose: the night state the Night screen draws from (observedNightState) stays true to the disk.
//  - ISSUES.md #10 (deadline.md #2): opening the app runs a disk refresh and an immediate tick side by side.
//    The tick finished the night and published "no night"; the refresh, which had read the disk before that,
//    then published the finished night again, and the screen sat on a dead "Morning alarm 15:35, in 0 min".
//  - ISSUES.md #11 (deadline.md #4): an alarm firing at 1x runs no tick, so the screen kept the pre-firing state
//    and offered both "I'm up" and "Nap" until the next tick. Spec: one choice at a time.
//  - PER-127 (owner request, 2026-10-21; seen 2026-10-03): a ring ending moves the nudge to 10 min after the
//    stop, but nothing told the screen, so it showed the firing's safety-net time for up to a minute.

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.nikita.sleepcycle.alarm.AlarmLabel
import com.nikita.sleepcycle.alarm.AlarmRingService
import com.nikita.sleepcycle.alarm.EXTRA_ALARM_LABEL
import com.nikita.sleepcycle.alarm.EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI
import com.nikita.sleepcycle.alarm.stopAlarmRinging
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.night.AppClock
import com.nikita.sleepcycle.night.ClockWarp
import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.NightState
import com.nikita.sleepcycle.night.PendingFollowUp
import com.nikita.sleepcycle.night.SimulatedSleepEventKind
import com.nikita.sleepcycle.night.appendSimulatedSleepEvent
import com.nikita.sleepcycle.night.buildNightEngineView
import com.nikita.sleepcycle.night.cancelTick
import com.nikita.sleepcycle.night.clearNightState
import com.nikita.sleepcycle.night.endNight
import com.nikita.sleepcycle.night.nowInstant
import com.nikita.sleepcycle.night.observedNightState
import com.nikita.sleepcycle.night.observedPendingFollowUp
import com.nikita.sleepcycle.night.readPendingFollowUp
import com.nikita.sleepcycle.night.refreshNightStateFromDisk
import com.nikita.sleepcycle.night.runImmediateTick
import com.nikita.sleepcycle.night.updateSimulatedSleepEvents
import com.nikita.sleepcycle.night.withNightTransactionLock
import com.nikita.sleepcycle.ui.state.buildNightUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NightScreenFreshnessScenarioTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = runBlocking {
        endNight(context, nowInstant())
        cancelTick(context)
        AppClock.setWarp(null)
    }

    /** A simulated night asleep since [onset]: five cycles, so the morning alarm is onset + 7.5 h. */
    private fun startNightAsleepSince(onset: Instant) = runBlocking {
        updateSimulatedSleepEvents(context) { emptyList() }
        startNightAndWait(context, NightSettings(deadline = null, pickedCycles = 5), onset, DebugOptions(simulatedBandData = true))
        updateSimulatedSleepEvents(context) { appendSimulatedSleepEvent(it, SimulatedSleepEventKind.ASLEEP, onset) }
        runImmediateTick(context)
    }

    /** Waits up to [timeoutMillis] for the observed night state to satisfy [done]; returns it either way. */
    private suspend fun awaitObserved(timeoutMillis: Long = 3_000, done: (NightState?) -> Boolean): NightState? {
        val giveUpAt = System.currentTimeMillis() + timeoutMillis
        while (!done(observedNightState.value) && System.currentTimeMillis() < giveUpAt) delay(20)
        return observedNightState.value
    }

    @Test
    fun `a disk refresh asked for while a night is being finished publishes the finished result, not the night before it`() = runBlocking {
        startNightAsleepSince(Instant.now().truncatedTo(ChronoUnit.MILLIS))
        assertNotNull(awaitObserved { it != null })

        withNightTransactionLock {
            // The app opens while a tick holds the lock and is finishing the night (deadline.md #2).
            refreshNightStateFromDisk(context)
            delay(300)
            clearNightState(context)
        }

        assertNull("the screen's night state matches the disk once the finishing commit is done", awaitObserved { it == null })
    }

    @Test
    fun `an alarm firing with no tick behind it shows at once - Nap, not I'm up`() = runBlocking {
        val onset = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        startNightAsleepSince(onset)
        val alarm = checkNotNull(armedAlarmIntent(context))
        val morningAt = Instant.ofEpochMilli(alarm.getLongExtra(EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI, -1L))
        AppClock.setWarp(ClockWarp(1, Instant.now(), morningAt))

        firePhoneAlarm(context, alarm)

        val state = checkNotNull(awaitObserved { it?.phoneAlarmFiredFor == morningAt })
        val now = nowInstant()
        val screen = checkNotNull(
            buildNightUiState(
                state, buildNightEngineView(state, now), now, ZoneOffset.UTC,
                showingMorningReport = false, morningReportEndedAt = null, confirmingEndNight = false,
                pendingFollowUp = readPendingFollowUp(context),
            )
        )
        assertFalse("I'm up is no longer offered once the morning alarm has rung", screen.showImUpButton)
        assertEquals("the nudge's Nap is the one choice", 20, screen.napButtonMinutes)
        assertEquals(morningAt, state.phoneAlarmFiredFor)
    }

    /** Waits up to [timeoutMillis] for the published follow-up record to satisfy [done]; returns it either way. */
    private suspend fun awaitObservedFollowUp(timeoutMillis: Long = 3_000, done: (PendingFollowUp?) -> Boolean): PendingFollowUp? {
        val giveUpAt = System.currentTimeMillis() + timeoutMillis
        while (!done(observedPendingFollowUp.value) && System.currentTimeMillis() < giveUpAt) delay(20)
        return observedPendingFollowUp.value
    }

    /**
     * Fires the morning alarm with no tick behind it and starts its ring, the way PhoneAlarmReceiver does.
     * Returns the ring and the firing's safety-net nudge, once the screen has been told about that nudge.
     */
    private fun ringMorningAlarm(): Pair<ServiceController<AlarmRingService>, PendingFollowUp> = runBlocking {
        startNightAsleepSince(Instant.now().truncatedTo(ChronoUnit.MILLIS))
        val alarm = checkNotNull(armedAlarmIntent(context))
        val morningAt = Instant.ofEpochMilli(alarm.getLongExtra(EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI, -1L))
        AppClock.setWarp(ClockWarp(1, Instant.now(), morningAt))
        firePhoneAlarm(context, alarm)
        val safetyNet = checkNotNull(readPendingFollowUp(context))
        assertEquals("the firing's own refresh shows the safety net", safetyNet, awaitObservedFollowUp { it == safetyNet })
        val ring = Robolectric.buildService(
            AlarmRingService::class.java, Intent(context, AlarmRingService::class.java).putExtra(EXTRA_ALARM_LABEL, AlarmLabel.MORNING.name)
        ).create().startCommand(0, 1)
        ring to safetyNet
    }

    /** Two minutes into the ring, so the nudge measured from the stop is clearly not the safety net. */
    private fun twoMinutesIntoTheRing() = AppClock.setWarp(ClockWarp(1, Instant.now(), nowInstant().plus(Duration.ofMinutes(2))))

    /** The screen's follow-up is the record the ring's end wrote, not the firing's safety net. */
    private fun assertScreenShowsNudgeFromRingEnd(safetyNet: PendingFollowUp) = runBlocking {
        val moved = checkNotNull(readPendingFollowUp(context))
        assertFalse("the ring's end moved the nudge", moved == safetyNet)
        assertEquals("the screen shows the moved nudge at once", moved, awaitObservedFollowUp { it == moved })
    }

    @Test
    fun `Stop from the notification shows the nudge's new time at once`() {
        val (ring, safetyNet) = ringMorningAlarm()
        twoMinutesIntoTheRing()

        val stopAction = checkNotNull(shadowOf(ring.get()).lastForegroundNotification.actions).first { action ->
            shadowOf(action.actionIntent).savedIntent.component?.className == AlarmRingService::class.java.name
        }
        ring.withIntent(shadowOf(stopAction.actionIntent).savedIntent).startCommand(0, 2)

        assertScreenShowsNudgeFromRingEnd(safetyNet)
    }

    @Test
    fun `Stop from the ring window shows the nudge's new time at once`() {
        val (ring, safetyNet) = ringMorningAlarm()
        twoMinutesIntoTheRing()

        // The night's own services were started first; only the Stop this screen sends is wanted here.
        shadowOf(context as Application).clearStartedServices()
        stopAlarmRinging(context)
        ring.withIntent(checkNotNull(shadowOf(context as Application).nextStartedService)).startCommand(0, 2)

        assertScreenShowsNudgeFromRingEnd(safetyNet)
    }

    @Test
    fun `a ring that stops itself shows the nudge's new time at once`() {
        val (_, safetyNet) = ringMorningAlarm()

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(10))

        assertScreenShowsNudgeFromRingEnd(safetyNet)
    }
}
