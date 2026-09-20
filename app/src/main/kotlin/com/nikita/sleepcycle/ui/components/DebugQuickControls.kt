package com.nikita.sleepcycle.ui.components

// File purpose: W7 (owner request, 2026-09-20) - the two controls that actually drive a simulated night, the
// clock speed and the Asleep toggle, placed on the screen the owner is already looking at. Everything else
// about a simulation is set up once and then left alone: which data source, where the clock starts, clearing
// a leftover timeline. Those stay on the Debug screen. These two get used every few seconds while watching a
// night unfold, and walking to another screen and back for each one made the whole thing tedious.
//
// Deliberately shows nothing unless simulated band data is already on. The controls it holds both REQUIRE
// that switch (U1's own gate), so rendering them without it would put two permanently dead controls on the
// main screen of a real night. Turning the switch on stays a deliberate trip to the Debug screen, which is
// the right weight for "I am about to simulate rather than sleep".

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.state.DebugUiState
import com.nikita.sleepcycle.ui.theme.ChipGap

/**
 * The speed row and the Asleep toggle, in one card, or nothing at all when [state] says simulated band data
 * is off (see this file's own header). [onSpeedChange] and [onSetSimulatedAsleep] are the same actions the
 * Debug screen's own copies of these controls call, so the two can never drift apart.
 */
@Composable
fun DebugQuickControls(
    state: DebugUiState,
    onSpeedChange: (Int) -> Unit,
    onSetSimulatedAsleep: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.simulatedBandData) return
    SettingsCard(modifier = modifier) {
        Text(text = stringResource(R.string.debug_quick_controls_title), style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ChipGap)) {
            state.availableSpeeds.forEach { speed ->
                val label = stringResource(R.string.debug_speed_multiplier_label, speed)
                SleepLengthChip(
                    label = label,
                    selected = speed == state.speed,
                    available = state.simulatedTimeControlEnabled,
                    onClick = { onSpeedChange(speed) },
                    contentDescription = label,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        ToggleRow(
            title = stringResource(R.string.debug_asleep_toggle_title),
            checked = state.simulatedAsleep,
            onCheckedChange = onSetSimulatedAsleep,
            contentDescription = stringResource(R.string.debug_asleep_toggle_title),
            enabled = state.sleepControlEnabled,
        )
    }
}
