package com.nikita.sleepcycle.night

// File purpose: the one fact night-20260920-0104.jsonl was missing entirely - what timezone its UTC
// timestamps were even IN. Every instant in the log is UTC already (java.time.Instant has no other option);
// what was missing was the zone id and offset in force at the start of the night, forcing a reader to guess
// it by comparing the log's first line against the filename's local time. Pure so it is directly
// unit-testable. NOT wired in: night_start is logged inside NightController.kt's startNight, which this
// task's file boundary forbids editing. See this file's integration note below.
//
// INTEGRATION (for NightController.kt, not done here):
//   startNight already computes `val zone = ZoneId.systemDefault()` (NightController.kt) before building the
//   night_start fields map. Merge this file's fields into that map, e.g.:
//
//     mapOf(
//         "pickedCycles" to settings.pickedCycles.toString(),
//         "deadline" to (settings.deadline?.toString() ?: "none"),
//         "simulatedBandData" to debugOptions.simulatedBandData.toString(),
//         "fastNight" to debugOptions.fastNight.toString()
//     ) + nightStartTimezoneFields(now, zone)
//
//   One call, same `now` and `zone` already in scope at that call site.

import java.time.Instant
import java.time.ZoneId

/**
 * The zone id (e.g. "America/New_York") and its UTC offset (e.g. "-04:00") in force at [at] - the offset is
 * read at [at], not at read time, so a log written before a DST change still records the offset that actually
 * applied that night rather than whatever offset the same zone id happens to carry when someone reads it back
 * later.
 */
fun nightStartTimezoneFields(at: Instant, zone: ZoneId): Map<String, String> = mapOf(
    "zoneId" to zone.id,
    "utcOffset" to zone.rules.getOffset(at).id
)
