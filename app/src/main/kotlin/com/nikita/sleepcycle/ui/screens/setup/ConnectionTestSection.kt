package com.nikita.sleepcycle.ui.screens.setup

// File purpose: the "Test connection" button, spinner, and finished report - shared by the one-page checklist
// and the wizard's final page, so a connection test looks and behaves identically wherever it is run from.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nikita.sleepcycle.BuildConfig
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.night.SetupCheckLineSeverity
import com.nikita.sleepcycle.night.SetupCheckReport
import com.nikita.sleepcycle.ui.components.SecondaryActionButton
import com.nikita.sleepcycle.ui.components.SettingsCard
import com.nikita.sleepcycle.ui.state.ConnectionTestState
import com.nikita.sleepcycle.ui.state.SetupUiState
import com.nikita.sleepcycle.ui.theme.AmberAccent
import com.nikita.sleepcycle.ui.theme.ConnectedDot
import com.nikita.sleepcycle.ui.theme.ErrorRed
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted

/** The "Test connection" button, its running spinner, and the finished report once one is available. */
@Composable
fun ConnectionTestSection(state: SetupUiState, onRunConnectionTest: () -> Unit) {
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
}

/** The Debug-screen entry row (a debug build only - see [BuildConfig.DEBUG]). */
@Composable
fun SetupDebugRow(onOpenDebug: () -> Unit) {
    // BuildConfig.DEBUG is a compile-time constant, so this whole row (and the branch that reads it) is
    // stripped from a release build - the debug entry point is genuinely absent, not merely hidden.
    if (BuildConfig.DEBUG) {
        SettingsCard {
            SecondaryActionButton(text = stringResource(R.string.setup_debug_row_label), onClick = onOpenDebug)
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
