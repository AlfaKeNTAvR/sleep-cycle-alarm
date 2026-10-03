package com.nikita.sleepcycle.ui.state

// File purpose: which top-level screen is showing. A simple sealed state; no navigation library needed.

/** One of the app's top-level screens. */
sealed interface Screen {
    /** The Before-bed gear's screen: the owner's settings, Setup, and in a debug build the Debug section (owner spec, 2026-10-02). */
    data object Settings : Screen
    data object Setup : Screen
    data object BeforeBed : Screen
    data object Night : Screen
    data object Logs : Screen
    /** One saved night reopened from the Logs list; which night it is lives in NightViewModel.pastNight. */
    data object PastNight : Screen
}
