package com.nikita.sleepcycle.scenario

// File purpose: PER-150 - a No-alarm night end to end. Start night arms no morning alarm, ticks and a reboot
// never arm one, the log says the night is a No-alarm one and its plans claim no alarm time, and I'm up starts
// the out-of-bed nudge as on any night.

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.night.AppClock
import com.nikita.sleepcycle.night.cancelTick
import com.nikita.sleepcycle.night.endNight
import com.nikita.sleepcycle.night.loadNightState
import com.nikita.sleepcycle.night.nowInstant
import com.nikita.sleepcycle.night.parseNightLogLine
import com.nikita.sleepcycle.night.pressImUp
import com.nikita.sleepcycle.night.readPendingFollowUp
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
class NoAlarmNightScenarioTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val noAlarm = NightSettings(deadline = null, pickedCycles = 5, noAlarm = true)

    @After
    fun tearDown() = runBlocking {
        endNight(context, nowInstant())
        cancelTick(context)
        AppClock.setWarp(null)
    }

    @Test
    fun `a No-alarm night arms no morning alarm, through ticks and a reboot`() = runBlocking {
        startNightAndWait(context, noAlarm, Instant.now())
        assertNull("Start night arms no morning alarm", armedPhoneAlarmAt(context))
        assertEquals(AlarmMode.NO_ALARM, loadNightState(context)?.lastPlan?.mode)

        runImmediateTick(context)
        assertNull("a tick arms none either", armedPhoneAlarmAt(context))

        rebootPhone(context)
        assertNull("nor does a reboot", armedPhoneAlarmAt(context))
        assertEquals("the night is still a No-alarm one after the restart", true, loadNightState(context)?.settings?.noAlarm)
    }

    @Test
    fun `the night log marks the night as No-alarm and its plans claim no alarm time`() = runBlocking {
        startNightAndWait(context, noAlarm, Instant.now())
        runImmediateTick(context)

        val events = nightLogText(context).lines().mapNotNull(::parseNightLogLine)
        assertEquals("true", events.first { it.type == "night_start" }.fields["noAlarm"])
        val plans = events.filter { it.type == "plan" }
        assertTrue("the tick logged a plan", plans.isNotEmpty())
        plans.forEach { plan ->
            assertEquals(AlarmMode.NO_ALARM.name, plan.fields["mode"])
            assertEquals("", plan.fields["wakeAt"])
        }
    }

    @Test
    fun `I'm up starts the out-of-bed nudge and still arms no morning alarm`() = runBlocking {
        startNightAndWait(context, noAlarm, Instant.now())

        assertTrue(pressImUp(context, nowInstant()))

        assertNotNull("the out-of-bed nudge is armed", armedAlarmIntent(context, outOfBed = true))
        assertNotNull(readPendingFollowUp(context))
        assertNull(armedPhoneAlarmAt(context))
        runImmediateTick(context)
        assertEquals("the night never ends on its own", AlarmMode.NO_ALARM, loadNightState(context)?.lastPlan?.mode)
    }
}
