package com.nikita.sleepcycle.night

// File purpose: the pure decision behind pausing whatever media is playing once the band says the owner fell
// asleep - see MediaPauseOnSleep.shouldPauseMediaOnSleep. The pause itself is a media-key press through
// AudioManager and is checked by hand on the phone.

import com.nikita.sleepcycle.engine.SleepState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MediaPauseOnSleepTest {
    @Test fun `falling asleep for the first time tonight pauses media`() {
        assertTrue(shouldPauseMediaOnSleep(SleepState.NOT_YET_ASLEEP, SleepState.ASLEEP, morningAlarmRang = false))
    }

    @Test fun `staying asleep does not pause again`() {
        assertFalse(shouldPauseMediaOnSleep(SleepState.ASLEEP, SleepState.ASLEEP, morningAlarmRang = false))
    }

    @Test fun `falling back asleep after a mid-night awakening pauses again`() {
        assertTrue(shouldPauseMediaOnSleep(SleepState.AWAKE, SleepState.ASLEEP, morningAlarmRang = false))
    }

    @Test fun `still awake does not pause`() {
        assertFalse(shouldPauseMediaOnSleep(SleepState.NOT_YET_ASLEEP, SleepState.NOT_YET_ASLEEP, morningAlarmRang = false))
        assertFalse(shouldPauseMediaOnSleep(SleepState.ASLEEP, SleepState.AWAKE, morningAlarmRang = false))
    }

    @Test fun `once the morning alarm has rung, dozing off never pauses media`() {
        assertFalse(shouldPauseMediaOnSleep(SleepState.AWAKE, SleepState.ASLEEP, morningAlarmRang = true))
    }
}
