package com.nikita.sleepcycle.scenario

// File purpose: the 15:00 "Still feel the same about last night?" notification (owner spec, 2026-10-04). Good
// still rates with one tap and opens nothing; Okay or Bad rates too, and opens the app on that night's 15:00
// "How is your afternoon?" dialog. Drives the real receiver and the notification's own PendingIntents.

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.nikita.sleepcycle.MainActivity
import com.nikita.sleepcycle.night.LaterRatingFromNotification
import com.nikita.sleepcycle.night.NightLogEvent
import com.nikita.sleepcycle.night.SleepRating
import com.nikita.sleepcycle.night.appendLogLine
import com.nikita.sleepcycle.night.deleteNightLog
import com.nikita.sleepcycle.night.laterRatingAskIntent
import com.nikita.sleepcycle.night.nightLogFile
import com.nikita.sleepcycle.night.nightStartRatingFields
import com.nikita.sleepcycle.night.readPastNightLog
import com.nikita.sleepcycle.night.recordLaterRatingFromNotification
import kotlinx.coroutines.delay
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

        val opened = recordLaterRatingFromNotification(context, tap.savedIntent)
        assertEquals(LaterRatingFromNotification(logFile, SleepRating.OKAY), opened)
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

    /** Fires the 15:00 alarm the way AlarmManager does and waits for the question to be posted. */
    private suspend fun postLaterQuestion(): Notification {
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
        context.sendBroadcast(laterRatingAskIntent(context, logFile.name))
        shadowOf(Looper.getMainLooper()).idle()
        withTimeout(10_000) { while (notifications.allNotifications.isEmpty()) delay(20) }
        return notifications.allNotifications.single()
    }
}
