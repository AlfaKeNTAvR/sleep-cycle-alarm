package com.nikita.sleepcycle.night

// File purpose: PER-130 - the silent lock-screen notification for a band that syncs but measures no heart rate.
// Posted once when a sync first finds only -1 rows, left alone by a failed sync in between, removed once real
// values return or the night ends; each post and removal is in the night log.

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nikita.sleepcycle.bridge.StaleReason
import com.nikita.sleepcycle.engine.NightSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BandNotMeasuringNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val startedAt = Instant.parse("2026-11-02T04:30:00Z")
    private val now = startedAt.plusSeconds(1800)
    private val night = NightState(
        startedAt = startedAt, settings = NightSettings(deadline = null, pickedCycles = 4), lastPlan = null,
        lastSyncAt = null, lastSyncOk = null, lastSegments = emptyList(), lastExportFileModifiedAt = null, lastSyncFailureCause = null
    )
    private val notMeasuring = SyncOutcome(emptyList(), null, false, now, null, staleReason = StaleReason.NO_HEART_RATE_VALUES)
    private val fresh = SyncOutcome(emptyList(), now, true, now, null)
    private val failed = SyncOutcome(emptyList(), null, false, null, "timed out after PT20S")

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        manager.cancelAll()
        nightLogFile(context, startedAt, debugNight = false).delete()
    }

    @Test
    fun `the first sync with only -1 rows posts a silent public notification and logs it`() {
        stepBandNotMeasuringNotification(context, night, notMeasuring, now)

        val posted = checkNotNull(bandNotMeasuringNotification()) { "not posted" }
        assertEquals(Notification.VISIBILITY_PUBLIC, posted.visibility)
        val channel = manager.getNotificationChannel(posted.channelId)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertNull("no sound", channel.sound)
        assertFalse("no vibration", channel.shouldVibrate())
        assertEquals(listOf("true"), logEvents("band_not_measuring_posted").map { it.fields["notificationPosted"] })
    }

    @Test
    fun `a later -1 tick does not post again, a failed sync leaves it, and real values clear it`() {
        stepBandNotMeasuringNotification(context, night, notMeasuring, now)
        val stillNotMeasuring = night.copy(lastSyncOk = false, lastStaleReason = StaleReason.NO_HEART_RATE_VALUES)

        stepBandNotMeasuringNotification(context, stillNotMeasuring, notMeasuring, now.plusSeconds(900))
        stepBandNotMeasuringNotification(context, stillNotMeasuring, failed, now.plusSeconds(1800))
        assertNotNull("a failed sync says nothing about measuring", bandNotMeasuringNotification())

        stepBandNotMeasuringNotification(context, stillNotMeasuring, fresh, now.plusSeconds(2700))

        assertNull(bandNotMeasuringNotification())
        assertEquals(1, logEvents("band_not_measuring_posted").size)
        assertEquals(listOf("real_heart_rate_returned"), logEvents("band_not_measuring_cleared").map { it.fields["cause"] })
    }

    @Test
    fun `a fresh sync with nothing showing logs nothing`() {
        stepBandNotMeasuringNotification(context, night, fresh, now)

        assertEquals(0, logEvents("band_not_measuring_cleared").size)
    }

    private fun bandNotMeasuringNotification(): Notification? =
        shadowOf(manager).allNotifications.firstOrNull { it.channelId == "band_not_measuring" }

    private fun logEvents(type: String): List<NightLogEvent> {
        val file = nightLogFile(context, startedAt, debugNight = false)
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull(::parseNightLogLine).filter { it.type == type }
    }
}
