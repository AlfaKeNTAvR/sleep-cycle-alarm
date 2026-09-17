package com.nikita.sleepcycle.ui.screens

// File purpose: maps a SetupItemKind to its string resources. Shared by the Setup checklist (label + detail)
// and the Before-bed screen's "Start night" blocked-reason line, so both read from the same copy.

import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.state.SetupItemKind

/** The checklist row's label and detail string resources for one setup item. */
fun setupItemTextResources(kind: SetupItemKind): Pair<Int, Int> = when (kind) {
    SetupItemKind.GADGETBRIDGE_INSTALLED -> R.string.setup_item_gadgetbridge_installed_label to R.string.setup_item_gadgetbridge_installed_detail
    SetupItemKind.BAND_ADDRESS -> R.string.setup_item_band_address_label to R.string.setup_item_band_address_detail
    SetupItemKind.EXPORT_FILE -> R.string.setup_item_export_file_label to R.string.setup_item_export_file_detail
    SetupItemKind.NOTIFICATIONS -> R.string.setup_item_notifications_label to R.string.setup_item_notifications_detail
    SetupItemKind.FULL_SCREEN_INTENT -> R.string.setup_item_full_screen_intent_label to R.string.setup_item_full_screen_intent_detail
    SetupItemKind.BATTERY_OPTIMIZATION -> R.string.setup_item_battery_optimization_label to R.string.setup_item_battery_optimization_detail
    SetupItemKind.EXACT_ALARMS -> R.string.setup_item_exact_alarms_label to R.string.setup_item_exact_alarms_detail
}

/** The one-line "Start night" blocked-reason string resource for the first incomplete setup item. */
fun setupBlockerReasonRes(kind: SetupItemKind): Int = when (kind) {
    SetupItemKind.GADGETBRIDGE_INSTALLED -> R.string.start_blocked_gadgetbridge
    SetupItemKind.BAND_ADDRESS -> R.string.start_blocked_band_address
    SetupItemKind.EXPORT_FILE -> R.string.start_blocked_export_file
    SetupItemKind.NOTIFICATIONS -> R.string.start_blocked_notifications
    SetupItemKind.FULL_SCREEN_INTENT -> R.string.start_blocked_full_screen_intent
    SetupItemKind.BATTERY_OPTIMIZATION -> R.string.start_blocked_battery_optimization
    SetupItemKind.EXACT_ALARMS -> R.string.start_blocked_exact_alarms
}
