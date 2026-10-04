package com.nikita.sleepcycle.scenario

// File purpose: owner request, 2026-10-03 - a simulated night's log is named and listed by the REAL time Start
// night was tapped, not by the simulated clock it ran on, from Start night through End night. A real night's
// clocks are the same, so its log name does not change.

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.night.AfterAlarmSettings
import com.nikita.sleepcycle.night.AppClock
import com.nikita.sleepcycle.night.BedtimeAudioSettings
import com.nikita.sleepcycle.night.ClockWarp
import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.cancelTick
import com.nikita.sleepcycle.night.endNight
import com.nikita.sleepcycle.night.listNightLogs
import com.nikita.sleepcycle.night.loadNightState
import com.nikita.sleepcycle.night.nowInstant
import com.nikita.sleepcycle.night.startNight
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SimulatedNightLogNameScenarioTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        cancelTick(context)
        AppClock.setWarp(null)
    }

    private fun logNameStamp(at: Instant): String =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.systemDefault()).format(at)

    private fun runNight(startedAt: Instant, debugOptions: DebugOptions) = runBlocking {
        startNight(context, NightSettings(deadline = null, pickedCycles = 5), startedAt, AfterAlarmSettings(), BedtimeAudioSettings(), debugOptions)
        withTimeout(10_000) { while (loadNightState(context) == null) delay(20) }
        endNight(context, nowInstant())
    }

    @Test
    fun `a simulated night with the clock hours ahead is logged under the real time it was started`() {
        val realStart = Instant.now()
        val warp = ClockWarp(1, realStart, realStart.plus(Duration.ofHours(9)))
        AppClock.setWarp(warp)

        runNight(warp.anchorVirtual, DebugOptions(simulatedBandData = true, warp = warp))

        assertEquals(listOf("night-sim-${logNameStamp(realStart)}.jsonl"), listNightLogs(context).map { it.name })
    }

    @Test
    fun `a real night is logged under its start, as before`() {
        val start = Instant.now()

        runNight(start, DebugOptions())

        assertEquals(listOf("night-${logNameStamp(start)}.jsonl"), listNightLogs(context).map { it.name })
    }
}
