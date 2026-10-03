package com.nikita.sleepcycle.scenario

// File purpose: emulator test, 2026-10-03 - a "1x" tap was lost while Auto ran at 3600x. The tick re-applying
// Auto read the speed between the tap's two writes and put 3600x back. Runs the tap against a burst of ticks.

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nikita.sleepcycle.night.AppClock
import com.nikita.sleepcycle.night.ClockWarp
import com.nikita.sleepcycle.night.SpeedChoice
import com.nikita.sleepcycle.night.applyAutoSpeed
import com.nikita.sleepcycle.night.changeSimulationSpeed
import com.nikita.sleepcycle.night.chooseSimulationSpeed
import com.nikita.sleepcycle.night.nowInstant
import com.nikita.sleepcycle.night.readSimulationSpeed
import com.nikita.sleepcycle.night.updateDebugOptions
import com.nikita.sleepcycle.night.writeClockWarp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SimulationSpeedRaceScenarioTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = AppClock.setWarp(null)

    @Test
    fun `a 1x tap while Auto ticks run at 3600x always lands`() = runBlocking {
        repeat(20) {
            writeClockWarp(context, ClockWarp(3600, Instant.now(), Instant.now()))
            updateDebugOptions(context) { it.copy(simulatedBandData = true, autoSpeed = true) }

            val ticks = async(Dispatchers.Default) {
                repeat(20) { applyAutoSpeed(context, nowInstant(), plannedAlarmAt = null, followUpAt = null, waitingForSleep = false) }
            }
            val tap = async(Dispatchers.Default) {
                changeSimulationSpeed(context) { chooseSimulationSpeed(it, SpeedChoice.REAL, Instant.now(), autoSpeedNow = 3600) }
            }
            tap.await()
            ticks.await()

            val speed = readSimulationSpeed(context)
            assertEquals("Auto must be off after the tap", false, speed.auto)
            assertEquals("the clock must be at 1x after the tap", 1, speed.warp?.speed ?: 1)
        }
    }
}
