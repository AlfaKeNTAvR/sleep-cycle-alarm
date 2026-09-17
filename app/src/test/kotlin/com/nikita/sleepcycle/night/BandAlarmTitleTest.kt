package com.nikita.sleepcycle.night

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BandAlarmTitleTest {
    @Test
    fun `no current title picks A`() {
        assertEquals(BAND_ALARM_TITLE_A, nextBandAlarmTitle(null))
    }

    @Test
    fun `A switches to B`() {
        assertEquals(BAND_ALARM_TITLE_B, nextBandAlarmTitle(BAND_ALARM_TITLE_A))
    }

    @Test
    fun `B switches to A`() {
        assertEquals(BAND_ALARM_TITLE_A, nextBandAlarmTitle(BAND_ALARM_TITLE_B))
    }

    @Test
    fun `an unrecognised title falls back to A`() {
        assertEquals(BAND_ALARM_TITLE_A, nextBandAlarmTitle("something else"))
    }
}
