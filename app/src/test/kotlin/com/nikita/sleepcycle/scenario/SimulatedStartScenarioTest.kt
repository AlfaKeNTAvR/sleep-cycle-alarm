package com.nikita.sleepcycle.scenario

// File purpose: owner request, 2026-10-03 - switching simulated band data on starts the simulated clock at the
// Debug section's "Simulated start" time, and that time is a preference: End night and the 2 h idle reset
// (both resetDebugOptionsAndClock) turn the switches off but leave it as the owner set it.

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nikita.sleepcycle.night.AppClock
import com.nikita.sleepcycle.night.ClockWarp
import com.nikita.sleepcycle.night.enableSimulatedBandData
import com.nikita.sleepcycle.night.nextOccurrenceOf
import com.nikita.sleepcycle.night.readDebugOptions
import com.nikita.sleepcycle.night.readSimulatedStartTime
import com.nikita.sleepcycle.night.resetDebugOptionsAndClock
import com.nikita.sleepcycle.night.writeSimulatedStartTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SimulatedStartScenarioTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val berlin = ZoneId.of("Europe/Berlin")

    @After
    fun tearDown() = runBlocking { resetDebugOptionsAndClock(context, Instant.now()) }

    @Test
    fun `switching simulated band data on starts the clock at the stored time, at 1x`() = runBlocking {
        writeSimulatedStartTime(context, LocalTime.of(22, 15))
        // 15:00 in Berlin: today's 22:15 is still ahead.
        val realNow = Instant.parse("2026-10-03T13:00:00Z")

        enableSimulatedBandData(context, realNow, berlin)

        assertEquals(ClockWarp(1, realNow, Instant.parse("2026-10-03T20:15:00Z")), AppClock.warp())
        val stored = readDebugOptions(context).first()
        assertEquals(true, stored.simulatedBandData)
        assertEquals("the persisted warp is the one AppClock runs on", AppClock.warp(), stored.warp)
    }

    @Test
    fun `the stored time survives the debug reset, and the next switch-on uses it again`() = runBlocking {
        writeSimulatedStartTime(context, LocalTime.of(1, 30))
        enableSimulatedBandData(context, Instant.now(), berlin)

        resetDebugOptionsAndClock(context, Instant.now())

        assertNull("the reset puts the clock back to real time", AppClock.warp())
        assertEquals(false, readDebugOptions(context).first().simulatedBandData)
        assertEquals(LocalTime.of(1, 30), readSimulatedStartTime(context).first())

        val realNow = Instant.now()
        enableSimulatedBandData(context, realNow, berlin)
        assertEquals(nextOccurrenceOf(LocalTime.of(1, 30), berlin, realNow), AppClock.warp()?.anchorVirtual)
    }
}
