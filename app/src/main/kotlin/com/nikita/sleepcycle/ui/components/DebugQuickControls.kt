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
//
// Owner spec, 2026-10-02: the chips are 1x, 60x and Auto (AutoSimulationSpeed.kt), the Auto chip shows the
// speed Auto is running at, and the card takes the Settings screen's look.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.night.SpeedChoice
import com.nikita.sleepcycle.ui.state.DebugUiState
import com.nikita.sleepcycle.ui.theme.ChipGap

private val ChipRowVerticalPadding = 12.dp

/**
 * The speed chips and the Asleep switch, in one Settings-style section, or nothing at all when [state] says
 * simulated band data is off (see this file's own header).
 */
@Composable
fun DebugQuickControls(
    state: DebugUiState,
    onSpeedChoice: (SpeedChoice) -> Unit,
    onSetSimulatedAsleep: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.simulatedBandData) return
    SettingsSection(stringResource(R.string.debug_quick_controls_title), modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = ChipRowVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(ChipGap),
        ) {
            state.speedChoices.forEach { choice ->
                val label = speedChoiceLabel(choice, state.autoRunningSpeed)
                SleepLengthChip(
                    label = label,
                    selected = choice == state.selectedSpeed,
                    available = state.simulatedTimeControlEnabled,
                    onClick = { onSpeedChoice(choice) },
                    contentDescription = label,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        CardDivider()
        SettingsSwitchRow(
            label = stringResource(R.string.debug_asleep_toggle_title),
            checked = state.simulatedAsleep,
            onCheckedChange = onSetSimulatedAsleep,
            enabled = state.sleepControlEnabled,
        )
    }
}

/** "1x", "60x", "Auto" - and under Auto, the speed it is running at on a second line under "Auto". */
@Composable
private fun speedChoiceLabel(choice: SpeedChoice, autoRunningSpeed: Int?): String {
    val fixedSpeed = choice.fixedSpeed
    return when {
        fixedSpeed != null -> stringResource(R.string.debug_speed_multiplier_label, fixedSpeed)
        autoRunningSpeed != null -> stringResource(R.string.debug_speed_auto_running, stringResource(R.string.debug_speed_multiplier_label, autoRunningSpeed))
        else -> stringResource(R.string.debug_speed_auto)
    }
}
