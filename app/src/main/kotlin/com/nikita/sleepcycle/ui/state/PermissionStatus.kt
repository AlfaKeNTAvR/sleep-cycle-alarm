package com.nikita.sleepcycle.ui.state

// File purpose: the runtime/system permissions the setup checklist tracks, as plain data so UiState derivation
// stays pure and testable. Reading the real statuses from Android APIs happens in ui/permissions/.

/** The system-level permissions and exemptions the setup checklist needs, all green before a night can start. */
data class PermissionStatus(
    val notificationsGranted: Boolean,
    val fullScreenIntentAllowed: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val exactAlarmsAllowed: Boolean,
) {
    companion object {
        /** A safe "nothing granted yet" default, used only until the real statuses are read once. */
        fun unknown(): PermissionStatus = PermissionStatus(
            notificationsGranted = false,
            fullScreenIntentAllowed = false,
            batteryOptimizationIgnored = false,
            exactAlarmsAllowed = false,
        )
    }
}
