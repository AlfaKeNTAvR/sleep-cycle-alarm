package com.nikita.sleepcycle.scenario

// File purpose: the bedtime fade through a whole simulated night on Robolectric - the real tick, the 1 s fade
// check NightService runs on media and volume changes, the state files and the media volume - no phone.
// Emulator audit, 2026-10-03: until now only the fade's pure decisions were tested, never the sequence of
// starting, pulling back, parking on sleep, a fresh fade after it and End night putting the volume back.

import android.app.AlarmManager
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.night.AfterAlarmSettings
import com.nikita.sleepcycle.night.AppClock
import com.nikita.sleepcycle.night.BedtimeAudioSettings
import com.nikita.sleepcycle.night.ClockWarp
import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.SimulatedSleepEventKind
import com.nikita.sleepcycle.night.appendSimulatedSleepEvent
import com.nikita.sleepcycle.night.cancelTick
import com.nikita.sleepcycle.night.endNight
import com.nikita.sleepcycle.night.listNightLogs
import com.nikita.sleepcycle.night.loadNightState
import com.nikita.sleepcycle.night.nowInstant
import com.nikita.sleepcycle.night.readAppSettings
import com.nikita.sleepcycle.night.runImmediateTick
import com.nikita.sleepcycle.night.runMediaFadeCheck
import com.nikita.sleepcycle.night.startNight
import com.nikita.sleepcycle.night.updateSimulatedSleepEvents
import com.nikita.sleepcycle.night.writeAppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaFadeScenarioTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val audio = shadowOf(audioManager)

    // A 15-step media range, like the emulator: the default starting volume 25% is step 4 (3.75), the
    // default ending volume 5% is the 1-step floor.
    private val startStep = 4

    private var mediaApp: Job? = null

    @Before
    fun setUp() {
        audio.setStreamMaxVolume(15)
    }

    @After
    fun tearDown() {
        mediaApp?.cancel()
        cancelTick(context)
        AppClock.setWarp(null)
    }

    private val volume get() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    private fun setVolume(step: Int) = audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)

    /** Starts playback in a media app that obeys the pause key, as an audiobook app does. */
    private fun play() {
        audio.setIsMusicActive(true)
        audio.clearDispatchedMediaKeyEvents()
        mediaApp?.cancel()
        mediaApp = CoroutineScope(Dispatchers.Default).launch {
            while (audio.dispatchedMediaKeyEvents.isEmpty()) delay(20)
            audio.setIsMusicActive(false)
        }
    }

    private fun nightLog(): String = listNightLogs(context).first().readText()

    private fun startSimulatedNight(startedAt: Instant, deadline: Instant? = null) = runBlocking {
        startNight(
            context, NightSettings(deadline = deadline, pickedCycles = 5), startedAt,
            AfterAlarmSettings(), BedtimeAudioSettings(), DebugOptions(simulatedBandData = true),
        )
        withTimeout(10_000) { while (loadNightState(context) == null) delay(20) }
    }

    private fun markAsleep(at: Instant) = runBlocking {
        updateSimulatedSleepEvents(context) { appendSimulatedSleepEvent(it, SimulatedSleepEventKind.ASLEEP, at) }
    }

    @Test
    fun `a whole night's fade - start, pull back, park on sleep, fade again, own volume back at End night`() = runBlocking {
        val now = Instant.now()
        startSimulatedNight(now.minus(Duration.ofMinutes(40)))
        setVolume(10)

        play()
        runMediaFadeCheck(context)
        assertEquals("the fade starts at the starting volume", startStep, volume)

        setVolume(9)
        runMediaFadeCheck(context)
        assertEquals("a volume turned up is pulled back", startStep, volume)

        setVolume(2)
        runMediaFadeCheck(context)
        assertEquals("a volume turned down stays", 2, volume)

        markAsleep(now.minus(Duration.ofMinutes(30)))
        runImmediateTick(context)
        assertTrue(nightLog().contains("\"media_pause_on_sleep\""))
        assertEquals("falling asleep pauses and parks where the volume already was", 2, volume)
        assertTrue(nightLog().contains("\"media_fade_parked\""))

        endNight(context, nowInstant())
        assertEquals("End night puts the night's own volume back", 10, volume)
    }

    // Emulator test, 2026-10-03 (owner decision): play pressed while the band still says asleep used to play at
    // the parked volume all night - no pause, no fade.
    @Test
    fun `media started while the band still says asleep gets a fresh fade and is paused once it has run its course`() = runBlocking {
        val now = Instant.now()
        startSimulatedNight(now.minus(Duration.ofMinutes(40)))
        markAsleep(now.minus(Duration.ofMinutes(30)))
        runImmediateTick(context)

        setVolume(2)
        play()
        val fadeStartedAt = nowInstant()
        assertTrue("pressing play while asleep starts a fade", runMediaFadeCheck(context))
        assertEquals("at exactly the starting volume", startStep, volume)

        // NightService's watcher follows a fresh fade with a tick, which books the next one on the fade's first step.
        runImmediateTick(context)
        val nextTickAt = Instant.ofEpochMilli(checkNotNull(shadowOf(context.getSystemService(AlarmManager::class.java)).peekNextScheduledAlarm()).triggerAtTime)
        // The fade stamps its own start a few ms after fadeStartedAt; the ordinary tick would be 15 minutes out.
        val firstStepAt = fadeStartedAt.plus(Duration.ofMinutes(10))
        assertTrue("next tick at $nextTickAt, the fade's first step is at $firstStepAt", Duration.between(firstStepAt, nextTickAt).abs() < Duration.ofSeconds(1))

        // Step 4 down to the floor 1: the 10 minute hold, then steps at 10 and 15, the floor at 20 minutes.
        AppClock.setWarp(ClockWarp(1, Instant.now(), fadeStartedAt.plus(Duration.ofMinutes(21))))
        runImmediateTick(context)
        assertTrue(nightLog().contains("\"media_pause_after_fade\""))
        assertEquals("the fade reached its floor", 1, volume)
        assertTrue("the media app was paused", !audioManager.isMusicActive)

        endNight(context, nowInstant())
        assertEquals("End night puts back the volume the night's first fade started from", 2, volume)
    }

    // Emulator audit, 2026-10-03 (spec-audit.md test gaps): a night that finishes on its own, with no End night,
    // used to keep the faded volume until the next Start night. Reached here by the deadline passing more than an
    // hour ago with nothing rung (the phone alarm is never delivered under Robolectric), which ends the night at
    // the next tick with no out-of-bed nudge to defer it.
    @Test
    fun `a night that finishes on its own puts back the volume its fade lowered`() = runBlocking {
        val now = Instant.now()
        val deadline = now.plus(Duration.ofMinutes(30))
        startSimulatedNight(now.minus(Duration.ofMinutes(10)), deadline)
        setVolume(10)
        play()
        runMediaFadeCheck(context)
        assertEquals("the fade starts at the starting volume", startStep, volume)

        AppClock.setWarp(ClockWarp(1, Instant.now(), deadline.plus(Duration.ofMinutes(61))))
        runImmediateTick(context)

        assertEquals("the night finished on its own", null, loadNightState(context))
        assertEquals("the night's own volume is back", 10, volume)
    }

    private fun changeBedtimeAudio(change: (BedtimeAudioSettings) -> BedtimeAudioSettings) = runBlocking {
        val settings = readAppSettings(context).first()
        writeAppSettings(context, settings.copy(bedtimeAudio = change(settings.bedtimeAudio)))
    }

    // Owner decision, 2026-10-03 (spec-audit.md #4): the fade's settings stay live mid-night, like every other
    // setting that is saved at once - the spec used to say they were read at Start night.
    @Test
    fun `fade settings changed mid-night apply to the next fade and the running fade's next step`() = runBlocking<Unit> {
        val now = Instant.now()
        startSimulatedNight(now.minus(Duration.ofMinutes(40)))
        setVolume(10)
        play()
        runMediaFadeCheck(context)
        assertEquals("the first fade starts at the 25% the night started with", startStep, volume)

        // Starting volume raised to 40% (step 6 of 15) while that fade runs; then asleep, which parks it.
        changeBedtimeAudio { it.copy(fadeStartPercent = 40) }
        markAsleep(now.minus(Duration.ofMinutes(30)))
        runImmediateTick(context)
        assertTrue(nightLog().contains("\"media_fade_parked\""))

        play()
        val fadeStartedAt = nowInstant()
        assertTrue("media played again starts a fresh fade", runMediaFadeCheck(context))
        assertEquals("the next fade starts at the new starting volume", 6, volume)

        // Ending volume raised to 20% (step 3) while this fade runs: its floor moves at its next step. Step 6 to
        // the old floor 1 would take 25 minutes; at 21 minutes it already sits at the new floor 3.
        changeBedtimeAudio { it.copy(fadeEndPercent = 20) }
        AppClock.setWarp(ClockWarp(1, Instant.now(), fadeStartedAt.plus(Duration.ofMinutes(21))))
        runImmediateTick(context)
        assertEquals("the running fade stops at the new ending volume", 3, volume)

        // That fade ran its course asleep, so its media was paused and the fade re-armed. Fade switched off now:
        // playing again starts no new fade.
        assertTrue(nightLog().contains("\"media_pause_after_fade\""))
        changeBedtimeAudio { it.copy(fadeEnabled = false) }
        play()
        assertTrue("with the fade switched off, playing again starts no fade", !runMediaFadeCheck(context))

        changeBedtimeAudio { BedtimeAudioSettings() }
        endNight(context, nowInstant())
    }
}
