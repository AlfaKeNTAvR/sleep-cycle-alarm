package com.nikita.sleepcycle.ui.screens

// File purpose: the Setup checklist screen - Gadgetbridge/band/export requirements, system permissions with
// deep links to their settings screens, the Gadgetbridge settings list, and the "Test connection" button.

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nikita.sleepcycle.BuildConfig
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.night.SetupCheckLine
import com.nikita.sleepcycle.night.SetupCheckLineSeverity
import com.nikita.sleepcycle.night.SetupCheckReport
import com.nikita.sleepcycle.ui.components.IconGlyphButton
import com.nikita.sleepcycle.ui.components.PrimaryActionButton
import com.nikita.sleepcycle.ui.components.ScreenContainer
import com.nikita.sleepcycle.ui.components.SecondaryActionButton
import com.nikita.sleepcycle.ui.components.SettingsCard
import com.nikita.sleepcycle.ui.permissions.batteryOptimizationSettingsIntent
import com.nikita.sleepcycle.ui.permissions.fullScreenIntentSettingsIntent
import com.nikita.sleepcycle.ui.state.ConnectionTestState
import com.nikita.sleepcycle.ui.state.SetupChecklistItem
import com.nikita.sleepcycle.ui.state.SetupItemKind
import com.nikita.sleepcycle.ui.state.SetupUiState
import com.nikita.sleepcycle.ui.theme.AmberAccent
import com.nikita.sleepcycle.ui.theme.ConnectedDot
import com.nikita.sleepcycle.ui.theme.ErrorRed
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted

/** The Setup checklist screen. */
@Composable
fun SetupScreen(
    state: SetupUiState,
    onDeviceMacChange: (String) -> Unit,
    onExportUriPicked: (Uri) -> Unit,
    onRunConnectionTest: () -> Unit,
    onBack: () -> Unit,
    onOpenDebug: () -> Unit = {},
) {
    val context = LocalContext.current
    val exportFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onExportUriPicked(uri)
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    ScreenContainer(scrollable = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconGlyphButton(
                glyph = stringResource(R.string.glyph_back),
                contentDescription = stringResource(R.string.content_description_back),
                onClick = onBack,
            )
            Text(text = stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium)
        }
        Text(text = stringResource(R.string.setup_subtitle), style = MaterialTheme.typography.bodyMedium)

        SettingsCard {
            state.items.forEach { item -> SetupItemRow(item) }
        }

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

        SettingsCard {
            SecondaryActionButton(
                text = if (state.connectionTest == ConnectionTestState.Running) {
                    stringResource(R.string.setup_test_connection_running)
                } else {
                    stringResource(R.string.setup_test_connection)
                },
                onClick = onRunConnectionTest,
                enabled = state.canRunConnectionTest,
            )
            if (state.connectionTest is ConnectionTestState.Running) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
            if (state.connectionTest is ConnectionTestState.Done) {
                SetupCheckReportView(state.connectionTest.report)
            }
        }

        // BuildConfig.DEBUG is a compile-time constant, so this whole row (and the branch that reads it) is
        // stripped from a release build - the debug entry point is genuinely absent, not merely hidden.
        if (BuildConfig.DEBUG) {
            SettingsCard {
                SecondaryActionButton(text = stringResource(R.string.setup_debug_row_label), onClick = onOpenDebug)
            }
        }

        if (state.allComplete) {
            PrimaryActionButton(text = stringResource(R.string.action_done), onClick = onBack)
        }
    }
}

/** Renders a finished setup check: a clear pass/fail headline, then one row per report line, colored by how urgently it needs attention - amber and first for anything blocking, muted for a non-blocking warning. */
@Composable
private fun SetupCheckReportView(report: SetupCheckReport) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (report.isReady) stringResource(R.string.setup_check_passed_headline) else stringResource(R.string.setup_check_failed_headline),
            style = MaterialTheme.typography.titleMedium,
            color = if (report.isReady) ConnectedDot else ErrorRed,
        )
        report.lines.sortedBy { lineSortOrder(it.severity) }.forEach { line ->
            Text(text = line.text, style = MaterialTheme.typography.bodyMedium, color = lineColor(line.severity))
        }
    }
}

/** Action-needed lines surface first (they are what the owner must fix before bed), then ordinary lines, then non-blocking warnings. */
private fun lineSortOrder(severity: SetupCheckLineSeverity): Int = when (severity) {
    SetupCheckLineSeverity.ACTION_NEEDED -> 0
    SetupCheckLineSeverity.INFO -> 1
    SetupCheckLineSeverity.WARNING -> 2
}

@Composable
private fun lineColor(severity: SetupCheckLineSeverity): Color = when (severity) {
    SetupCheckLineSeverity.ACTION_NEEDED -> AmberAccent
    SetupCheckLineSeverity.WARNING -> NightOnSurfaceMuted
    SetupCheckLineSeverity.INFO -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun SetupItemRow(item: SetupChecklistItem) {
    val (labelRes, detailRes) = setupItemTextResources(item.kind)
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(10.dp)
                .background(if (item.complete) ConnectedDot else ErrorRed, CircleShape),
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(10.dp))
        Column {
            Text(text = stringResource(labelRes), style = MaterialTheme.typography.titleMedium)
            Text(text = stringResource(detailRes), style = MaterialTheme.typography.bodySmall)
        }
    }
}
