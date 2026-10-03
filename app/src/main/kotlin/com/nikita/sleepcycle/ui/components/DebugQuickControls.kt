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
// Owner spec, 2026-10-02: the chips are 1x, 60x and Auto (AutoSimulationSpeed.kt), and the Auto chip shows the
// speed Auto is running at. Option B of the design (owner's pick): the amber SIMULATED banner itself becomes
// the control - one amber-outlined block at the top holding the simulated clock, the speed chips and an
// Asleep toggle chip - so the bottom of the Night screen keeps only the real buttons (Nap, I'm up, End night)
// and the card no longer crowds them on the out-of-bed nudge screen.

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.night.SpeedChoice
import com.nikita.sleepcycle.ui.state.DebugUiState
import com.nikita.sleepcycle.ui.theme.AmberAccent
import com.nikita.sleepcycle.ui.theme.CardBorderWidth
import com.nikita.sleepcycle.ui.theme.CardCornerRadius
import com.nikita.sleepcycle.ui.theme.ChipGap

private val BlockHorizontalPadding = 12.dp
private val BlockVerticalPadding = 10.dp
private val BlockRowGap = 10.dp
private val TitleLetterSpacing = 1.sp
/** The Asleep chip carries a longer word than "1x", so it gets a little more of the row. */
private const val ASLEEP_CHIP_WEIGHT = 1.4f

/**
 * The simulation control block (option B), in place of the plain SIMULATED banner, or nothing at all when
 * [state] says simulated band data is off (see this file's own header). [simulatedTimeValue] is the live
 * simulated clock ("03:15"), null while the clock runs at real time with nothing to show.
 */
@Composable
fun DebugQuickControls(
    state: DebugUiState,
    simulatedTimeValue: String?,
    onSpeedChoice: (SpeedChoice) -> Unit,
    onSetSimulatedAsleep: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.simulatedBandData) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(CardBorderWidth, AmberAccent, RoundedCornerShape(CardCornerRadius))
            .padding(horizontal = BlockHorizontalPadding, vertical = BlockVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(BlockRowGap),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.debug_switch_simulated_sleep_data),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = TitleLetterSpacing,
                color = AmberAccent,
            )
            if (simulatedTimeValue != null) {
                Text(text = simulatedTimeValue, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AmberAccent)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ChipGap)) {
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
            val asleepLabel = stringResource(R.string.debug_asleep_toggle_title)
            SleepLengthChip(
                label = asleepLabel,
                selected = state.simulatedAsleep,
                available = state.sleepControlEnabled,
                onClick = { onSetSimulatedAsleep(!state.simulatedAsleep) },
                contentDescription = asleepLabel,
                modifier = Modifier.weight(ASLEEP_CHIP_WEIGHT),
            )
        }
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
