package com.nikita.sleepcycle.ui.state

// File purpose: which top-level screen is showing. A simple sealed state; no navigation library needed.

/** One of the app's top-level screens. */
sealed interface Screen {
    /** X1: the short menu the Before-bed gear opens now - Setup/Test connection/Debug as plain navigation rows, no status text. */
    data object Settings : Screen
    data object Setup : Screen
    /** X3: the "Test connection" row's own screen - the same [com.nikita.sleepcycle.ui.screens.setup.ConnectionTestSection] Setup used to embed, just given its own place to live. */
    data object ConnectionTest : Screen
    data object BeforeBed : Screen
    data object Night : Screen
    data object Logs : Screen
    /** One saved night reopened from the Logs list; which night it is lives in NightViewModel.pastNight. */
    data object PastNight : Screen
    /** Debug/simulation screen, reached from a "Debug" row on Settings (X4), or directly from the night screen's own shortcut - present only in a debug build (see DebugOptions.kt). */
    data object Debug : Screen
}
