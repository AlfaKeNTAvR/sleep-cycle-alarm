package com.nikita.sleepcycle.ui.state

/**
 * One setup requirement. Shared by the Setup checklist (one row per kind) and the Before-bed screen's
 * "Start night" gating (the first incomplete kind becomes the one-line blocked reason), so labels for both
 * live in one place (`strings.xml`, keyed by kind) and can never drift apart.
 */
enum class SetupItemKind {
    GADGETBRIDGE_INSTALLED,
    BAND_ADDRESS,
    EXPORT_FILE,
    NOTIFICATIONS,
    FULL_SCREEN_INTENT,
    BATTERY_OPTIMIZATION,
    EXACT_ALARMS,
}
