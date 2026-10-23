package com.nikita.sleepcycle.scenario

// File purpose: the bedtime fade through a whole simulated night on Robolectric - the real tick, the 1 s fade
// check NightService runs on media, volume and screen changes, the fade's own updates MediaFadeRunner.kt runs
// every 200 ms, the state files and the media volume - no phone. The digital gain is a fake (Robolectric cannot
// create DynamicsProcessing). Emulator audit, 2026-10-03: until then only the fade's pure decisions were tested.
// Owner spec, 2026-10-21/22 (PER-134, PER-135): the smooth line, the screen rule, re-anchoring on the owner's
// own volume, the output's own pair, and the fallback to plain steps.

import android.app.AlarmManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.collect.ImmutableList
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.night.AfterAlarmSettings
import com.nikita.sleepcycle.night.AppClock
import com.nikita.sleepcycle.night.BedtimeAudioSettings
import com.nikita.sleepcycle.night.ClockWarp
import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.FadeGainEffect
import com.nikita.sleepcycle.night.FadeProgress
import com.nikita.sleepcycle.night.FadeVolumes
import com.nikita.sleepcycle.night.MediaFadeGain
import com.nikita.sleepcycle.night.SimulatedSleepEventKind
import com.nikita.sleepcycle.night.VolumeCurve
import com.nikita.sleepcycle.night.advanceMediaFade
import com.nikita.sleepcycle.night.appendSimulatedSleepEvent
import com.nikita.sleepcycle.night.cancelTick
import com.nikita.sleepcycle.night.endNight
import com.nikita.sleepcycle.night.listNightLogs
import com.nikita.sleepcycle.night.loadNightState
import com.nikita.sleepcycle.night.nowInstant
import com.nikita.sleepcycle.night.readAppSettings
import com.nikita.sleepcycle.night.readMediaVolumeCurve
import com.nikita.sleepcycle.night.runImmediateTick
import com.nikita.sleepcycle.night.runMediaFadeCheck
import com.nikita.sleepcycle.night.splitFadeTarget
import com.nikita.sleepcycle.night.startNight
import com.nikita.sleepcycle.night.updateSimulatedSleepEvents
import com.nikita.sleepcycle.night.writeAppSettings
import com.nikita.sleepcycle.ui.components.MEDIA_PLAYBACK_ATTRIBUTES
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import java.time.Duration
import java.time.Instant

/** The digital gain, recorded instead of applied. [hasControl] false acts out another effects app taking over. */
private class FakeGainEffect : FadeGainEffect {
    var gainDb = 0f
    var released = false
    var hasControl = true
    val gains = mutableListOf<Float>()

    override fun setGainDb(gainDb: Float): Boolean {
        if (!hasControl) return false
        this.gainDb = gainDb
        gains += gainDb
        return true
    }

    override fun release() {
        gainDb = 0f
        released = true
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaFadeScenarioTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val audio = shadowOf(audioManager)
    private val power = shadowOf(context.getSystemService(PowerManager::class.java))

    // A 15-step media range, like the emulator: the default starting volume 25% is step 4 (3.75), the default
    // ending volume 5% is the 1-step floor.
    private val startStep = 4

    private var mediaApp: Job? = null
    private var effectsOpened = 0
    private var effect = FakeGainEffect()

    @Before
    fun setUp() {
        audio.setStreamMaxVolume(15)
        // Owner, 2026-10-21: a fade only begins and falls with the screen off.
        power.setIsInteractive(false)
        MediaFadeGain.resetForNewFade()
        MediaFadeGain.openEffect = {
            effectsOpened++
            effect = FakeGainEffect()
            effect
        }
    }

    @After
    fun tearDown() {
        mediaApp?.cancel()
        cancelTick(context)
        AppClock.setWarp(null)
        MediaFadeGain.resetForNewFade()
    }

    private val volume get() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    private fun setVolume(step: Int) = audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)

    private val curve: VolumeCurve get() = checkNotNull(readMediaVolumeCurve(context))

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

    private fun playOn(type: Int) {
        audio.setAudioDevicesForAttributes(MEDIA_PLAYBACK_ATTRIBUTES, ImmutableList.of(AudioDeviceInfoBuilder.newBuilder().setType(type).build()))
    }

    private fun nightLog(): String = listNightLogs(context).first().readText()

    private fun startSimulatedNight(startedAt: Instant, deadline: Instant? = null) = runBlocking {
        startNight(
            context, NightSettings(deadline = deadline, pickedCycles = 5), startedAt,
            AfterAlarmSettings(), DebugOptions(simulatedBandData = true),
        )
        withTimeout(10_000) { while (loadNightState(context) == null) delay(20) }
    }

    private fun markAsleep(at: Instant) = runBlocking {
        updateSimulatedSleepEvents(context) { appendSimulatedSleepEvent(it, SimulatedSleepEventKind.ASLEEP, at) }
    }

    private fun warpTo(virtualNow: Instant) = AppClock.setWarp(ClockWarp(1, Instant.now(), virtualNow))

    /** One update, as MediaFadeRunner.kt runs every 200 ms. */
    private fun update(): FadeProgress = runBlocking {
        val state = checkNotNull(loadNightState(context))
        advanceMediaFade(context, state.realStartedAt, nowInstant(), readAppSettings(context).first().bedtimeAudio, state.debugOptions.isAnyEnabled).progress
    }

    /** Updates until the volume settles, as the runner does over a few hundred ms; checks no update drops more than one step. */
    private fun updateUntilSettled() {
        repeat(20) {
            val before = volume
            update()
            assertTrue("one update dropped $before to $volume", before - volume <= 1)
            if (volume == before) return
        }
    }

    private fun changeBedtimeAudio(change: (BedtimeAudioSettings) -> BedtimeAudioSettings) = runBlocking {
        val settings = readAppSettings(context).first()
        writeAppSettings(context, settings.copy(bedtimeAudio = change(settings.bedtimeAudio)))
    }

    // Owner spec, 2026-10-22 (PER-134): "in case you increase the volume myself, it's going to still be fading away
    // with the same rate" - the old pull back to the schedule is gone.
    @Test
    fun `a whole night's fade - start, the owner's own volume kept, park on sleep, own volume back at End night`() = runBlocking<Unit> {
        val now = Instant.now()
        startSimulatedNight(now.minus(Duration.ofMinutes(40)))
        setVolume(10)

        play()
        runMediaFadeCheck(context)
        assertEquals("the fade starts at the starting volume", startStep, volume)

        setVolume(9)
        runMediaFadeCheck(context)
        assertEquals("a volume turned up is no longer pulled back", 9, volume)
        assertTrue(nightLog().contains("\"media_fade_reanchor\""))

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

    // Owner, 2026-10-21: "I don't want it to start dropping the audio while the screen is still active."
    @Test
    fun `no fade begins while the screen is on, and one begins once it goes off`() = runBlocking<Unit> {
        startSimulatedNight(Instant.now().minus(Duration.ofMinutes(5)))
        setVolume(10)
        power.setIsInteractive(true)
        play()
        assertFalse("screen on: no fade yet", runMediaFadeCheck(context))
        assertEquals(10, volume)

        power.setIsInteractive(false)
        assertTrue("screen off: the fade begins", runMediaFadeCheck(context))
        assertEquals(startStep, volume)
        endNight(context, nowInstant())
    }

    // Owner spec, 2026-10-22 (PER-134): the line falls from the starting volume's dB to the ending volume's dB over
    // the fade duration (15 minutes by default); the hardware sits on the lowest step at or above the line and the
    // gain fills the rest.
    @Test
    fun `halfway through the fade the volume and the gain follow the line`() = runBlocking<Unit> {
        startSimulatedNight(Instant.now().minus(Duration.ofMinutes(5)))
        setVolume(10)
        play()
        val fadeStartedAt = nowInstant()
        runMediaFadeCheck(context)
        assertEquals("no effect opened while the gain is 0", 0, effectsOpened)

        warpTo(fadeStartedAt.plus(Duration.ofSeconds(450)))
        updateUntilSettled()

        val halfwayDb = curve.db(startStep) - (curve.db(startStep) - curve.db(1)) * 0.5
        val expected = splitFadeTarget(halfwayDb, curve, currentStep = startStep, floorStep = 1).let { first ->
            // Settled: repeat the split from wherever the hardware got, as each update does.
            generateSequence(first) { previous -> splitFadeTarget(halfwayDb, curve, previous.step, 1).takeIf { it != previous } }.last()
        }
        assertEquals(expected.step, volume)
        assertEquals(expected.gainDb.toFloat(), effect.gainDb, 0.05f)
        assertTrue("the fade is still falling", update() == FadeProgress.DESCENDING)

        // At the end of the 15 minutes: the ending volume's step, the gain released.
        warpTo(fadeStartedAt.plus(Duration.ofMinutes(15)))
        updateUntilSettled()
        assertEquals(1, volume)
        assertEquals(FadeProgress.AT_END, update())
        assertFalse("the gain is released at the ending volume", MediaFadeGain.isOpen)

        // A fade that has run its course books no tick in the past (which would tick again at once, over and over).
        runImmediateTick(context)
        val nextTickAt = checkNotNull(shadowOf(context.getSystemService(AlarmManager::class.java)).peekNextScheduledAlarm()).triggerAtTime
        assertTrue("next tick at $nextTickAt is ahead", nextTickAt > System.currentTimeMillis())
        endNight(context, nowInstant())
    }

    // Owner, 2026-10-21: if the screen comes back on mid-fade the line freezes, and it continues when the screen
    // goes off again - no reset.
    @Test
    fun `the screen on mid-fade freezes the line, and it carries on from there once off`() = runBlocking<Unit> {
        startSimulatedNight(Instant.now().minus(Duration.ofMinutes(5)))
        setVolume(10)
        play()
        val fadeStartedAt = nowInstant()
        runMediaFadeCheck(context)

        warpTo(fadeStartedAt.plus(Duration.ofMinutes(5)))
        updateUntilSettled()
        val frozenStep = volume
        assertTrue("five minutes in, the fade has stepped down", frozenStep < startStep)

        power.setIsInteractive(true)
        assertEquals(FadeProgress.HOLDING, update())
        warpTo(fadeStartedAt.plus(Duration.ofMinutes(40)))
        assertEquals(FadeProgress.HOLDING, update())
        assertEquals("nothing drops while the screen is on", frozenStep, volume)

        power.setIsInteractive(false)
        update()
        assertEquals("the screen off again: no jump to where the line would have been", frozenStep, volume)
        warpTo(fadeStartedAt.plus(Duration.ofMinutes(60)))
        updateUntilSettled()
        assertEquals("and from there the line carries on to the ending volume", 1, volume)
        endNight(context, nowInstant())
    }

    // Owner spec, 2026-10-22 (PER-135): when Bluetooth connects mid-fade, that output's own pair applies from then
    // on, from the Bluetooth volume itself.
    @Test
    fun `media moving to Bluetooth mid-fade continues from its own volume to its own ending volume`() = runBlocking<Unit> {
        changeBedtimeAudio { it.copy(bluetoothVolumes = FadeVolumes(startPercent = 40, endPercent = 20)) }
        startSimulatedNight(Instant.now().minus(Duration.ofMinutes(5)))
        setVolume(10)
        playOn(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        play()
        val fadeStartedAt = nowInstant()
        runMediaFadeCheck(context)
        assertEquals(startStep, volume)

        // The speaker turned Bluetooth, with its own volume of 9 steps.
        playOn(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        setVolume(9)
        update()
        assertEquals("no snap to any starting volume", 9, volume)
        assertTrue(nightLog().contains("\"cause\":\"output\""))

        // Bluetooth's ending volume 20% of 15 steps is step 3, not the speaker's 1.
        warpTo(fadeStartedAt.plus(Duration.ofMinutes(60)))
        updateUntilSettled()
        assertEquals(3, volume)
        endNight(context, nowInstant())
    }

    // Owner spec, 2026-10-22 (PER-134): without the effect the fade follows the same line in plain steps, logged once.
    @Test
    fun `without the effect the fade goes on in plain steps, logged once`() = runBlocking<Unit> {
        MediaFadeGain.openEffect = { null }
        startSimulatedNight(Instant.now().minus(Duration.ofMinutes(5)))
        setVolume(10)
        play()
        val fadeStartedAt = nowInstant()
        runMediaFadeCheck(context)

        warpTo(fadeStartedAt.plus(Duration.ofSeconds(450)))
        updateUntilSettled()
        warpTo(fadeStartedAt.plus(Duration.ofMinutes(15)))
        updateUntilSettled()
        assertEquals("it still reaches the ending volume", 1, volume)
        assertEquals(1, Regex("\"media_fade_gain_fallback\"").findAll(nightLog()).count())
        endNight(context, nowInstant())
    }

    @Test
    fun `an effect that loses control mid-fade is released and the fade goes on in steps`() = runBlocking<Unit> {
        startSimulatedNight(Instant.now().minus(Duration.ofMinutes(5)))
        setVolume(10)
        play()
        val fadeStartedAt = nowInstant()
        runMediaFadeCheck(context)
        // A moment in, between two steps, so the gain is in use.
        warpTo(fadeStartedAt.plus(Duration.ofSeconds(60)))
        updateUntilSettled()
        assertTrue(MediaFadeGain.isOpen)

        effect.hasControl = false
        warpTo(fadeStartedAt.plus(Duration.ofSeconds(90)))
        update()
        assertTrue("released", effect.released)
        assertTrue(nightLog().contains("\"reason\":\"lost_control\""))
        warpTo(fadeStartedAt.plus(Duration.ofMinutes(15)))
        updateUntilSettled()
        assertEquals(1, volume)
        endNight(context, nowInstant())
    }

    @Test
    fun `End night releases the gain mid-fade and puts the volume back`() = runBlocking<Unit> {
        startSimulatedNight(Instant.now().minus(Duration.ofMinutes(5)))
        setVolume(10)
        play()
        val fadeStartedAt = nowInstant()
        runMediaFadeCheck(context)
        warpTo(fadeStartedAt.plus(Duration.ofSeconds(60)))
        updateUntilSettled()
        assertTrue(MediaFadeGain.isOpen)

        endNight(context, nowInstant())
        assertTrue(effect.released)
        assertFalse(MediaFadeGain.isOpen)
        assertEquals(10, volume)
    }

    @Test
    fun `an alarm starting to ring releases the gain until the next fade`() = runBlocking<Unit> {
        startSimulatedNight(Instant.now().minus(Duration.ofMinutes(5)))
        setVolume(10)
        play()
        val fadeStartedAt = nowInstant()
        runMediaFadeCheck(context)
        warpTo(fadeStartedAt.plus(Duration.ofSeconds(60)))
        updateUntilSettled()
        assertTrue(MediaFadeGain.isOpen)

        MediaFadeGain.releaseForAlarm()
        warpTo(fadeStartedAt.plus(Duration.ofSeconds(90)))
        update()
        assertFalse("no gain comes back while the alarm is the last word", MediaFadeGain.isOpen)
        endNight(context, nowInstant())
    }

    // Emulator test, 2026-10-03 (owner decision): play pressed while the band still says asleep used to play at
    // the parked volume all night - no pause, no fade.
    @Test
    fun `media started while the band still says asleep gets a fresh fade and is paused once it has run its course`() = runBlocking<Unit> {
        val now = Instant.now()
        startSimulatedNight(now.minus(Duration.ofMinutes(40)))
        markAsleep(now.minus(Duration.ofMinutes(30)))
        runImmediateTick(context)

        setVolume(2)
        play()
        val fadeStartedAt = nowInstant()
        assertTrue("pressing play while asleep starts a fade", runMediaFadeCheck(context))
        assertEquals("at exactly the starting volume", startStep, volume)

        // NightService's watcher follows a fresh fade with a tick, which books the next one where the fade will
        // have run its course: the 15 minute fade duration.
        runImmediateTick(context)
        val nextTickAt = Instant.ofEpochMilli(checkNotNull(shadowOf(context.getSystemService(AlarmManager::class.java)).peekNextScheduledAlarm()).triggerAtTime)
        val fadeEndsAt = fadeStartedAt.plus(Duration.ofMinutes(15))
        assertTrue("next tick at $nextTickAt, the fade ends at $fadeEndsAt", Duration.between(fadeEndsAt, nextTickAt).abs() < Duration.ofSeconds(1))

        warpTo(fadeStartedAt.plus(Duration.ofMinutes(16)))
        updateUntilSettled()
        runImmediateTick(context)
        assertTrue(nightLog().contains("\"media_pause_after_fade\""))
        assertEquals("the fade reached its floor", 1, volume)
        assertTrue("the media app was paused", !audioManager.isMusicActive)

        endNight(context, nowInstant())
        assertEquals("End night puts back the volume the night's first fade started from", 2, volume)
    }

    @Test
    fun `media pressed while asleep is not paused before the fade duration, whatever the volume`() = runBlocking<Unit> {
        val now = Instant.now()
        startSimulatedNight(now.minus(Duration.ofMinutes(40)))
        markAsleep(now.minus(Duration.ofMinutes(30)))
        runImmediateTick(context)
        play()
        val fadeStartedAt = nowInstant()
        runMediaFadeCheck(context)
        // Lowered by hand to the floor at once: the line is at its end, but the 15 minutes have not passed.
        setVolume(1)
        warpTo(fadeStartedAt.plus(Duration.ofMinutes(3)))
        runImmediateTick(context)
        assertFalse(nightLog().contains("\"media_pause_after_fade\""))
        assertTrue(audioManager.isMusicActive)
        endNight(context, nowInstant())
    }

    // Emulator audit, 2026-10-03 (spec-audit.md test gaps): a night that finishes on its own, with no End night,
    // used to keep the faded volume until the next Start night. Reached here by the deadline passing more than an
    // hour ago with nothing rung (the phone alarm is never delivered under Robolectric), which ends the night at
    // the next tick with no out-of-bed nudge to defer it.
    @Test
    fun `a night that finishes on its own puts back the volume its fade lowered`() = runBlocking<Unit> {
        val now = Instant.now()
        val deadline = now.plus(Duration.ofMinutes(30))
        startSimulatedNight(now.minus(Duration.ofMinutes(10)), deadline)
        setVolume(10)
        play()
        runMediaFadeCheck(context)
        assertEquals("the fade starts at the starting volume", startStep, volume)

        warpTo(deadline.plus(Duration.ofMinutes(61)))
        runImmediateTick(context)

        assertEquals("the night finished on its own", null, loadNightState(context))
        assertEquals("the night's own volume is back", 10, volume)
    }

    // Owner decision, 2026-10-03 (spec-audit.md #4): the fade's settings stay live mid-night, like every other
    // setting that is saved at once.
    @Test
    fun `fade settings changed mid-night apply to the next fade and the running fade`() = runBlocking<Unit> {
        val now = Instant.now()
        startSimulatedNight(now.minus(Duration.ofMinutes(40)))
        setVolume(10)
        play()
        runMediaFadeCheck(context)
        assertEquals("the first fade starts at the 25% the night started with", startStep, volume)

        // Starting volume raised to 40% (step 6 of 15) while that fade runs; then asleep, which parks it.
        changeBedtimeAudio { it.copy(phoneSpeakerVolumes = it.phoneSpeakerVolumes.copy(startPercent = 40)) }
        markAsleep(now.minus(Duration.ofMinutes(30)))
        runImmediateTick(context)
        assertTrue(nightLog().contains("\"media_fade_parked\""))

        play()
        val fadeStartedAt = nowInstant()
        assertTrue("media played again starts a fresh fade", runMediaFadeCheck(context))
        assertEquals("the next fade starts at the new starting volume", 6, volume)

        // Ending volume raised to 20% (step 3) while this fade runs: it now stops there.
        changeBedtimeAudio { it.copy(phoneSpeakerVolumes = it.phoneSpeakerVolumes.copy(endPercent = 20)) }
        warpTo(fadeStartedAt.plus(Duration.ofMinutes(21)))
        updateUntilSettled()
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

    // PER-148: Settings opens from the Night screen, so fade on or off is honored at the next fade start rather
    // than decided once at Start night.
    @Test
    fun `a fade switched off at Start night and on mid-night fades the next playback`() = runBlocking<Unit> {
        changeBedtimeAudio { it.copy(fadeEnabled = false) }
        startSimulatedNight(Instant.now().minus(Duration.ofMinutes(5)))
        setVolume(10)
        play()
        assertFalse("fade off: playing starts no fade", runMediaFadeCheck(context))
        assertEquals(10, volume)

        changeBedtimeAudio { it.copy(fadeEnabled = true) }
        assertTrue("switched on mid-night: the next check starts a fade", runMediaFadeCheck(context))
        assertEquals(startStep, volume)

        endNight(context, nowInstant())
        assertEquals("End night puts the night's own volume back", 10, volume)
    }
}
