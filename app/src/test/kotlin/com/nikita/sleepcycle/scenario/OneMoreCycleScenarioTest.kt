package com.nikita.sleepcycle.scenario

// File purpose: PER-149 - One more cycle end to end: after the morning alarm (here stood in for by I'm up), the
// button arms the out-of-bed slot 90 min from the tap under its own name, the arming survives a reboot, its log
// line is told apart from a nap's, and when it rings the next nudge is armed so it can be chosen again.

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nikita.sleepcycle.alarm.AlarmLabel
import com.nikita.sleepcycle.alarm.EXTRA_ALARM_LABEL
import com.nikita.sleepcycle.alarm.EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.night.AppClock
import com.nikita.sleepcycle.night.ClockWarp
import com.nikita.sleepcycle.night.FollowUpKind
import com.nikita.sleepcycle.night.PendingFollowUp
import com.nikita.sleepcycle.night.cancelTick
import com.nikita.sleepcycle.night.endNight
import com.nikita.sleepcycle.night.nowInstant
import com.nikita.sleepcycle.night.pressImUp
import com.nikita.sleepcycle.night.readPendingFollowUp
import com.nikita.sleepcycle.night.startOneMoreCycle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OneMoreCycleScenarioTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = runBlocking {
        endNight(context, nowInstant())
        cancelTick(context)
        AppClock.setWarp(null)
    }

    private val cycle: Duration = Duration.ofMinutes(90)

    private fun armedOutOfBedSlot(): Pair<Instant, String?> {
        val intent = checkNotNull(armedAlarmIntent(context, outOfBed = true)) { "nothing armed in the out-of-bed slot" }
        return Instant.ofEpochMilli(intent.getLongExtra(EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI, -1L)) to intent.getStringExtra(EXTRA_ALARM_LABEL)
    }

    @Test
    fun `One more cycle rings 90 min after the tap under its own name, survives a reboot, and can be chosen again`() = runBlocking {
        startNightAndWait(context, NightSettings(deadline = null, pickedCycles = 5), Instant.now())
        assertTrue(pressImUp(context, nowInstant()))

        val pressedAt = nowInstant()
        assertTrue(startOneMoreCycle(context, pressedAt))
        val cycleAt = pressedAt.plus(cycle)
        assertEquals(cycleAt to AlarmLabel.ONE_MORE_CYCLE.name, armedOutOfBedSlot())
        assertEquals("persisted like a pending nap", PendingFollowUp(FollowUpKind.ONE_MORE_CYCLE, cycleAt), readPendingFollowUp(context))

        rebootPhone(context)
        assertEquals("a reboot restores it under the same name", cycleAt to AlarmLabel.ONE_MORE_CYCLE.name, armedOutOfBedSlot())

        val log = nightLogText(context)
        assertTrue(log, log.contains("\"one_more_cycle_armed\""))
        assertFalse("never logged as a nap: $log", log.contains("\"manual_nap_armed\""))

        AppClock.setWarp(ClockWarp(1, Instant.now(), cycleAt))
        firePhoneAlarm(context, checkNotNull(armedAlarmIntent(context, outOfBed = true)))
        assertEquals("its ring arms the next nudge", AlarmLabel.OUT_OF_BED.name, armedOutOfBedSlot().second)
        assertTrue(nightLogText(context).contains("one_more_cycle_fired"))

        val pressedAgainAt = nowInstant()
        assertTrue("it can be chosen again after its own ring", startOneMoreCycle(context, pressedAgainAt))
        assertEquals(pressedAgainAt.plus(cycle) to AlarmLabel.ONE_MORE_CYCLE.name, armedOutOfBedSlot())
    }

    @Test
    fun `One more cycle that would ring after the deadline is refused and the nudge stays armed`() = runBlocking {
        val deadline = Instant.now().plus(Duration.ofMinutes(60))
        startNightAndWait(context, NightSettings(deadline = deadline, pickedCycles = 5), Instant.now())
        assertTrue(pressImUp(context, nowInstant()))
        val nudge = armedOutOfBedSlot()

        assertFalse(startOneMoreCycle(context, nowInstant()))
        assertEquals(nudge, armedOutOfBedSlot())
        assertTrue(nightLogText(context).contains("\"one_more_cycle_refused\""))
    }
}
