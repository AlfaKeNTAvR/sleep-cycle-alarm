package com.nikita.sleepcycle.scenario

// File purpose: ISSUES.md #1 (validation.md, confirmed) - "I'm up" pressed before the morning alarm cancels it,
// and nothing may put it back: not a tick whose band sync failed (the stale-sync freeze kept the old plan and
// re-armed it), and not a reboot (BootReceiver re-armed the saved plan's wakeAt).

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.night.AppClock
import com.nikita.sleepcycle.night.cancelTick
import com.nikita.sleepcycle.night.endNight
import com.nikita.sleepcycle.night.loadNightState
import com.nikita.sleepcycle.night.nowInstant
import com.nikita.sleepcycle.night.pressImUp
import com.nikita.sleepcycle.night.runImmediateTick
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImUpScenarioTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = runBlocking {
        endNight(context, nowInstant())
        cancelTick(context)
        AppClock.setWarp(null)
    }

    @Test
    fun `I'm up stays up through a reboot and a tick whose band sync failed`() = runBlocking {
        // No band configured: every sync of this night fails, as on the 2026-09-30 night the band never synced.
        startNightAndWait(context, NightSettings(deadline = null, pickedCycles = 5), Instant.now())
        assertNotNull("Start night arms the morning alarm", armedPhoneAlarmAt(context))

        assertTrue(pressImUp(context, nowInstant()))
        assertNull("I'm up cancels the morning alarm", armedPhoneAlarmAt(context))

        rebootPhone(context)
        assertNull("a reboot must not re-arm the alarm I'm up cancelled", armedPhoneAlarmAt(context))

        runImmediateTick(context)
        assertEquals(false, loadNightState(context)?.lastSyncOk)
        assertNull("a tick with a failed sync must not re-arm it either", armedPhoneAlarmAt(context))
    }
}
