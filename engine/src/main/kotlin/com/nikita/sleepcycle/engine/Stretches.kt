package com.nikita.sleepcycle.engine

/**
 * Groups a normalized timeline into continuous sleep runs (spec step 1). Sleep intervals that touch, overlap, or
 * are separated only by a data gap belong to one stretch; only a recorded awake interval ends one.
 * `followsAwakening` is true only when an earlier stretch already exists and an awake interval sits between it
 * and this one: a stray awake mark before the very first stretch of the night does not count.
 */
fun buildSleepStretches(intervals: List<NormalizedInterval>): List<SleepStretch> {
    val builder = intervals.fold(StretchBuilder()) { acc, interval -> acc.accept(interval) }
    return builder.finish()
}

/** Immutable fold accumulator: the stretch under construction, whether an awakening was just seen, and the rest. */
private data class StretchBuilder(
    val current: SleepStretch? = null,
    val sawAwakening: Boolean = false,
    val completed: List<SleepStretch> = emptyList()
) {
    fun accept(interval: NormalizedInterval): StretchBuilder =
        if (!interval.asleep) closeCurrent() else extend(interval)

    private fun closeCurrent(): StretchBuilder =
        StretchBuilder(current = null, sawAwakening = true, completed = current?.let { completed + it } ?: completed)

    private fun extend(interval: NormalizedInterval): StretchBuilder {
        val extended = current?.copy(end = interval.end)
            ?: SleepStretch(interval.start, interval.end, followsAwakening = sawAwakening && completed.isNotEmpty())
        return copy(current = extended)
    }

    fun finish(): List<SleepStretch> = current?.let { completed + it } ?: completed
}

/**
 * Detects the current state from the same normalized timeline used by [buildSleepStretches] (spec step 1), so the
 * two can never disagree. `NOT_YET_ASLEEP` when no sleep interval exists at all; otherwise the last interval of
 * the timeline decides, and a tie in end time favors the awake interval (it already won the overlap during
 * normalization).
 */
fun detectSleepState(intervals: List<NormalizedInterval>): SleepState {
    if (intervals.none { it.asleep }) return SleepState.NOT_YET_ASLEEP
    val latest = intervals.reduce(::later)
    return if (latest.asleep) SleepState.ASLEEP else SleepState.AWAKE
}

private fun later(a: NormalizedInterval, b: NormalizedInterval): NormalizedInterval = when {
    a.end.isAfter(b.end) -> a
    b.end.isAfter(a.end) -> b
    !a.asleep -> a
    else -> b
}
