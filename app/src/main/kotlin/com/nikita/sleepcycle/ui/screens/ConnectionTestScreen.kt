package com.nikita.sleepcycle.ui.screens

// File purpose: the "Test connection" screen (X3) - a relocation, not a rewrite. It wraps the existing
// ConnectionTestSection composable (ui/screens/setup/ConnectionTestSection.kt) in the same back-arrow-plus-
// title chrome every other screen uses; the section itself, and the state it reads, are untouched, so a
// connection test run from here behaves exactly as it did inside Setup before this rework.

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.BackArrowButton
import com.nikita.sleepcycle.ui.components.ScreenContainer
import com.nikita.sleepcycle.ui.screens.setup.ConnectionTestSection
import com.nikita.sleepcycle.ui.state.SetupUiState

/** The "Test connection" screen: back arrow to Settings, a title, and the unchanged [ConnectionTestSection]. */
@Composable
fun ConnectionTestScreen(state: SetupUiState, onRunConnectionTest: () -> Unit, onBack: () -> Unit) {
    ScreenContainer(scrollable = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackArrowButton(
                contentDescription = stringResource(R.string.content_description_back),
                onClick = onBack,
            )
            Text(text = stringResource(R.string.setup_test_connection), style = MaterialTheme.typography.headlineMedium)
        }
        ConnectionTestSection(state = state, onRunConnectionTest = onRunConnectionTest)
    }
}
