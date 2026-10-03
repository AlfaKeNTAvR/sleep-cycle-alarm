package com.nikita.sleepcycle.ui.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SettingsGroupsTest {
    @Test
    fun `a release build has no Debug section`() {
        assertEquals(
            listOf(SettingsGroup.AFTER_ALARM, SettingsGroup.BEDTIME_AUDIO, SettingsGroup.SLEEP_RATING, SettingsGroup.MORE),
            settingsGroups(isDebugBuild = false),
        )
    }

    @Test
    fun `a debug build adds the Debug section just before More, which stays last`() {
        // Owner request, 2026-10-02: Debug is its own section, not a row under More; More is the last section.
        assertEquals(
            listOf(SettingsGroup.AFTER_ALARM, SettingsGroup.BEDTIME_AUDIO, SettingsGroup.SLEEP_RATING, SettingsGroup.DEBUG, SettingsGroup.MORE),
            settingsGroups(isDebugBuild = true),
        )
    }
}

class CloseToNightOrBeforeBedTest {
    @Test
    fun `lands on Before bed with no night running`() {
        assertEquals(Screen.BeforeBed, closeToNightOrBeforeBed(nightActive = false))
    }

    @Test
    fun `lands on Night instead when one is running behind the screen being closed`() {
        assertEquals(Screen.Night, closeToNightOrBeforeBed(nightActive = true))
    }
}
