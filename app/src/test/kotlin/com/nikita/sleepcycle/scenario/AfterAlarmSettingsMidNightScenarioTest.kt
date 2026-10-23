package com.nikita.sleepcycle.scenario

// File purpose: PER-148 - Settings opens from the Night screen, so the nap length and the out-of-bed nudge delay
// changed mid-night apply from their next use: the next nap or nudge armed uses the new value, and one already
// set keeps ringing when it was set to.

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nikita.sleepcycle.alarm.EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.night.AfterAlarmSettings
import com.nikita.sleepcycle.night.AppClock
import com.nikita.sleepcycle.night.FollowUpKind
import com.nikita.sleepcycle.night.PendingFollowUp
import com.nikita.sleepcycle.night.applyAfterAlarmSettingsToNight
import com.nikita.sleepcycle.night.cancelTick
import com.nikita.sleepcycle.night.endNight
import com.nikita.sleepcycle.night.loadNightState
import com.nikita.sleepcycle.night.nowInstant
import com.nikita.sleepcycle.night.pressImUp
import com.nikita.sleepcycle.night.readPendingFollowUp
import com.nikita.sleepcycle.night.startManualNap
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AfterAlarmSettingsMidNightScenarioTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = runBlocking {
        endNight(context, nowInstant())
        cancelTick(context)
        AppClock.setWarp(null)
    }

    /** The (virtual) instant the out-of-bed slot is armed for: the nudge or the owner's nap. */
    private fun armedFollowUpAt(): Instant? =
        armedAlarmIntent(context, outOfBed = true)?.let { Instant.ofEpochMilli(it.getLongExtra(EXTRA_ALARM_SCHEDULED_FOR_EPOCH_MILLI, -1L)) }

    @Test
    fun `nap length and nudge delay changed mid-night apply to the next nap and nudge, never to the one already set`() = runBlocking {
        // Started with the defaults: a 10 min nudge and a 20 min nap.
        startNightAndWait(context, NightSettings(deadline = null, pickedCycles = 5), Instant.now())
        val upAt = nowInstant()
        assertTrue(pressImUp(context, upAt))
        val nudgeAt = upAt.plus(Duration.ofMinutes(10))
        assertEquals(PendingFollowUp(FollowUpKind.NUDGE, nudgeAt), readPendingFollowUp(context))

        val changed = AfterAlarmSettings(nudgeMinutes = 5, napMinutes = 30)
        applyAfterAlarmSettingsToNight(context, changed, nowInstant())
        assertEquals("the night now uses the new values", changed, loadNightState(context)?.afterAlarm)
        assertEquals("the nudge already set does not move", PendingFollowUp(FollowUpKind.NUDGE, nudgeAt), readPendingFollowUp(context))
        assertEquals(nudgeAt, armedFollowUpAt())

        val napPressedAt = nowInstant()
        assertTrue(startManualNap(context, napPressedAt))
        val napAt = napPressedAt.plus(Duration.ofMinutes(30))
        assertEquals("the next nap uses the new length", PendingFollowUp(FollowUpKind.NAP, napAt), readPendingFollowUp(context))
        assertEquals(napAt, armedFollowUpAt())

        val upAgainAt = nowInstant()
        assertTrue(pressImUp(context, upAgainAt))
        assertEquals(
            "the next nudge uses the new delay",
            PendingFollowUp(FollowUpKind.NUDGE, upAgainAt.plus(Duration.ofMinutes(5))),
            readPendingFollowUp(context),
        )
    }
}
