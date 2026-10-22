package com.nikita.sleepcycle.scenario

// File purpose: the 15:00 "Still feel the same about last night?" notification (owner spec, 2026-10-04). Good
// still rates with one tap and opens nothing; Okay or Bad rates too, and opens the app on that night's 15:00
// "How is your afternoon?" dialog. Owner spec, 2026-10-21 (PER-124): Okay, Bad and a tap on the notification
// itself open that night's Past night screen, the dialog on top for Okay or Bad. Drives the real receiver, the
// notification's own PendingIntents and the ViewModel the app opens through.

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.nikita.sleepcycle.MainActivity
import com.nikita.sleepcycle.night.LaterRatingNotificationTap
import com.nikita.sleepcycle.night.NightLogEvent
import com.nikita.sleepcycle.night.SleepRating
import com.nikita.sleepcycle.night.appendLogLine
import com.nikita.sleepcycle.night.deleteNightLog
import com.nikita.sleepcycle.night.laterRatingAskIntent
import com.nikita.sleepcycle.night.nightLogFile
import com.nikita.sleepcycle.night.nightStartRatingFields
import com.nikita.sleepcycle.night.RatingMoment
import com.nikita.sleepcycle.night.RatingSymptom
import com.nikita.sleepcycle.night.handleLaterRatingNotificationTap
import com.nikita.sleepcycle.night.ratingSymptomsEvent
import com.nikita.sleepcycle.night.readPastNightLog
import com.nikita.sleepcycle.night.sleepRatingEvent
import com.nikita.sleepcycle.ui.NightViewModel
import com.nikita.sleepcycle.ui.state.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LaterRatingNotificationScenarioTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val application = context as Application
    private lateinit var logFile: File

    @Before
    fun setUp() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // A rateable night that ended this morning, not yet rated later in the day.
        val startedAt = Instant.now().minus(Duration.ofHours(10))
        logFile = nightLogFile(context, startedAt, debugNight = false)
        appendLogLine(logFile, NightLogEvent(startedAt, "night_start", mapOf("pickedCycles" to "5", "deadline" to "none") + nightStartRatingFields()))
        appendLogLine(logFile, NightLogEvent(startedAt.plus(Duration.ofHours(7)), "night_end", mapOf("summary" to "total=PT7H stretches=1")))
    }

    @After
    fun tearDown() {
        deleteNightLog(logFile)
    }

    @Test
    fun `Okay on the 15 00 notification records the rating and opens the app on that night's dialog`() = runBlocking {
        val okay = postLaterQuestion().actions.first { it.title == "Okay" }

        val tap = shadowOf(okay.actionIntent)
        assertTrue("Okay opens the app rather than rating in the background", tap.isActivityIntent)
        assertEquals(MainActivity::class.java.name, tap.savedIntent.component?.className)

        val opened = handleLaterRatingNotificationTap(context, tap.savedIntent)
        assertEquals(LaterRatingNotificationTap(logFile, SleepRating.OKAY), opened)
        assertEquals(SleepRating.OKAY, readPastNightLog(logFile).ratings?.later?.rating)
    }

    @Test
    fun `Good on the 15 00 notification rates with one tap and opens nothing`() = runBlocking {
        val good = postLaterQuestion().actions.first { it.title == "Good" }

        val tap = shadowOf(good.actionIntent)
        assertTrue("Good still rates without opening the app", tap.isBroadcastIntent)
        context.sendBroadcast(tap.savedIntent)
        shadowOf(Looper.getMainLooper()).idle()
        withTimeout(10_000) { while (readPastNightLog(logFile).ratings?.later == null) delay(20) }

        assertEquals(SleepRating.GOOD, readPastNightLog(logFile).ratings?.later?.rating)
        assertNull("no app screen is opened", shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `a tap on the 15 00 notification itself opens the app on that night and rates nothing`() = runBlocking {
        val body = shadowOf(postLaterQuestion().contentIntent)
        assertTrue("the body opens the app", body.isActivityIntent)
        assertEquals(MainActivity::class.java.name, body.savedIntent.component?.className)

        // Owner spec, 2026-10-21: "it should open the log for me" - the body names the night, like the buttons do.
        assertEquals(LaterRatingNotificationTap(logFile, rating = null), handleLaterRatingNotificationTap(context, body.savedIntent))
        assertNull("opening the log is not a rating", readPastNightLog(logFile).ratings?.later)
    }

    @Test
    fun `Okay opens that night's Past night with the afternoon dialog on top, prefilled`() = runBlocking {
        val bad = shadowOf(postLaterQuestion().actions.first { it.title == "Bad" }.actionIntent)
        // Ticked at an earlier Okay (given in the app while the notification was up), so the dialog must come
        // back with them (owner change, 2026-10-05).
        appendLogLine(logFile, sleepRatingEvent(RatingMoment.LATER, SleepRating.OKAY, Instant.now().minusSeconds(60)))
        appendLogLine(logFile, ratingSymptomsEvent(RatingMoment.LATER, setOf(RatingSymptom.LOW_ENERGY), Instant.now().minusSeconds(50)))

        val viewModel = NightViewModel(application)
        viewModel.openFromIntent(bad.savedIntent)

        // Owner spec, 2026-10-21: "if I click anything else, it should open the log for me, so I can pick the symptoms there."
        awaitOnMain { viewModel.symptomsDialog.value != null && viewModel.pastNight.value != null }
        val dialog = viewModel.symptomsDialog.value
        assertEquals(RatingMoment.LATER, dialog?.moment)
        assertEquals(SleepRating.BAD, dialog?.rating)
        assertEquals(setOf(RatingSymptom.LOW_ENERGY), dialog?.selected)
        assertEquals(SleepRating.BAD, viewModel.pastNight.value?.ratings?.later?.rating)
        assertScreenSettlesOn(viewModel, Screen.PastNight)
    }

    @Test
    fun `a body tap opens that night's Past night without a dialog, and Back from it goes to the Logs list`() = runBlocking {
        val body = shadowOf(postLaterQuestion().contentIntent)

        val viewModel = NightViewModel(application)
        viewModel.openFromIntent(body.savedIntent)

        awaitOnMain { viewModel.pastNight.value != null }
        assertNull("only Okay or Bad asks for symptoms", viewModel.symptomsDialog.value)
        assertScreenSettlesOn(viewModel, Screen.PastNight)

        // Acceptance, 2026-10-21: Back never lands on a blank screen.
        viewModel.closePastNight()
        assertScreenSettlesOn(viewModel, Screen.Logs)
    }

    @Test
    fun `the app already open elsewhere switches to that night's Past night`() = runBlocking {
        val okay = shadowOf(postLaterQuestion().actions.first { it.title == "Okay" }.actionIntent)
        val viewModel = NightViewModel(application)
        settledScreen(viewModel)
        viewModel.openSettings()
        assertScreenSettlesOn(viewModel, Screen.Settings)

        // onNewIntent hands the ViewModel the same intent the cold start does.
        viewModel.openFromIntent(okay.savedIntent)

        awaitOnMain { viewModel.symptomsDialog.value != null }
        assertScreenSettlesOn(viewModel, Screen.PastNight)
    }

    /**
     * Waits for the ViewModel's screen to stop changing, then checks it is [expected]. A cold start resolves its
     * own first screen at the same time as the intent is handled, and must not win over it afterwards.
     */
    private fun assertScreenSettlesOn(viewModel: NightViewModel, expected: Screen) {
        whileObserving(viewModel) { awaitOnMain { viewModel.uiState.value.screen == expected } }
        assertEquals(expected, settledScreen(viewModel))
    }

    /** The screen the ViewModel shows once everything it started has had time to finish. */
    private fun settledScreen(viewModel: NightViewModel): Screen = whileObserving(viewModel) {
        repeat(SETTLE_ROUNDS) { idleMainBriefly() }
        viewModel.uiState.value.screen
    }

    /** uiState only runs while something collects it, as the app's own UI does. */
    private fun <T> whileObserving(viewModel: NightViewModel, block: () -> T): T {
        val collector = CoroutineScope(Dispatchers.Main).launch { viewModel.uiState.collect {} }
        try {
            return block()
        } finally {
            collector.cancel()
        }
    }

    /** Runs the main looper (the ViewModel's own dispatcher) until [done], or fails after 10 s. */
    private fun awaitOnMain(done: () -> Boolean) {
        val giveUpAt = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS
        while (!done()) {
            assertTrue("timed out waiting for the app to open the night", System.currentTimeMillis() < giveUpAt)
            idleMainBriefly()
        }
    }

    private fun idleMainBriefly() {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(IDLE_STEP_MILLIS)
    }

    /** Fires the 15:00 alarm the way AlarmManager does and waits for the question to be posted. */
    private suspend fun postLaterQuestion(): Notification {
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
        context.sendBroadcast(laterRatingAskIntent(context, logFile.name))
        shadowOf(Looper.getMainLooper()).idle()
        withTimeout(10_000) { while (notifications.allNotifications.isEmpty()) delay(20) }
        return notifications.allNotifications.single()
    }

    private companion object {
        const val AWAIT_TIMEOUT_MILLIS = 10_000L
        const val IDLE_STEP_MILLIS = 20L
        const val SETTLE_ROUNDS = 25
    }
}
