package com.nikita.sleepcycle.scenario

// File purpose: whole-night scenario tests on Robolectric - the real night code (state file, DataStore,
// AlarmManager) on the JVM, no phone. Phone test, 2026-10-02: on a simulated night the owner switched Asleep off,
// left for the media app and came back; coming back ran an extra immediate sync that re-armed the ordinary
// 15 minute cadence over the check booked for when the awake mark first counts (1 minute), so the awakening,
// and the bedtime fade with it, never happened.

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.night.AfterAlarmSettings
import com.nikita.sleepcycle.night.BedtimeAudioSettings
import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.SimulatedSleepEventKind
import com.nikita.sleepcycle.night.appendSimulatedSleepEvent
import com.nikita.sleepcycle.night.loadNightState
import com.nikita.sleepcycle.night.runImmediateTick
import com.nikita.sleepcycle.night.startNight
import com.nikita.sleepcycle.night.updateSimulatedSleepEvents
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SimulatedAwakeningScenarioTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `an extra sync after switching Asleep off still leaves a sync booked for when the awakening counts`() = runBlocking {
        val now = Instant.now()
        startNight(
            context, NightSettings(deadline = null, pickedCycles = 5), now.minus(Duration.ofMinutes(31)),
            AfterAlarmSettings(), BedtimeAudioSettings(), DebugOptions(simulatedBandData = true),
        )
        withTimeout(10_000) { while (loadNightState(context) == null) delay(20) }

        updateSimulatedSleepEvents(context) { appendSimulatedSleepEvent(it, SimulatedSleepEventKind.ASLEEP, now.minus(Duration.ofMinutes(30))) }
        runImmediateTick(context)
        val wokeAt = Instant.now()
        updateSimulatedSleepEvents(context) { appendSimulatedSleepEvent(it, SimulatedSleepEventKind.AWAKE, wokeAt) }
        runImmediateTick(context)
        // Coming back to the app: one more sync, after the awake mark, before it counts.
        runImmediateTick(context)

        val nextSyncAt = Instant.ofEpochMilli(checkNotNull(shadowOf(context.getSystemService(AlarmManager::class.java)).peekNextScheduledAlarm()).triggerAtTime)
        assertTrue(
            "next sync at $nextSyncAt, but the awakening counts at ${wokeAt.plusSeconds(62)}",
            !nextSyncAt.isAfter(wokeAt.plusSeconds(62)),
        )
    }
}
