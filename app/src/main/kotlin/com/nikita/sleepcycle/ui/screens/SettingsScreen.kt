package com.nikita.sleepcycle.ui.screens

// File purpose: the Settings menu (X1) - a short top-level list: a navigation row per destination (Setup,
// Debug), the connection test's own button, and Done. Every status line, checklist, warning and count that
// used to live on Setup's one long scroll moved down into the screen its row opens; this screen only routes
// and runs the one test. Replaces Setup as the destination of the Before-bed gear - see MainActivity.kt and
// NightViewModel.openSettings.

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.BuildConfig
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.BackArrowButton
import com.nikita.sleepcycle.ui.components.PrimaryActionButton
import com.nikita.sleepcycle.ui.components.ScreenContainer
import com.nikita.sleepcycle.ui.components.SettingsMenuRow
import com.nikita.sleepcycle.ui.screens.setup.ConnectionTestSection
import com.nikita.sleepcycle.ui.state.SettingsMenuEntry
import com.nikita.sleepcycle.ui.state.SetupUiState
import com.nikita.sleepcycle.ui.state.settingsMenuEntries

/**
 * The Settings menu. [isDebugBuild] defaults to the real [BuildConfig.DEBUG] flag and is a parameter only so a
 * preview/test can force either list; [settingsMenuEntries] (X4) is what actually decides whether Debug shows.
 * [onBack] is used for both the back arrow and the "Done" button - X1 makes Done the primary action at the
 * bottom, returning to the same place the back arrow does.
 *
 * W12 (owner request): the connection test runs from here directly, rather than behind a row of its own. It
 * keeps the unchanged [ConnectionTestSection], so a test run from Settings behaves exactly as it did when it
 * lived on Setup, and it sits below the navigation rows because it is an action, not a destination.
 */
@Composable
fun SettingsScreen(
    state: SetupUiState,
    onOpenSetup: () -> Unit,
    onRunConnectionTest: () -> Unit,
    onOpenDebug: () -> Unit,
    onBack: () -> Unit,
    isDebugBuild: Boolean = BuildConfig.DEBUG,
) {
    ScreenContainer(scrollable = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackArrowButton(
                contentDescription = stringResource(R.string.content_description_back),
                onClick = onBack,
            )
            Text(text = stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
        }

        settingsMenuEntries(isDebugBuild).forEach { entry ->
            SettingsMenuRow(
                text = stringResource(settingsMenuEntryLabelRes(entry)),
                onClick = settingsMenuEntryAction(entry, onOpenSetup, onOpenDebug),
            )
        }

        ConnectionTestSection(state = state, onRunConnectionTest = onRunConnectionTest)

        PrimaryActionButton(text = stringResource(R.string.action_done), onClick = onBack)
    }
}

/** Row label per entry - reuses the same strings Setup and Debug already show as their own screen titles (X6), rather than adding near-duplicates. */
private fun settingsMenuEntryLabelRes(entry: SettingsMenuEntry): Int = when (entry) {
    SettingsMenuEntry.SETUP -> R.string.setup_title
    SettingsMenuEntry.DEBUG -> R.string.setup_debug_row_label
}

private fun settingsMenuEntryAction(
    entry: SettingsMenuEntry,
    onOpenSetup: () -> Unit,
    onOpenDebug: () -> Unit,
): () -> Unit = when (entry) {
    SettingsMenuEntry.SETUP -> onOpenSetup
    SettingsMenuEntry.DEBUG -> onOpenDebug
}
