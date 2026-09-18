package com.nikita.sleepcycle.night

// File purpose: the one band alarm title this app uses, for the whole night, every night. Decision 11's two
// alternating titles are gone (night 1, 2026-09-18): they existed to keep the band from ever being
// momentarily alarm-less, but on this hardware that risk does not exist - the band keeps whatever was LAST
// WRITTEN to a slot and a DISMISS never disarms it, so alternating titles only ever meant two slots holding
// two different armed times, and both of them rang. See BandAlarmSingleSlotMode.kt for the protocol.
//
// The value is still "SCA-A", the name the two-title era used for the first title: a slot the band is already
// carrying from an earlier night is then still recognised as ours, and tonight's first SET reclaims that same
// slot instead of leaving it armed alongside a new one.

const val BAND_ALARM_TITLE = "SCA-A"

/** The same one title as a set, for the band-table helpers that take "which titles are ours" (BandAlarmMapping.kt) and for the setup check. */
val OUR_BAND_ALARM_TITLES: Set<String> = setOf(BAND_ALARM_TITLE)
