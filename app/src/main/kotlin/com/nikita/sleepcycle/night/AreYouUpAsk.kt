package com.nikita.sleepcycle.night

// File purpose: PER-151 - the Are you up? ask's own record and the rule for when it goes up. A No-alarm night has
// no morning alarm to say the night is moving on, so once the band shows the sleeper awake in the morning the
// app asks instead. Pure over engine types, like AwakeningLog.kt; the notification, the banner and the answers
// live in AreYouUpNotification.kt.

import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.engine.SleepSegment
import com.nikita.sleepcycle.engine.SleepState
import com.nikita.sleepcycle.engine.buildSleepStretches
import com.nikita.sleepcycle.engine.normalizeSegments
import com.nikita.sleepcycle.engine.sumSleepAlreadyHad
import java.time.Duration
import java.time.Instant

/** Owner spec, PER-151: the sleep a night must already have had before a morning awakening is asked about. */
val ARE_YOU_UP_MIN_SLEEP: Duration = Duration.ofHours(6)

/** Owner spec, PER-151: how long the band must show an awakening before it is asked about. */
val ARE_YOU_UP_MIN_AWAKENING: Duration = Duration.ofMinutes(15)

enum class AreYouUpAnswer { IM_UP, STILL_SLEEPING }

/**
 * The night's latest Are you up? ask, persisted in [NightState.areYouUp] so it survives the app being killed.
 * [awakeningStartedAt] is when the band's awakening that raised it began - what "the same awakening" means for
 * Still sleeping. Open while [answer] is null; it stays open until answered, however long that takes.
 */
data class AreYouUpAsk(
    val awakeningStartedAt: Instant,
    val askedAt: Instant,
    val answer: AreYouUpAnswer? = null,
    val answeredAt: Instant? = null,
) {
    val isOpen: Boolean get() = answer == null
}

/**
 * Whether the ask should go up now, and for which awakening: the start of the band's current awakening, or
 * null for no new ask. Only on a No-alarm night ([noAlarmNight]), never once I'm up has been pressed
 * ([imUpPressed]) and never while an ask is still open. The band must show the sleeper awake right now, in an
 * awakening that has itself lasted [ARE_YOU_UP_MIN_AWAKENING], after at least [ARE_YOU_UP_MIN_SLEEP] asleep that
 * night in total (every stretch, as the engine counts "slept tonight").
 *
 * After Still sleeping ([previousAsk] answered) only a later, separate awakening is asked about: one with sleep
 * between it and the awakening already answered. Matching by "sleep in between" rather than by an exact start
 * keeps a start that shifts as late band data arrives from being taken for a new awakening.
 */
fun findAwakeningToAskAreYouUp(
    noAlarmNight: Boolean,
    imUpPressed: Boolean,
    previousAsk: AreYouUpAsk?,
    segments: List<SleepSegment>,
    now: Instant,
    config: EngineConfig,
): Instant? {
    if (!noAlarmNight || imUpPressed || previousAsk?.isOpen == true) return null
    val timeline = normalizeSegments(segments, now, config)
    val awakening = timeline.lastOrNull()?.takeIf { !it.asleep } ?: return null
    if (Duration.between(awakening.start, awakening.end) < ARE_YOU_UP_MIN_AWAKENING) return null
    if (sumSleepAlreadyHad(buildSleepStretches(timeline), SleepState.AWAKE) < ARE_YOU_UP_MIN_SLEEP) return null
    val sleptSinceAnswered = previousAsk == null || timeline.any { it.asleep && it.start.isAfter(previousAsk.awakeningStartedAt) }
    return awakening.start.takeIf { sleptSinceAnswered }
}
