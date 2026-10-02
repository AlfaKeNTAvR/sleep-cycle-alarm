package com.nikita.sleepcycle.night

// File purpose: the Android side of the sleep rating (owner spec, 2026-10-02). Writes a rating into a night's
// log, and keeps the later "Still feel the same about last night?" notification armed for the newest night:
// one exact alarm at the time laterRatingAskAt (SleepRating.kt) says, whose receiver posts a notification with
// Good / Okay / Bad action buttons that rate without opening the app.
//
// [syncLaterRatingAsk] is the one place that arms or cancels that alarm. It is called whenever its inputs may
// have changed - a night started or ended, a rating given, the Settings changed, the app opened, the phone
// rebooted - and re-derives the answer from the newest log and the settings every time, so no caller has to
// know the rules. The ask time is real (wall clock), not the debug simulated clock: it is a daytime question.

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId

private const val LOG_TAG = "SleepRatingAsk"
private const val LATER_RATING_ASK_REQUEST_CODE = 2005
private const val LATER_RATING_OPEN_APP_REQUEST_CODE = 4002
/** One request code per rating button, so the three PendingIntents never replace one another. */
private const val LATER_RATING_BUTTON_REQUEST_CODE_BASE = 2010
private const val LATER_RATING_NOTIFICATION_ID = 5
private const val LATER_RATING_CHANNEL_ID = "sleep_rating"
private const val ACTION_ASK = "com.nikita.sleepcycle.action.ASK_LATER_RATING"
private const val ACTION_RATE = "com.nikita.sleepcycle.action.RATE_LATER"
private const val EXTRA_LOG_FILE_NAME = "logFileName"
private const val EXTRA_RATING = "rating"

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Appends [rating] for [moment] to the night log [logFile], timestamped now, then re-syncs the later question (a later rating cancels it). */
suspend fun recordSleepRating(context: Context, logFile: File, moment: RatingMoment, rating: SleepRating) {
    appendLogLine(logFile, sleepRatingEvent(moment, rating, Instant.now()))
    syncLaterRatingAsk(context)
}

/**
 * Arms the later question's alarm for the newest saved night when [laterRatingAskAt] gives a time, and cancels
 * it otherwise. A running night is the newest log and has not ended, so starting a night cancels the question
 * about the one before.
 */
suspend fun syncLaterRatingAsk(context: Context) {
    // Called from inside Start night and the boot receiver too: a failure here only loses the later question,
    // so it is logged and never allowed to break the caller.
    try {
        armOrCancelLaterRatingAsk(context)
    } catch (error: Exception) {
        Log.e(LOG_TAG, "could not arm or cancel the later rating question", error)
    }
}

private suspend fun armOrCancelLaterRatingAsk(context: Context) {
    val settings = readAppSettings(context).first().sleepRating
    val newestLog = listNightLogs(context).firstOrNull()
    val askAt = newestLog?.let { laterRatingAskAt(readPastNightLog(it), settings, ZoneId.systemDefault(), Instant.now()) }
    val alarmManager = context.getSystemService<AlarmManager>() ?: return
    if (askAt == null) {
        alarmManager.cancel(askPendingIntent(context, logFileName = null))
        return
    }
    scheduleAsk(context, alarmManager, askAt, newestLog.name)
}

@SuppressLint("MissingPermission")
private fun scheduleAsk(context: Context, alarmManager: AlarmManager, at: Instant, logFileName: String) {
    try {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), askPendingIntent(context, logFileName))
    } catch (error: SecurityException) {
        Log.e(LOG_TAG, "cannot schedule the later rating question: exact alarm permission was likely revoked", error)
    }
}

/** The ask alarm's intent. [logFileName] null builds the same identity for cancelling, extras being ignored when matching. */
private fun askPendingIntent(context: Context, logFileName: String?): PendingIntent {
    val intent = Intent(context, LaterRatingReceiver::class.java).setAction(ACTION_ASK)
    logFileName?.let { intent.putExtra(EXTRA_LOG_FILE_NAME, it) }
    return PendingIntent.getBroadcast(context, LATER_RATING_ASK_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

private fun rateButtonPendingIntent(context: Context, logFileName: String, rating: SleepRating): PendingIntent {
    val intent = Intent(context, LaterRatingReceiver::class.java)
        .setAction(ACTION_RATE)
        .putExtra(EXTRA_LOG_FILE_NAME, logFileName)
        .putExtra(EXTRA_RATING, rating.name)
    return PendingIntent.getBroadcast(
        context, LATER_RATING_BUTTON_REQUEST_CODE_BASE + rating.ordinal, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** The ask alarm firing posts the question; a tap on one of its buttons stores that rating and removes the notification. */
class LaterRatingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val logFile = intent.getStringExtra(EXTRA_LOG_FILE_NAME)?.let { name -> listNightLogs(context).firstOrNull { it.name == name } }
        if (logFile == null) {
            Log.w(LOG_TAG, "later rating ${intent.action} for a night log that no longer exists")
            return
        }
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                when (intent.action) {
                    ACTION_ASK -> postLaterRatingQuestion(context, logFile)
                    ACTION_RATE -> rateFromNotification(context, logFile, intent.getStringExtra(EXTRA_RATING))
                }
            } catch (error: Exception) {
                Log.e(LOG_TAG, "later rating ${intent.action} failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/** Posts the question unless something since the alarm was armed makes it moot (rated already, switched off), and logs that it was asked. */
private suspend fun postLaterRatingQuestion(context: Context, logFile: File) {
    val night = readPastNightLog(logFile)
    val ratings = night.ratings ?: return
    if (!shouldAskLaterRating(night, readAppSettings(context).first().sleepRating)) return
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        Log.w(LOG_TAG, "notifications are not allowed, the later rating question cannot be shown")
        return
    }
    val manager = context.getSystemService<NotificationManager>() ?: return
    manager.createNotificationChannel(
        NotificationChannel(LATER_RATING_CHANNEL_ID, context.getString(R.string.rating_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
    )
    manager.notify(LATER_RATING_NOTIFICATION_ID, buildLaterRatingNotification(context, logFile.name, laterRatingQuestion(ratings)))
    appendLogLine(logFile, laterRatingAskedEvent(Instant.now()))
}

private fun buildLaterRatingNotification(context: Context, logFileName: String, question: LaterRatingQuestion) =
    NotificationCompat.Builder(context, LATER_RATING_CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(
            when (question) {
                is LaterRatingQuestion.StillFeelTheSame -> context.getString(R.string.rating_later_title_still_same)
                LaterRatingQuestion.HowDoYouFeel -> context.getString(R.string.rating_later_title_how_do_you_feel)
            }
        )
        .apply {
            if (question is LaterRatingQuestion.StillFeelTheSame) {
                setContentText(context.getString(R.string.rating_later_body_morning_said, context.getString(sleepRatingLabelRes(question.morningRating))))
            }
        }
        .setContentIntent(
            PendingIntent.getActivity(
                context, LATER_RATING_OPEN_APP_REQUEST_CODE, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .apply {
            SleepRating.entries.forEach { rating ->
                addAction(0, context.getString(sleepRatingLabelRes(rating)), rateButtonPendingIntent(context, logFileName, rating))
            }
        }
        .setAutoCancel(true)
        .build()

private suspend fun rateFromNotification(context: Context, logFile: File, ratingName: String?) {
    val rating = SleepRating.entries.firstOrNull { it.name == ratingName } ?: return
    recordSleepRating(context, logFile, RatingMoment.LATER, rating)
    context.getSystemService<NotificationManager>()?.cancel(LATER_RATING_NOTIFICATION_ID)
}

/** The word shown for each rating, on the faces, the chips and the notification buttons alike. */
fun sleepRatingLabelRes(rating: SleepRating): Int = when (rating) {
    SleepRating.GOOD -> R.string.rating_good
    SleepRating.OKAY -> R.string.rating_okay
    SleepRating.BAD -> R.string.rating_bad
}
