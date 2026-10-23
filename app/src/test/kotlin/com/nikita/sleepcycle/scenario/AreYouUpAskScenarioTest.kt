package com.nikita.sleepcycle.scenario

// File purpose: PER-151 - the Are you up? ask on a No-alarm night, end to end on Robolectric: a simulated band
// shows 6 h+ asleep and then a 15 min awakening, a tick posts the silent notification, and its own buttons and
// delete intent are driven the way Android delivers them. Checks what the night state, the log and the
// notification shade hold afterwards.

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.night.AppClock
import com.nikita.sleepcycle.night.AreYouUpAnswer
import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.SimulatedSleepEvent
import com.nikita.sleepcycle.night.SimulatedSleepEventKind
import com.nikita.sleepcycle.night.cancelTick
import com.nikita.sleepcycle.night.endNight
import com.nikita.sleepcycle.night.loadNightState
import com.nikita.sleepcycle.night.nowInstant
import com.nikita.sleepcycle.night.parseNightLogLine
import com.nikita.sleepcycle.night.pressImUp
import com.nikita.sleepcycle.night.readPendingFollowUp
import com.nikita.sleepcycle.night.runImmediateTick
import com.nikita.sleepcycle.night.writeSimulatedSleepEvents
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class AreYouUpAskScenarioTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val application = context as Application
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val noAlarm = NightSettings(deadline = null, pickedCycles = 5, noAlarm = true)
    private val base = Instant.now()

    /** 7 h 20 min asleep, then awake for the last 40 min. */
    private val longNightThenAwake = listOf(
        SimulatedSleepEvent(SimulatedSleepEventKind.ASLEEP, base.minus(Duration.ofHours(8))),
        SimulatedSleepEvent(SimulatedSleepEventKind.AWAKE, base.minus(Duration.ofMinutes(40))),
    )

    @Before
    fun setUp() = runBlocking {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        startNightAndWait(context, noAlarm, base.minus(Duration.ofHours(8)).minusSeconds(60), DebugOptions(simulatedBandData = true))
    }

    @After
    fun tearDown() = runBlocking {
        endNight(context, nowInstant())
        cancelTick(context)
        writeSimulatedSleepEvents(context, emptyList())
        AppClock.setWarp(null)
    }

    @Test
    fun `a 15 min awakening after 6 h asleep posts a silent ask with I'm up and Still sleeping, and logs it`() = runBlocking {
        writeSimulatedSleepEvents(context, longNightThenAwake)
        runImmediateTick(context)

        val ask = checkNotNull(areYouUpNotification()) { "the ask was not posted" }
        assertEquals(listOf("I'm up", "Still sleeping"), ask.actions.map { it.title.toString() })
        assertNotNull("dismissing it comes back to the app", ask.deleteIntent)
        assertEquals(Notification.VISIBILITY_PUBLIC, ask.visibility)
        val channel = manager.getNotificationChannel(ask.channelId)
        assertNull("no sound", channel.sound)
        assertFalse("no vibration", channel.shouldVibrate())

        assertTrue("the ask is open in the night state", loadNightState(context)?.areYouUp?.isOpen == true)
        assertEquals(1, logEvents("are_you_up_asked").size)
        assertEquals("the night does not end because of the ask", AlarmMode.NO_ALARM, loadNightState(context)?.lastPlan?.mode)
    }

    @Test
    fun `no ask before the band has seen 6 h asleep`() = runBlocking {
        writeSimulatedSleepEvents(
            context,
            listOf(
                SimulatedSleepEvent(SimulatedSleepEventKind.ASLEEP, base.minus(Duration.ofHours(5))),
                SimulatedSleepEvent(SimulatedSleepEventKind.AWAKE, base.minus(Duration.ofMinutes(40))),
            ),
        )
        runImmediateTick(context)

        assertNull(areYouUpNotification())
        assertNull(loadNightState(context)?.areYouUp)
    }

    @Test
    fun `a dismissed ask is posted again straight away and the re-post is logged`() = runBlocking {
        writeSimulatedSleepEvents(context, longNightThenAwake)
        runImmediateTick(context)
        val dismissed = checkNotNull(areYouUpNotification()).deleteIntent

        manager.cancelAll()
        context.sendBroadcast(shadowOf(dismissed).savedIntent)
        shadowOf(Looper.getMainLooper()).idle()

        withTimeout(10_000) { while (areYouUpNotification() == null) delay(20) }
        assertEquals("dismissed", logEvents("are_you_up_reposted").single().fields["cause"])
        assertTrue(loadNightState(context)?.areYouUp?.isOpen == true)
    }

    @Test
    fun `I'm up on the notification does what I'm up does and the ask goes away`() = runBlocking {
        writeSimulatedSleepEvents(context, longNightThenAwake)
        runImmediateTick(context)

        tapAction("I'm up")
        withTimeout(10_000) { while (loadNightState(context)?.wakeAlarmFiredAt == null) delay(20) }
        withTimeout(10_000) { while (logEvents("are_you_up_answered").isEmpty()) delay(20) }

        assertNotNull("the out-of-bed nudge is armed", readPendingFollowUp(context))
        assertNotNull(armedAlarmIntent(context, outOfBed = true))
        assertNull("the notification is gone", areYouUpNotification())
        assertEquals(AreYouUpAnswer.IM_UP, loadNightState(context)?.areYouUp?.answer)
        val answered = logEvents("are_you_up_answered").single()
        assertEquals("IM_UP", answered.fields["answer"])
        assertEquals("notification", answered.fields["via"])
    }

    @Test
    fun `the Night screen's own I'm up also answers an open ask`() = runBlocking {
        writeSimulatedSleepEvents(context, longNightThenAwake)
        runImmediateTick(context)

        assertTrue(pressImUp(context, nowInstant()))

        assertNull(areYouUpNotification())
        assertEquals(AreYouUpAnswer.IM_UP, loadNightState(context)?.areYouUp?.answer)
        runImmediateTick(context)
        assertNull("never asked again once up", areYouUpNotification())
    }

    @Test
    fun `Still sleeping clears the ask, and only a later separate awakening asks again`() = runBlocking {
        writeSimulatedSleepEvents(context, longNightThenAwake)
        runImmediateTick(context)

        tapAction("Still sleeping")
        withTimeout(10_000) { while (loadNightState(context)?.areYouUp?.answer == null) delay(20) }
        assertEquals(AreYouUpAnswer.STILL_SLEEPING, loadNightState(context)?.areYouUp?.answer)
        assertNull("the notification is gone", areYouUpNotification())
        assertNull("Still sleeping arms nothing", readPendingFollowUp(context))

        runImmediateTick(context)
        assertNull("the same awakening is not asked again", areYouUpNotification())

        // He fell asleep again 5 min after answering and woke for good 20 min ago.
        writeSimulatedSleepEvents(
            context,
            longNightThenAwake + listOf(
                SimulatedSleepEvent(SimulatedSleepEventKind.ASLEEP, base.minus(Duration.ofMinutes(35))),
                SimulatedSleepEvent(SimulatedSleepEventKind.AWAKE, base.minus(Duration.ofMinutes(20))),
            ),
        )
        runImmediateTick(context)

        assertNotNull("the later awakening is asked about", areYouUpNotification())
        assertEquals(base.minus(Duration.ofMinutes(20)), loadNightState(context)?.areYouUp?.awakeningStartedAt)
        assertEquals(2, logEvents("are_you_up_asked").size)
    }

    @Test
    fun `an ask open when the app was killed is still open after restart, and a lost notification comes back`() = runBlocking {
        writeSimulatedSleepEvents(context, longNightThenAwake)
        runImmediateTick(context)

        // A reboot clears the shade; the state file keeps the ask.
        manager.cancelAll()
        assertTrue(loadNightState(context)?.areYouUp?.isOpen == true)
        runImmediateTick(context)

        assertNotNull(areYouUpNotification())
        assertEquals("missing", logEvents("are_you_up_reposted").single().fields["cause"])
        assertEquals("not asked a second time", 1, logEvents("are_you_up_asked").size)
    }

    private fun areYouUpNotification(): Notification? =
        shadowOf(manager).allNotifications.firstOrNull { it.actions?.any { action -> action.title == "Still sleeping" } == true }

    /** Delivers one of the ask's action buttons the way Android does: its own PendingIntent's broadcast. */
    private fun tapAction(title: String) {
        val action = checkNotNull(areYouUpNotification()).actions.first { it.title == title }
        context.sendBroadcast(shadowOf(action.actionIntent).savedIntent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun logEvents(type: String) = nightLogText(context).lines().mapNotNull(::parseNightLogLine).filter { it.type == type }
}
