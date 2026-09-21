package com.nikita.sleepcycle.ui.state

// File purpose: pure decision logic for the Settings menu rework (X1-X5) - which rows Settings offers for a
// debug versus release build, and which screen a back press out of Setup/Test connection/Debug/Settings itself
// lands on. Kept separate from NightViewModel (Android glue) so it is unit testable, the same split
// EndNightFlowState.kt uses for the end-night button/dialog flow.

/** X1: one row Settings can offer, in the order Settings renders them. */
enum class SettingsMenuEntry { SETUP, TEST_CONNECTION, DEBUG }

/**
 * X1/X4: every build gets Setup and Test connection; Debug is gated to a debug build - the same
 * `BuildConfig.DEBUG` gate `SetupDebugRow` used before Debug moved off Setup onto this menu, so a release
 * build loses nothing it did not already lack.
 */
fun settingsMenuEntries(isDebugBuild: Boolean): List<SettingsMenuEntry> =
    if (isDebugBuild) {
        listOf(SettingsMenuEntry.SETUP, SettingsMenuEntry.TEST_CONNECTION, SettingsMenuEntry.DEBUG)
    } else {
        listOf(SettingsMenuEntry.SETUP, SettingsMenuEntry.TEST_CONNECTION)
    }

/**
 * X5: where back (or Done) lands when leaving the Settings menu itself, exiting the Setup wizard, or leaving
 * Logs - Night if one is running behind the screen being closed, otherwise Before bed. All three reach this
 * only when no screen already answers "where did I come from" more specifically than that (Setup's checklist
 * mode and Test connection always return to Settings instead - see [Screen.Settings] itself as their target).
 */
fun closeToNightOrBeforeBed(nightActive: Boolean): Screen = if (nightActive) Screen.Night else Screen.BeforeBed

/**
 * X5: where back from Debug lands. Debug has two doors in: the Settings row, and the night screen's own
 * shortcut (`SlidersButton` in NightScreen.kt) so the simulator stays reachable mid-night in one tap. Back must
 * return to whichever one was used - Night while a night is actually running, Settings otherwise - or the
 * owner opening Debug from Night would be bounced to Settings and then, on the next Done, to Before bed, while
 * a night keeps running unseen behind it.
 */
fun closeToNightOrSettings(nightActive: Boolean): Screen = if (nightActive) Screen.Night else Screen.Settings
