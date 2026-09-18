package com.nikita.sleepcycle.ui.screens.setup

// File purpose: wizard page 6 - notifications, full-screen alarm, battery optimisation, and exact alarms, each
// with its current state and a one-button fix where the app can offer one. EXACT_ALARMS has no button: the
// manifest declares USE_EXACT_ALARM (a normal, always-granted permission for alarm-clock apps), so that row is
// always complete and there is nothing to send the owner to Settings for (D6 in the checklist screen).

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.SecondaryActionButton
import com.nikita.sleepcycle.ui.components.SettingsCard
import com.nikita.sleepcycle.ui.state.SetupItemKind
import com.nikita.sleepcycle.ui.state.SetupUiState

private val PHONE_PERMISSION_KINDS = listOf(
    SetupItemKind.NOTIFICATIONS,
    SetupItemKind.FULL_SCREEN_INTENT,
    SetupItemKind.BATTERY_OPTIMIZATION,
    SetupItemKind.EXACT_ALARMS,
)

/** The four phone-side setup items, each with its current status; three carry a one-tap "Allow" fix. */
@Composable
fun PhonePermissionsPage(
    state: SetupUiState,
    onRequestNotificationPermission: () -> Unit,
    onOpenFullScreenIntentSettings: () -> Unit,
    onOpenBatteryOptimizationSettings: () -> Unit,
) {
    SettingsCard {
        PHONE_PERMISSION_KINDS.forEach { kind ->
            SetupItemStatusRow(state.items.first { it.kind == kind })
        }
    }
    if (!isItemComplete(state, SetupItemKind.NOTIFICATIONS)) {
        SecondaryActionButton(
            text = stringResource(R.string.setup_grant) + ": " + stringResource(R.string.setup_item_notifications_label),
            onClick = onRequestNotificationPermission,
        )
    }
    if (!isItemComplete(state, SetupItemKind.FULL_SCREEN_INTENT)) {
        SecondaryActionButton(
            text = stringResource(R.string.setup_grant) + ": " + stringResource(R.string.setup_item_full_screen_intent_label),
            onClick = onOpenFullScreenIntentSettings,
        )
    }
    if (!isItemComplete(state, SetupItemKind.BATTERY_OPTIMIZATION)) {
        SecondaryActionButton(
            text = stringResource(R.string.setup_grant) + ": " + stringResource(R.string.setup_item_battery_optimization_label),
            onClick = onOpenBatteryOptimizationSettings,
        )
    }
}

private fun isItemComplete(state: SetupUiState, kind: SetupItemKind): Boolean =
    state.items.any { it.kind == kind && it.complete }
