package com.nikita.sleepcycle.ui.screens

// File purpose: the Debug screen - reached from the Debug row in Settings, present only in a debug build (see
// DebugOptions.kt). Owner spec, 2026-10-02: laid out like Settings (titled sections, no descriptions) and cut
// to the two things still used from here - the simulated band data switch and the alarm screen test. The
// speed chips and the Asleep switch live on the Night screen (DebugQuickControls.kt); setting the simulated
// time, resetting it and clearing the simulated sleep are gone: a night's end resets the clock and empties
// the simulated sleep on its own (DebugSettingsStore.kt's resetDebugOptionsAndClock).

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.BackArrowButton
import com.nikita.sleepcycle.ui.components.ScreenContainer
import com.nikita.sleepcycle.ui.components.SecondaryActionButton
import com.nikita.sleepcycle.ui.components.SettingsSection
import com.nikita.sleepcycle.ui.components.SettingsSwitchRow
import com.nikita.sleepcycle.ui.state.DebugUiState

/** The Debug screen. Both controls are inert while a night is running. */
@Composable
fun DebugScreen(
    state: DebugUiState,
    onSimulatedBandDataChange: (Boolean) -> Unit,
    onRingTestAlarm: () -> Unit,
    onBack: () -> Unit,
) {
    ScreenContainer(scrollable = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackArrowButton(
                contentDescription = stringResource(R.string.content_description_back),
                onClick = onBack,
            )
            Text(text = stringResource(R.string.debug_title), style = MaterialTheme.typography.headlineMedium)
        }

        SettingsSection(stringResource(R.string.debug_quick_controls_title)) {
            SettingsSwitchRow(
                label = stringResource(R.string.debug_simulated_band_data_title),
                checked = state.simulatedBandData,
                onCheckedChange = onSimulatedBandDataChange,
                enabled = state.simulatedBandDataControlEnabled,
            )
        }

        // Owner request, 2026-10-02: the app's own outlined button, not a small row button.
        SecondaryActionButton(
            text = stringResource(R.string.debug_ring_test_alarm_button),
            onClick = onRingTestAlarm,
            enabled = state.canRingTestAlarm,
        )
    }
}
