package com.nikita.sleepcycle.ui.screens

// File purpose: the Setup screen. Renders either the one-page checklist (reachable from Settings' "Setup" row,
// for a later re-check) or the page-by-page wizard (what a fresh install opens into, and what "Run setup
// again" on the checklist re-enters). Both share the same SetupUiState and the same underlying actions - this
// is a presentation split only, see ui/screens/setup/ for the wizard's page model and page composables.
//
// X2: the checklist lost ConnectionTestSection and SetupDebugRow - Settings carries both now, the test as its
// own button and Debug as a row (see SettingsScreen.kt). The wizard's own last page (TestConnectionPage.kt) still
// shows both; it is explicitly out of scope for this rework and behaves exactly as before. That split is also
// why this screen now takes two distinct exit callbacks: [onExitWizard] (wizard mode, unchanged destination)
// and [onBack] (checklist mode, now returns to Settings instead of Before bed/Night - X5).

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.BackArrowButton
import com.nikita.sleepcycle.ui.components.PrimaryActionButton
import com.nikita.sleepcycle.ui.components.ScreenContainer
import com.nikita.sleepcycle.ui.components.SecondaryActionButton
import com.nikita.sleepcycle.ui.components.SettingsCard
import com.nikita.sleepcycle.ui.permissions.batteryOptimizationSettingsIntent
import com.nikita.sleepcycle.ui.permissions.fullScreenIntentSettingsIntent
import com.nikita.sleepcycle.ui.screens.setup.SetupItemStatusRow
import com.nikita.sleepcycle.ui.screens.setup.SetupWizardActions
import com.nikita.sleepcycle.ui.screens.setup.SetupWizardContent
import com.nikita.sleepcycle.ui.screens.setup.SetupWizardPage
import com.nikita.sleepcycle.ui.state.SetupItemKind
import com.nikita.sleepcycle.ui.state.SetupUiState

/**
 * The Setup screen. [wizardPage] null renders the one-page checklist; non-null renders that page of the
 * wizard. Every action below is shared verbatim between the two modes - this file changes only which of them
 * is offered on screen at once, never what any of them does.
 */
@Composable
fun SetupScreen(
    state: SetupUiState,
    wizardPage: SetupWizardPage?,
    onDeviceMacChange: (String) -> Unit,
    onExportUriPicked: (Uri) -> Unit,
    onRunConnectionTest: () -> Unit,
    onWizardNext: () -> Unit,
    onWizardBack: () -> Unit,
    onRunSetupAgain: () -> Unit,
    onExitWizard: () -> Unit,
    onBack: () -> Unit,
    onOpenDebug: () -> Unit = {},
) {
    val context = LocalContext.current
    val exportFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onExportUriPicked(uri)
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    if (wizardPage != null) {
        SetupWizardContent(
            state = state,
            page = wizardPage,
            actions = SetupWizardActions(
                onDeviceMacChange = onDeviceMacChange,
                onPickExportFile = { exportFileLauncher.launch(arrayOf("*/*")) },
                onRequestNotificationPermission = { notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
                onOpenFullScreenIntentSettings = { context.startActivity(fullScreenIntentSettingsIntent(context)) },
                onOpenBatteryOptimizationSettings = { context.startActivity(batteryOptimizationSettingsIntent(context)) },
                onRunConnectionTest = onRunConnectionTest,
                onOpenDebug = onOpenDebug,
                onExitWizard = onExitWizard,
                onPreviousPage = onWizardBack,
                onNextPage = onWizardNext,
            ),
        )
        return
    }

    ScreenContainer(scrollable = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackArrowButton(
                contentDescription = stringResource(R.string.content_description_back),
                onClick = onBack,
            )
            Text(text = stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium)
        }
        Text(text = stringResource(R.string.setup_subtitle), style = MaterialTheme.typography.bodyMedium)

        SettingsCard {
            state.items.forEach { item -> SetupItemStatusRow(item) }
        }

        SecondaryActionButton(text = stringResource(R.string.setup_run_wizard_again_button), onClick = onRunSetupAgain)

        SettingsCard {
            Text(text = stringResource(R.string.setup_device_mac_label), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.deviceMacText,
                onValueChange = onDeviceMacChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            SecondaryActionButton(
                text = stringResource(R.string.setup_pick_export_file),
                onClick = { exportFileLauncher.launch(arrayOf("*/*")) },
            )
            Text(text = stringResource(R.string.setup_pick_export_file_helper), style = MaterialTheme.typography.bodySmall)
            if (!state.items.any { it.kind == SetupItemKind.NOTIFICATIONS && it.complete }) {
                SecondaryActionButton(
                    text = stringResource(R.string.setup_grant) + ": " + stringResource(R.string.setup_item_notifications_label),
                    onClick = { notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
                )
            }
            if (!state.items.any { it.kind == SetupItemKind.FULL_SCREEN_INTENT && it.complete }) {
                SecondaryActionButton(
                    text = stringResource(R.string.setup_grant) + ": " + stringResource(R.string.setup_item_full_screen_intent_label),
                    onClick = { context.startActivity(fullScreenIntentSettingsIntent(context)) },
                )
            }
            if (!state.items.any { it.kind == SetupItemKind.BATTERY_OPTIMIZATION && it.complete }) {
                SecondaryActionButton(
                    text = stringResource(R.string.setup_grant) + ": " + stringResource(R.string.setup_item_battery_optimization_label),
                    onClick = { context.startActivity(batteryOptimizationSettingsIntent(context)) },
                )
            }
            // No "Grant" button for EXACT_ALARMS: the manifest declares USE_EXACT_ALARM (a normal, always-granted
            // permission for alarm-clock apps), so that checklist item is always complete and there is nothing
            // to send the owner to Settings for (D6 - the settings intent this used to open was unreachable).
        }

        SettingsCard {
            Text(text = stringResource(R.string.setup_gadgetbridge_settings_title), style = MaterialTheme.typography.titleMedium)
            stringArrayResource(R.array.setup_gadgetbridge_settings).forEach { line ->
                Text(text = "• $line", style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (state.allComplete) {
            PrimaryActionButton(text = stringResource(R.string.action_done), onClick = onBack)
        }
    }
}
