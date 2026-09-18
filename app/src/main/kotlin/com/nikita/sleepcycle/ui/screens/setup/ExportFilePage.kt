package com.nikita.sleepcycle.ui.screens.setup

// File purpose: wizard page 5 - picking the file Gadgetbridge auto-exports its database to.

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.SecondaryActionButton
import com.nikita.sleepcycle.ui.components.SettingsCard
import com.nikita.sleepcycle.ui.state.SetupItemKind
import com.nikita.sleepcycle.ui.state.SetupUiState

/** The export file picker, plus its current status and a reminder of which file to pick. */
@Composable
fun ExportFilePage(state: SetupUiState, onPickExportFile: () -> Unit) {
    SettingsCard {
        SetupItemStatusRow(state.items.first { it.kind == SetupItemKind.EXPORT_FILE })
        SecondaryActionButton(text = stringResource(R.string.setup_pick_export_file), onClick = onPickExportFile)
        Text(text = stringResource(R.string.setup_pick_export_file_helper), style = MaterialTheme.typography.bodySmall)
    }
}
