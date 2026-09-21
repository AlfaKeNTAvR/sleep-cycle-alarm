package com.nikita.sleepcycle.ui.state

// File purpose: which top-level screen is showing. A simple sealed state; no navigation library needed.

/** One of the app's top-level screens. */
sealed interface Screen {
    /** X1: the short menu the Before-bed gear opens now - Setup and Debug as plain navigation rows plus the connection test's own button, no status text. */
    data object Settings : Screen
    data object Setup : Screen
    data object BeforeBed : Screen
    data object Night : Screen
    data object Logs : Screen
    /** One saved night reopened from the Logs list; which night it is lives in NightViewModel.pastNight. */
    data object PastNight : Screen
    /** Debug/simulation screen, reached from a "Debug" row on Settings (X4), or directly from the night screen's own shortcut - present only in a debug build (see DebugOptions.kt). */
    data object Debug : Screen
}
