package com.nikita.sleepcycle.scenario

// File purpose: owner spec, 2026-10-21 (PER-126) - a night discarded under 1 h after Start night. "If the sleep
// was shorter than 1 hour, then it shouldn't ask me any questions... doesn't show me the final screen... and
// doesn't save the log because it doesn't matter unless it was a simulation." Every alarm of the night is
// cancelled and the night service stops, exactly as End night does; there is no morning report and no log (a
// simulation keeps its log, with no night_end so no later rating question is ever armed for it). Drives the
// real NightController.discardNight, the alarm slots in AlarmManager, the state files and the media volume.

import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.night.AppClock
import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.SleepRatingSettings
import com.nikita.sleepcycle.night.cancelTick
import com.nikita.sleepcycle.night.discardNight
import com.nikita.sleepcycle.night.endNight
import com.nikita.sleepcycle.night.laterRatingAskAt
import com.nikita.sleepcycle.night.listNightLogs
import com.nikita.sleepcycle.night.loadMorningReport
import com.nikita.sleepcycle.night.loadNightState
import com.nikita.sleepcycle.night.nowInstant
import com.nikita.sleepcycle.night.pressImUp
import com.nikita.sleepcycle.night.readPastNightLog
import com.nikita.sleepcycle.night.readPendingFollowUp
import com.nikita.sleepcycle.night.runMediaFadeCheck
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiscardShortNightScenarioTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val noDeadline = NightSettings(deadline = null, pickedCycles = 5)

    @Before
    fun setUp() {
        shadowOf(audioManager).setStreamMaxVolume(15)
    }

    @After
    fun tearDown() = runBlocking {
        endNight(context, nowInstant())
        listNightLogs(context).forEach { it.delete() }
        cancelTick(context)
        AppClock.setWarp(null)
    }

    @Test
    fun `a real night discarded ten minutes in cancels its alarm and leaves no report and no log`() = runBlocking {
        startNightAndWait(context, noDeadline, Instant.now().minus(Duration.ofMinutes(10)))
        assertNotNull("Start night arms the morning alarm", armedPhoneAlarmAt(context))

        assertNotNull(discardNight(context, nowInstant()))

        assertNull("the morning alarm is cancelled", armedPhoneAlarmAt(context))
        assertNull("the night is over", loadNightState(context))
        assertNull("no morning report", loadMorningReport(context))
        assertEquals("no night log in Logs", emptyList<String>(), listNightLogs(context).map { it.name })
    }

    @Test
    fun `discarding after I'm up armed the out-of-bed nudge cancels the nudge too`() = runBlocking {
        startNightAndWait(context, noDeadline, Instant.now().minus(Duration.ofMinutes(10)))
        assertTrue(pressImUp(context, nowInstant()))
        assertNotNull("I'm up arms the nudge", armedAlarmIntent(context, outOfBed = true))

        discardNight(context, nowInstant())

        assertNull("the nudge is cancelled", armedAlarmIntent(context, outOfBed = true))
        assertNull("and its pending record cleared", readPendingFollowUp(context))
    }

    @Test
    fun `discarding a night puts the owner's own media volume back, as End night does`() = runBlocking {
        startNightAndWait(context, noDeadline, Instant.now().minus(Duration.ofMinutes(10)))
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 10, 0)
        shadowOf(audioManager).setIsMusicActive(true)
        // Owner, 2026-10-21 (PER-134): a fade begins only with the screen off.
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        runMediaFadeCheck(context)
        assertTrue("the fade lowered the volume", audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) < 10)

        discardNight(context, nowInstant())

        assertEquals(10, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals("the restore leaves no log behind either", emptyList<String>(), listNightLogs(context).map { it.name })
    }

    @Test
    fun `a simulated night discarded keeps its log, marked discarded, and is never asked about later`() = runBlocking {
        startNightAndWait(context, noDeadline, Instant.now().minus(Duration.ofMinutes(10)), DebugOptions(simulatedBandData = true))

        discardNight(context, nowInstant())

        val log = listNightLogs(context).single()
        assertTrue(log.name.startsWith("night-sim-"))
        val text = log.readText()
        assertTrue("the log says the night was discarded", text.contains("\"night_discarded\""))
        assertFalse("no night_end, so no summary and no later rating question", text.contains("\"night_end\""))
        assertNull(laterRatingAskAt(readPastNightLog(log), SleepRatingSettings(), ZoneId.systemDefault(), Instant.now()))
        assertNull("no morning report", loadMorningReport(context))
        assertNull(armedPhoneAlarmAt(context))
    }
}
