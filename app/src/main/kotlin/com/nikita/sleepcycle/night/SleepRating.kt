package com.nikita.sleepcycle.night

// File purpose: the sleep rating (owner spec, 2026-10-02) as the night log keeps it. Two ratings per night,
// stored separately, each with its own time: one on the morning report right after End night, and one later
// in the day from the "Still feel the same?" notification. Both are plain `sleep_rating` lines appended to
// that night's own log, so changing one later (Past night) is one more line, and the last line per moment
// wins. Only nights whose night_start carries the `rateable` marker - every night started since this build -
// can be rated. Pure: the Android glue is SleepRatingAsk.kt.

import java.time.Instant
import java.time.ZoneId

private const val RATEABLE_FIELD = "rateable"
private const val SLEEP_RATING_EVENT_TYPE = "sleep_rating"
private const val LATER_RATING_ASKED_EVENT_TYPE = "sleep_rating_asked"
private const val MOMENT_FIELD = "moment"
private const val RATING_FIELD = "rating"

/** How the night felt. Logged in lower case. */
enum class SleepRating { GOOD, OKAY, BAD }

/** Which of the two ratings: right after End night, or later in the day. Logged in lower case. */
enum class RatingMoment { AFTER_END_NIGHT, LATER }

/** One rating and when it was given (or last changed). */
data class RecordedRating(val rating: SleepRating, val at: Instant)

/** A rateable night's two ratings, either missing until given, and whether the later question was already asked. */
data class NightRatings(val afterEndNight: RecordedRating?, val later: RecordedRating?, val laterAsked: Boolean)

/** Added to night_start: marks the night as started by a build that can rate it. */
fun nightStartRatingFields(): Map<String, String> = mapOf(RATEABLE_FIELD to true.toString())

/** The log line for [rating] given at [moment], at [at]. */
fun sleepRatingEvent(moment: RatingMoment, rating: SleepRating, at: Instant): NightLogEvent = NightLogEvent(
    at, SLEEP_RATING_EVENT_TYPE,
    mapOf(MOMENT_FIELD to moment.name.lowercase(), RATING_FIELD to rating.name.lowercase()),
)

/** The log line for the later question having been posted, so it is asked once per night. */
fun laterRatingAskedEvent(at: Instant): NightLogEvent = NightLogEvent(at, LATER_RATING_ASKED_EVENT_TYPE, emptyMap())

/** A night's ratings from its log events, in log order. Null when the night cannot be rated (started before the rating existed). */
fun readNightRatings(events: List<NightLogEvent>): NightRatings? {
    val start = events.lastOrNull { it.type == NIGHT_START_EVENT_TYPE } ?: return null
    if (start.fields[RATEABLE_FIELD] != true.toString()) return null
    val ratings = events.filter { it.type == SLEEP_RATING_EVENT_TYPE }.mapNotNull(::parseRatingEvent)
    return NightRatings(
        afterEndNight = ratings.lastOrNull { it.first == RatingMoment.AFTER_END_NIGHT }?.second,
        later = ratings.lastOrNull { it.first == RatingMoment.LATER }?.second,
        laterAsked = events.any { it.type == LATER_RATING_ASKED_EVENT_TYPE },
    )
}

/** Null for a rating line whose moment or value this build does not know. */
private fun parseRatingEvent(event: NightLogEvent): Pair<RatingMoment, RecordedRating>? {
    val moment = RatingMoment.entries.firstOrNull { it.name.lowercase() == event.fields[MOMENT_FIELD] } ?: return null
    val rating = SleepRating.entries.firstOrNull { it.name.lowercase() == event.fields[RATING_FIELD] } ?: return null
    return moment to RecordedRating(rating, event.at)
}

/**
 * Whether the later question should still be asked about [night]: the rating and "Ask again later" are both
 * on in Settings, the night can be rated and has ended, and its later rating is neither given nor asked yet.
 * The morning rating does not matter - a skipped one is asked about the same way.
 */
fun shouldAskLaterRating(night: PastNightLog, settings: SleepRatingSettings): Boolean {
    val ratings = night.ratings ?: return false
    return settings.enabled && settings.askAgainLater && night.endedAt != null && ratings.later == null && !ratings.laterAsked
}

/**
 * When to post the later question about [night]: [SleepRatingSettings.askAt] on the day it ended, in [zone].
 * Null when it should not be asked ([shouldAskLaterRating]), when the night ended after that time (a late
 * morning is not asked about the next afternoon), or when that time is already past [now] (the phone was off,
 * or the time was changed to one already gone) - a missed question is skipped rather than asked late.
 */
fun laterRatingAskAt(night: PastNightLog, settings: SleepRatingSettings, zone: ZoneId, now: Instant): Instant? {
    if (!shouldAskLaterRating(night, settings)) return null
    val endedAt = night.endedAt ?: return null
    val askAt = endedAt.atZone(zone).toLocalDate().atTime(settings.askAt).atZone(zone).toInstant()
    return askAt.takeIf { it > endedAt && it > now }
}

/** Which later question the notification asks: recalling the morning rating, or asking fresh when it was skipped. */
sealed interface LaterRatingQuestion {
    /** "Still feel the same about last night?" with "This morning you said Good." */
    data class StillFeelTheSame(val morningRating: SleepRating) : LaterRatingQuestion

    /** "How do you feel about last night?" */
    data object HowDoYouFeel : LaterRatingQuestion
}

/** The later question for a night rated (or not) as [ratings] says. */
fun laterRatingQuestion(ratings: NightRatings): LaterRatingQuestion =
    ratings.afterEndNight?.let { LaterRatingQuestion.StillFeelTheSame(it.rating) } ?: LaterRatingQuestion.HowDoYouFeel
