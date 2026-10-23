package com.nikita.sleepcycle.night

// File purpose: PER-151 - the Android side of the Are you up? ask on a No-alarm night. The tick raises it
// ([stepAreYouUpAsk], deciding through AreYouUpAsk.kt's pure rule) and posts a silent lock-screen notification
// with I'm up and Still sleeping buttons; the Night screen shows the same question as a banner from
// NightState.areYouUp. I'm up, from either place, is the Night screen's own I'm up (pressImUp, which closes the
// ask itself); Still sleeping only closes it. A dismissed notification is posted again at once (its delete
// intent), and a tick finding the ask open with no notification showing (after a reboot) posts it again: it
// stays until answered. Nothing here ever ends the night.
//
// Same notification style as SleepRatingAsk.kt: buttons are broadcast PendingIntents to one receiver, the body
// opens the app. Every write to the ask runs under the night lock, like the tick that raises it.

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.nikita.sleepcycle.MainActivity
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.engine.SleepSegment
import com.nikita.sleepcycle.engine.morningAlarmHasRung
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant

private const val LOG_TAG = "AreYouUp"
private const val ARE_YOU_UP_NOTIFICATION_ID = 6
private const val ARE_YOU_UP_CHANNEL_ID = "are_you_up"
private const val ARE_YOU_UP_IM_UP_REQUEST_CODE = 2020
private const val ARE_YOU_UP_STILL_SLEEPING_REQUEST_CODE = 2021
private const val ARE_YOU_UP_DISMISSED_REQUEST_CODE = 2022
private const val ARE_YOU_UP_OPEN_APP_REQUEST_CODE = 4020
private const val ACTION_IM_UP = "com.nikita.sleepcycle.action.ARE_YOU_UP_IM_UP"
private const val ACTION_STILL_SLEEPING = "com.nikita.sleepcycle.action.ARE_YOU_UP_STILL_SLEEPING"
private const val ACTION_DISMISSED = "com.nikita.sleepcycle.action.ARE_YOU_UP_DISMISSED"

/** Where an answer came from, for the night log's `via` field. */
const val ARE_YOU_UP_VIA_NOTIFICATION = "notification"
const val ARE_YOU_UP_VIA_BANNER = "banner"
const val ARE_YOU_UP_VIA_NIGHT_SCREEN = "night_screen"

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * The tick's share, run under its night lock: raises a new ask when [findAwakeningToAskAreYouUp] says so, and
 * otherwise puts back the notification of an ask still open when it is no longer showing (a reboot clears the
 * shade). Returns the ask for the tick to save into the night state.
 */
internal fun stepAreYouUpAsk(context: Context, state: NightState, segments: List<SleepSegment>, now: Instant): AreYouUpAsk? {
    val debugNight = state.debugOptions.isAnyEnabled
    val previous = state.areYouUp
    val awakeningStartedAt = findAwakeningToAskAreYouUp(
        state.settings.noAlarm, morningAlarmHasRung(state.wakeAlarmFiredAt, state.morningAlarmAt, state.phoneAlarmFiredFor),
        previous, segments, now, resolveEngineConfig(state),
    )
    if (awakeningStartedAt != null) {
        val posted = postAreYouUpNotification(context)
        appendNightLog(
            context, state.realStartedAt,
            NightLogEvent(now, "are_you_up_asked", mapOf("awakeningStartedAt" to awakeningStartedAt.toString(), "notificationPosted" to posted.toString())),
            debugNight
        )
        return AreYouUpAsk(awakeningStartedAt, askedAt = now)
    }
    if (previous?.isOpen == true && !isAreYouUpNotificationShowing(context) && postAreYouUpNotification(context)) {
        appendNightLog(context, state.realStartedAt, NightLogEvent(now, "are_you_up_reposted", mapOf("cause" to "missing")), debugNight)
    }
    return previous
}

/**
 * Closes [state]'s open ask with [answer]: saves and publishes the state, removes the notification and logs the
 * answer. The caller holds the night lock and has checked the ask is open. Returns the new state.
 */
internal fun closeOpenAreYouUpAsk(context: Context, state: NightState, answer: AreYouUpAnswer, now: Instant, via: String): NightState {
    val ask = checkNotNull(state.areYouUp)
    val closed = state.copy(areYouUp = ask.copy(answer = answer, answeredAt = now))
    val debugNight = state.debugOptions.isAnyEnabled
    if (!saveNightState(context, closed)) {
        appendNightLog(context, state.realStartedAt, NightLogEvent(now, "error", mapOf("step" to "save_night_state", "cause" to "failed to persist the Are you up? answer")), debugNight)
    }
    publishNightState(closed)
    cancelAreYouUpNotification(context)
    appendNightLog(
        context, state.realStartedAt,
        NightLogEvent(now, "are_you_up_answered", mapOf("answer" to answer.name, "via" to via, "awakeningStartedAt" to ask.awakeningStartedAt.toString())),
        debugNight
    )
    return closed
}

/** Still sleeping, from the notification or the banner: closes the open ask and arms nothing. Returns whether an ask was open. */
suspend fun answerStillSleeping(context: Context, now: Instant, via: String): Boolean = withNightTransactionLock {
    val state = loadNightState(context)
    if (state?.areYouUp?.isOpen != true) {
        // A stale notification or screen: nothing left to answer, so just make sure nothing is left showing.
        cancelAreYouUpNotification(context)
        return@withNightTransactionLock false
    }
    closeOpenAreYouUpAsk(context, state, AreYouUpAnswer.STILL_SLEEPING, now, via)
    true
}

/** The notification's I'm up: exactly what the Night screen's I'm up does once confirmed (NightViewModel.confirmImUp). */
private suspend fun answerImUpFromNotification(context: Context) {
    // Owner spec, 2026-10-02: a simulated night drops to 1x first, so the nudge is armed at real speed.
    dropSimulationToRealSpeed(context)
    pressImUp(context, nowInstant(), via = ARE_YOU_UP_VIA_NOTIFICATION)
    requestImmediateTick(context)
}

/** The notification was swiped away unanswered: post it again at once while the ask is still open. */
private suspend fun repostDismissedAsk(context: Context, now: Instant) = withNightTransactionLock {
    val state = loadNightState(context) ?: return@withNightTransactionLock
    if (state.areYouUp?.isOpen != true) return@withNightTransactionLock
    if (postAreYouUpNotification(context)) {
        appendNightLog(context, state.realStartedAt, NightLogEvent(now, "are_you_up_reposted", mapOf("cause" to "dismissed")), state.debugOptions.isAnyEnabled)
    }
}

fun cancelAreYouUpNotification(context: Context) {
    context.getSystemService<NotificationManager>()?.cancel(ARE_YOU_UP_NOTIFICATION_ID)
}

private fun isAreYouUpNotificationShowing(context: Context): Boolean =
    context.getSystemService<NotificationManager>()?.activeNotifications?.any { it.id == ARE_YOU_UP_NOTIFICATION_ID } == true

/** Posts (or replaces) the ask's notification. Returns false when notifications are not allowed; the banner still asks. */
private fun postAreYouUpNotification(context: Context): Boolean {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        Log.w(LOG_TAG, "notifications are not allowed, the Are you up? ask shows on the Night screen only")
        return false
    }
    val manager = context.getSystemService<NotificationManager>() ?: return false
    manager.createNotificationChannel(buildAreYouUpChannel(context))
    manager.notify(ARE_YOU_UP_NOTIFICATION_ID, buildAreYouUpNotification(context))
    return true
}

/**
 * Silent: no sound, no vibration, no light, so it can never wake a sleeper. DEFAULT importance rather than LOW on
 * purpose: Android files LOW as a "silent" notification, which the lock screen hides by default on Pixel phones,
 * and the ask must be on the lock screen. With nothing to play, DEFAULT makes no noise either.
 */
private fun buildAreYouUpChannel(context: Context) =
    NotificationChannel(ARE_YOU_UP_CHANNEL_ID, context.getString(R.string.are_you_up_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
        setSound(null, null)
        enableVibration(false)
        enableLights(false)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }

private fun buildAreYouUpNotification(context: Context) =
    NotificationCompat.Builder(context, ARE_YOU_UP_CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(context.getString(R.string.are_you_up_title))
        .setContentText(context.getString(R.string.are_you_up_body))
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                context, ARE_YOU_UP_OPEN_APP_REQUEST_CODE,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .addAction(0, context.getString(R.string.night_im_up), receiverPendingIntent(context, ACTION_IM_UP, ARE_YOU_UP_IM_UP_REQUEST_CODE))
        .addAction(0, context.getString(R.string.are_you_up_still_sleeping), receiverPendingIntent(context, ACTION_STILL_SLEEPING, ARE_YOU_UP_STILL_SLEEPING_REQUEST_CODE))
        .setDeleteIntent(receiverPendingIntent(context, ACTION_DISMISSED, ARE_YOU_UP_DISMISSED_REQUEST_CODE))
        .build()

private fun receiverPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent =
    PendingIntent.getBroadcast(
        context, requestCode, Intent(context, AreYouUpReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

/** The ask's two buttons and its delete intent. */
class AreYouUpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                val now = nowInstant()
                when (intent.action) {
                    ACTION_IM_UP -> answerImUpFromNotification(context)
                    ACTION_STILL_SLEEPING -> answerStillSleeping(context, now, ARE_YOU_UP_VIA_NOTIFICATION)
                    ACTION_DISMISSED -> repostDismissedAsk(context, now)
                }
            } catch (error: Exception) {
                Log.e(LOG_TAG, "Are you up? ${intent.action} failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
