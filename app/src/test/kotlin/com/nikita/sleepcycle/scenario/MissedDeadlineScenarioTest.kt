package com.nikita.sleepcycle.scenario

// File purpose: ISSUES.md #2 (round2 #1, reproduced in deadline.md #1) - a deadline that passed with no alarm
// ever rung (the phone off, or its alarms wiped by a force-stop) used to end the night in silence at the first
// tick after it. Owner decision, 2026-10-03: the alarm still rings, two minutes out (minAlarmLead); the night
// ends silently only once the morning alarm has rung or the deadline is more than an hour behind. Also pins a
// deadline closer than minAlarmLead at Start night: armed at the deadline itself.

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nikita.sleepcycle.alarm.cancelPhoneAlarm
import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.night.AppClock
import com.nikita.sleepcycle.night.ClockWarp
import com.nikita.sleepcycle.night.cancelTick
import com.nikita.sleepcycle.night.endNight
import com.nikita.sleepcycle.night.loadNightState
import com.nikita.sleepcycle.night.nowInstant
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
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MissedDeadlineScenarioTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = runBlocking {
        endNight(context, nowInstant())
        cancelTick(context)
        AppClock.setWarp(null)
    }

    @Test
    fun `a deadline that passed with nothing rung rings two minutes later, and the night ends only after that ring`() = runBlocking {
        val now = Instant.now()
        val deadline = now.minus(Duration.ofMinutes(5))
        startNightAndWait(context, NightSettings(deadline = deadline, pickedCycles = 5), now.minus(Duration.ofMinutes(60)))
        // Force-stop: Android drops every alarm the app had, and no BOOT_COMPLETED follows.
        cancelPhoneAlarm(context)

        val tickAt = Instant.now()
        runImmediateTick(context)

        val state = loadNightState(context)
        assertNotNull("the night is not over: its deadline alarm never rang", state)
        assertEquals(AlarmMode.DEADLINE_ONLY, state?.lastPlan?.mode)
        val armedAt = checkNotNull(armedPhoneAlarmAt(context)) { "the missed deadline alarm must be armed again" }
        assertEquals("on a whole minute", armedAt.truncatedTo(ChronoUnit.MINUTES), armedAt)
        assertTrue("armed at $armedAt, at least minAlarmLead after $tickAt", !armedAt.isBefore(tickAt.plus(Duration.ofMinutes(2))))
        assertTrue("armed at $armedAt, within a minute of $tickAt + minAlarmLead", armedAt.isBefore(tickAt.plus(Duration.ofMinutes(3)).plusSeconds(5)))

        // It rings, and the next tick reaches FINISHED, held open only by the out-of-bed nudge that ring armed.
        AppClock.setWarp(ClockWarp(1, Instant.now(), armedAt))
        firePhoneAlarm(context)
        AppClock.setWarp(ClockWarp(1, Instant.now(), armedAt.plusSeconds(30)))
        runImmediateTick(context)
        assertEquals(AlarmMode.FINISHED, loadNightState(context)?.lastPlan?.mode)
        assertNull("nothing is re-armed after the recovered ring", armedPhoneAlarmAt(context))
        assertTrue(nightLogText(context).contains("\"night_end_deferred\""))
    }

    @Test
    fun `a deadline more than an hour behind with nothing rung ends the night silently`() = runBlocking {
        val now = Instant.now()
        startNightAndWait(context, NightSettings(deadline = now.minus(Duration.ofMinutes(61)), pickedCycles = 5), now.minus(Duration.ofHours(3)))
        cancelPhoneAlarm(context)

        runImmediateTick(context)

        assertNull("the night ended", loadNightState(context))
        assertNull(armedPhoneAlarmAt(context))
    }

    @Test
    fun `a deadline closer than minAlarmLead at Start night is armed at the deadline itself`() = runBlocking {
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val deadline = now.plusSeconds(60)
        startNightAndWait(context, NightSettings(deadline = deadline, pickedCycles = 5), now)

        assertEquals(AlarmMode.DEADLINE_ONLY, loadNightState(context)?.lastPlan?.mode)
        assertEquals(deadline, armedPhoneAlarmAt(context))
    }
}
