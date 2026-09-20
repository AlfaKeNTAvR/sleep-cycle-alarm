package com.nikita.sleepcycle.ui.screens

// File purpose: the Logs screen - the night history. Every saved night, newest first: tap one to reopen its
// summary, or use its three-dot menu to share or delete it. The three-dot button was chosen over long-press
// and over swipe-to-delete because it is the only one of the three that can be seen without being guessed.

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.BackArrowButton
import com.nikita.sleepcycle.ui.components.ConfirmDialog
import com.nikita.sleepcycle.ui.components.DotsButton
import com.nikita.sleepcycle.ui.components.ScreenContainer
import com.nikita.sleepcycle.ui.components.SettingsCard
import com.nikita.sleepcycle.ui.state.LogsUiState
import com.nikita.sleepcycle.ui.state.NightLogSummary
import com.nikita.sleepcycle.ui.theme.AmberAccent
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted

private val LogTitleRowGap = 6.dp

/**
 * The Logs screen: every saved night log, newest first. Which row's menu is open, and which row is awaiting a
 * delete confirmation, are kept here rather than in UiState - both are momentary screen state that means
 * nothing once the screen is left, like the time picker's own open/closed flag (see TimePickerField.kt).
 */
@Composable
fun LogsScreen(
    state: LogsUiState,
    onOpenLog: (NightLogSummary) -> Unit,
    onShareLog: (NightLogSummary) -> Unit,
    onDeleteLog: (NightLogSummary) -> Unit,
    onBack: () -> Unit,
) {
    var openMenuLogName by remember { mutableStateOf<String?>(null) }
    var pendingDeleteLog by remember { mutableStateOf<NightLogSummary?>(null) }

    ScreenContainer(scrollable = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackArrowButton(
                contentDescription = stringResource(R.string.content_description_back),
                onClick = onBack,
            )
            Text(text = stringResource(R.string.logs_title), style = MaterialTheme.typography.headlineMedium)
        }
        if (state.logs.isEmpty()) {
            Text(text = stringResource(R.string.logs_empty), style = MaterialTheme.typography.bodyMedium, color = NightOnSurfaceMuted)
        }
        state.logs.forEach { log ->
            SettingsCard(modifier = Modifier.clickable { onOpenLog(log) }) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(LogTitleRowGap), verticalAlignment = Alignment.CenterVertically) {
                            Text(text = log.displayName, style = MaterialTheme.typography.titleMedium)
                            if (log.isSimulated) {
                                Text(
                                    text = stringResource(R.string.logs_simulated_tag),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AmberAccent,
                                )
                            }
                        }
                        Text(text = log.sizeLabel, style = MaterialTheme.typography.bodySmall, color = NightOnSurfaceMuted)
                    }
                    LogActionsMenu(
                        expanded = openMenuLogName == log.file.name,
                        onOpenMenu = { openMenuLogName = log.file.name },
                        onCloseMenu = { openMenuLogName = null },
                        onShare = { onShareLog(log) },
                        onDelete = { pendingDeleteLog = log },
                    )
                }
            }
        }
    }

    pendingDeleteLog?.let { log ->
        ConfirmDialog(
            title = stringResource(R.string.logs_confirm_delete_title),
            message = stringResource(R.string.logs_confirm_delete_message, log.displayName),
            confirmText = stringResource(R.string.logs_confirm_delete_confirm),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = {
                pendingDeleteLog = null
                onDeleteLog(log)
            },
            onDismiss = { pendingDeleteLog = null },
        )
    }
}

/** One row's three-dot button and the menu it opens. Colors are explicit rather than left to Material defaults - see TimePickerField.kt for why. */
@Composable
private fun LogActionsMenu(
    expanded: Boolean,
    onOpenMenu: () -> Unit,
    onCloseMenu: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Box {
        DotsButton(
            contentDescription = stringResource(R.string.content_description_log_menu),
            onClick = onOpenMenu,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onCloseMenu,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.logs_menu_share), color = MaterialTheme.colorScheme.onSurface) },
                onClick = {
                    onCloseMenu()
                    onShare()
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.logs_menu_delete), color = MaterialTheme.colorScheme.error) },
                onClick = {
                    onCloseMenu()
                    onDelete()
                },
            )
        }
    }
}
