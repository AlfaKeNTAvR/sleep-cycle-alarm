package com.nikita.sleepcycle.engine

import java.time.Duration
import java.time.Instant

/** One cleaned interval of the night: either asleep or awake, never both, never overlapping its neighbours. */
data class NormalizedInterval(val start: Instant, val end: Instant, val asleep: Boolean)

/** A plain time span, used only while merging and subtracting band marks below. */
private data class Interval(val start: Instant, val end: Instant)

/**
 * Turns raw, possibly messy band marks into one sorted, non-overlapping timeline (spec step 1). Drops zero-length
 * and future-dated marks, clips anything still running to `now`, drops awake blips shorter than `minAwakening`,
 * and lets AWAKE win wherever it overlaps sleep: an awake interval cuts every sleep segment it overlaps, and a
 * sleep segment that continues past it re-opens afterwards.
 */
fun normalizeSegments(segments: List<SleepSegment>, now: Instant, config: EngineConfig): List<NormalizedInterval> {
    validateConfig(config)
    val clipped = clipToNow(segments, now)
    val awake = mergeIntervals(significantAwakeIntervals(clipped, config))
    val sleep = mergeIntervals(clipped.filter { it.kind != SegmentKind.AWAKE }.map { Interval(it.start, it.end) })
    val sleepMinusAwake = subtractIntervals(sleep, awake)
    return (sleepMinusAwake.map { NormalizedInterval(it.start, it.end, asleep = true) } +
        awake.map { NormalizedInterval(it.start, it.end, asleep = false) })
        .sortedBy { it.start }
}

/** Drops zero/negative-length and future-dated marks, and clips anything still running to `now`. */
private fun clipToNow(segments: List<SleepSegment>, now: Instant): List<SleepSegment> =
    segments
        .filter { it.end.isAfter(it.start) }
        .filter { !it.start.isAfter(now) }
        .map { it.copy(end = minOf(it.end, now)) }
        .filter { it.end.isAfter(it.start) }

/** Awake marks that meet the `minAwakening` floor; shorter blips do not split a sleep stretch. */
private fun significantAwakeIntervals(segments: List<SleepSegment>, config: EngineConfig): List<Interval> =
    segments
        .filter { it.kind == SegmentKind.AWAKE }
        .filter { Duration.between(it.start, it.end) >= config.minAwakening }
        .map { Interval(it.start, it.end) }

/** Sorts and merges touching or overlapping intervals into the smallest equivalent set. */
private fun mergeIntervals(intervals: List<Interval>): List<Interval> {
    val merged = mutableListOf<Interval>()
    intervals.sortedBy { it.start }.forEach { interval ->
        val last = merged.lastOrNull()
        if (last != null && !interval.start.isAfter(last.end)) {
            merged[merged.lastIndex] = Interval(last.start, maxOf(last.end, interval.end))
        } else {
            merged.add(interval)
        }
    }
    return merged.toList()
}

/** Removes every part of [base] that any interval in [cuts] overlaps, splitting a base interval around a cut. */
private fun subtractIntervals(base: List<Interval>, cuts: List<Interval>): List<Interval> =
    base.flatMap { subtractFrom(it, cuts) }

private fun subtractFrom(segment: Interval, cuts: List<Interval>): List<Interval> {
    val overlapping = cuts.filter { it.start.isBefore(segment.end) && it.end.isAfter(segment.start) }
    return overlapping.fold(listOf(segment)) { pieces, cut -> pieces.flatMap { splitAround(it, cut) } }
}

/** What is left of [piece] once [cut] is removed from it: zero, one, or two smaller pieces. */
private fun splitAround(piece: Interval, cut: Interval): List<Interval> {
    val before = if (piece.start.isBefore(cut.start)) Interval(piece.start, minOf(piece.end, cut.start)) else null
    val after = if (piece.end.isAfter(cut.end)) Interval(maxOf(piece.start, cut.end), piece.end) else null
    return listOfNotNull(before, after).filter { it.end.isAfter(it.start) }
}
