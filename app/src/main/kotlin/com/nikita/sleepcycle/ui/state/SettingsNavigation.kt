package com.nikita.sleepcycle.ui.state

// File purpose: pure decision logic for the Settings menu rework (X1-X5) - which sections Settings shows for a
// debug versus release build, and which screen a back press out of Setup/Settings itself lands on. Kept
// separate from NightViewModel (Android glue) so it is unit testable, the same split EndNightFlowState.kt uses
// for the end-night button/dialog flow.

/** The Settings screen's titled sections, in the order it renders them (owner spec, 2026-10-02, option A). */
enum class SettingsGroup { AFTER_ALARM, BEDTIME_AUDIO, SLEEP_RATING, DEBUG, MORE }

/**
 * X4, owner request 2026-10-02: Debug is its own section, just before More (Simulated band data, Ring phone alarm), no longer
 * a row under More opening a Debug screen - and only in a debug build, the same `BuildConfig.DEBUG` gate the
 * old row had, so a release build loses nothing it did not already lack. More now holds Setup alone.
 *
 * W12 (owner request): Test connection is not a Settings row. It used to be a row opening a screen whose
 * only content was the test button, so the row was a detour around a single button.
 */
fun settingsGroups(isDebugBuild: Boolean): List<SettingsGroup> =
    SettingsGroup.entries.filter { it != SettingsGroup.DEBUG || isDebugBuild }

/**
 * X5: where back (or Done) lands when leaving the Settings menu itself, exiting the Setup wizard, or leaving
 * Logs - Night if one is running behind the screen being closed, otherwise Before bed. All three reach this
 * only when no screen already answers "where did I come from" more specifically than that (Setup's checklist
 * mode always returns to Settings instead - see [Screen.Settings] itself as its target).
 */
fun closeToNightOrBeforeBed(nightActive: Boolean): Screen = if (nightActive) Screen.Night else Screen.BeforeBed
