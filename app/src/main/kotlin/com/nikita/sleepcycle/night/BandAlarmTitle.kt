package com.nikita.sleepcycle.night

// File purpose: picks the alternate band alarm title so the alarm can be moved without a gap (decision 11).

const val BAND_ALARM_TITLE_A = "SCA-A"
const val BAND_ALARM_TITLE_B = "SCA-B"

/** Returns the other of the two alternating titles, so the new alarm can be set before the old one is dismissed. */
fun nextBandAlarmTitle(currentTitle: String?): String =
    if (currentTitle == BAND_ALARM_TITLE_A) BAND_ALARM_TITLE_B else BAND_ALARM_TITLE_A
