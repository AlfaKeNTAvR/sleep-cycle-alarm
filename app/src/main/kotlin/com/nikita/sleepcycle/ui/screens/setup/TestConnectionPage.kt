package com.nikita.sleepcycle.ui.screens.setup

// File purpose: wizard page 8 - Test connection, the full status list and result, and "Done" once everything
// is green. This page is never skipped, regardless of how complete the checklist is (see SetupWizardPages.kt).

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.PrimaryActionButton
import com.nikita.sleepcycle.ui.state.SetupUiState

/** The connection test, the Debug entry point (debug builds only), and "Done" once the checklist is complete. */
@Composable
fun TestConnectionPage(state: SetupUiState, onRunConnectionTest: () -> Unit, onOpenDebug: () -> Unit, onDone: () -> Unit) {
    ConnectionTestSection(state = state, onRunConnectionTest = onRunConnectionTest)
    SetupDebugRow(onOpenDebug = onOpenDebug)
    if (state.allComplete) {
        PrimaryActionButton(text = stringResource(R.string.action_done), onClick = onDone)
    }
}
