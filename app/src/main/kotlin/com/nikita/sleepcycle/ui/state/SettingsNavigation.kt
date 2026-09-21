package com.nikita.sleepcycle.ui.state

// File purpose: pure decision logic for the Settings menu rework (X1-X5) - which rows Settings offers for a
// debug versus release build, and which screen a back press out of Setup/Debug/Settings itself lands on. Kept
// separate from NightViewModel (Android glue) so it is unit testable, the same split EndNightFlowState.kt uses
// for the end-night button/dialog flow.

/** X1: one navigation row Settings can offer, in the order Settings renders them. */
enum class SettingsMenuEntry { SETUP, DEBUG }

/**
 * X1/X4: every build gets Setup; Debug is gated to a debug build - the same `BuildConfig.DEBUG` gate
 * `SetupDebugRow` used before Debug moved off Setup onto this menu, so a release build loses nothing it did
 * not already lack.
 *
 * W12 (owner request): Test connection is no longer one of these. It used to be a row opening a screen whose
 * only content was the test button, so the row was a detour around a single button; Settings now carries that
 * button itself and there is no Test connection screen at all.
 */
fun settingsMenuEntries(isDebugBuild: Boolean): List<SettingsMenuEntry> =
    if (isDebugBuild) listOf(SettingsMenuEntry.SETUP, SettingsMenuEntry.DEBUG) else listOf(SettingsMenuEntry.SETUP)

/**
 * X5: where back (or Done) lands when leaving the Settings menu itself, exiting the Setup wizard, or leaving
 * Logs - Night if one is running behind the screen being closed, otherwise Before bed. All three reach this
 * only when no screen already answers "where did I come from" more specifically than that (Setup's checklist
 * mode always returns to Settings instead - see [Screen.Settings] itself as its target).
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
