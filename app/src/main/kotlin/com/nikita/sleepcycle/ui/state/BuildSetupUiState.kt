package com.nikita.sleepcycle.ui.state

// File purpose: pure derivation of the Setup checklist screen's state from settings and permission statuses.

import com.nikita.sleepcycle.night.AppSettings

/** Builds the Setup checklist screen's state: one row per requirement, in [SETUP_ITEM_ORDER]. */
fun buildSetupUiState(
    appSettings: AppSettings,
    permissionStatus: PermissionStatus,
    gadgetbridgeInstalled: Boolean,
    connectionTest: ConnectionTestState,
): SetupUiState {
    val deviceMacSet = !appSettings.deviceMac.isNullOrBlank()
    val exportFileSet = appSettings.exportUri != null
    val items = SETUP_ITEM_ORDER.map { kind ->
        SetupChecklistItem(kind, isSetupItemComplete(kind, appSettings, permissionStatus, gadgetbridgeInstalled))
    }
    return SetupUiState(
        deviceMacText = appSettings.deviceMac.orEmpty(),
        items = items,
        allComplete = items.all { it.complete },
        connectionTest = connectionTest,
        canRunConnectionTest = gadgetbridgeInstalled && deviceMacSet && exportFileSet && connectionTest != ConnectionTestState.Running,
    )
}
