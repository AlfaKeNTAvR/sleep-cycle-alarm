package com.nikita.sleepcycle.engine

import java.time.Instant
import java.time.ZoneId

internal val testZone: ZoneId = ZoneId.of("Europe/Moscow")
internal fun instant(text: String): Instant = java.time.LocalDateTime.parse(text).atZone(testZone).toInstant()
internal fun segment(start: String, end: String, kind: SegmentKind) = SleepSegment(instant(start), instant(end), kind)
internal fun settings(deadline: String? = null, cycles: Int = 5, backup: Boolean = false) =
    NightSettings(deadline?.let(::instant), cycles, backup)
