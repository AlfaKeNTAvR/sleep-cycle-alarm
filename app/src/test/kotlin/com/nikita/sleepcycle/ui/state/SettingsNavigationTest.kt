package com.nikita.sleepcycle.ui.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SettingsMenuEntriesTest {
    @Test
    fun `a release build offers Setup and nothing else`() {
        assertEquals(listOf(SettingsMenuEntry.SETUP), settingsMenuEntries(isDebugBuild = false))
    }

    @Test
    fun `a debug build adds Debug after Setup`() {
        assertEquals(listOf(SettingsMenuEntry.SETUP, SettingsMenuEntry.DEBUG), settingsMenuEntries(isDebugBuild = true))
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

class CloseToNightOrSettingsTest {
    @Test
    fun `lands on Settings with no night running - the Settings row door`() {
        assertEquals(Screen.Settings, closeToNightOrSettings(nightActive = false))
    }

    @Test
    fun `lands on Night instead when one is running - the night screen's own shortcut door`() {
        assertEquals(Screen.Night, closeToNightOrSettings(nightActive = true))
    }
}
