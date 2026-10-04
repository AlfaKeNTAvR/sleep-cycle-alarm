package com.nikita.sleepcycle.scenario

// File purpose: ISSUES.md #4 (round2 #3) - the alarm rings on the phone's ALARM stream, a slider of its own that
// nothing checked: at zero the alarm is vibrate-only. Owner decision, 2026-10-03: Start night's readiness check
// and Setup warn when it is low (under half its range), and while ringing it is raised to at least half its
// range and put back when the ring stops.

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.nikita.sleepcycle.alarm.AlarmRingService
import com.nikita.sleepcycle.alarm.EXTRA_ALARM_LABEL
import com.nikita.sleepcycle.alarm.AlarmLabel
import com.nikita.sleepcycle.alarm.stopAlarmRinging
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.night.cancelTick
import com.nikita.sleepcycle.night.endNight
import com.nikita.sleepcycle.night.nowInstant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmVolumeScenarioTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audioManager = context.getSystemService(AudioManager::class.java)

    // Seven alarm steps, as on most phones: half the range is 3.5, so the floor is step 4.
    @Before
    fun setUp() = shadowOf(audioManager).setStreamMaxVolume(7)

    @After
    fun tearDown() = runBlocking {
        endNight(context, nowInstant())
        cancelTick(context)
    }

    private var alarmVolume: Int
        get() = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        set(step) = audioManager.setStreamVolume(AudioManager.STREAM_ALARM, step, 0)

    @Test
    fun `a ring raises a silent alarm stream to half its range and puts it back when stopped`() {
        alarmVolume = 0
        val ring = Robolectric.buildService(
            AlarmRingService::class.java, Intent(context, AlarmRingService::class.java).putExtra(EXTRA_ALARM_LABEL, AlarmLabel.MORNING.name)
        ).create().startCommand(0, 1)
        assertEquals("ringing at no less than half the range", 4, alarmVolume)

        stopAlarmRinging(context)
        ring.withIntent(checkNotNull(shadowOf(context as android.app.Application).nextStartedService)).startCommand(0, 2)
        assertEquals("the owner's own alarm volume is back", 0, alarmVolume)
    }

    @Test
    fun `a ring leaves an alarm stream already at or above half its range alone`() {
        alarmVolume = 6
        Robolectric.buildService(AlarmRingService::class.java, Intent(context, AlarmRingService::class.java)).create().startCommand(0, 1)
        assertEquals(6, alarmVolume)
    }

    @Test
    fun `Start night's readiness check records a low alarm volume`() {
        alarmVolume = 3
        startNightAndWait(context, NightSettings(deadline = null, pickedCycles = 5), Instant.now())
        val readiness = nightLogText(context).lineSequence().first { it.contains("\"alarm_readiness\"") }
        assertTrue(readiness, readiness.contains("\"alarmVolume\":\"3\""))
        assertTrue(readiness, readiness.contains("\"alarmVolumeMax\":\"7\""))
        assertTrue(readiness, readiness.contains("\"alarmVolumeLow\":\"true\""))
    }
}
