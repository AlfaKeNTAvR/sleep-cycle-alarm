package com.nikita.sleepcycle.ui.screens

// File purpose: the Debug/simulation screen - reached from a "Debug" row at the bottom of Setup, present
// only in a debug build (see DebugOptions.kt). Two independent switches (simulated band data, and the clock -
// speed plus an optional jump), the simulator's "Asleep" toggle and timeline, and the phone-alarm daylight
// test button.
//
// T12: the speed selector is a proper segmented control now, reusing [SleepLengthChip] (the same chip the
// Before-bed screen's "Sleep up to" picker uses) rather than inventing a new widget family - amber-filled when
// selected, hatched-disabled when the whole row is unavailable (U1).

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.BackArrowButton
import com.nikita.sleepcycle.ui.components.MediumTimeText
import com.nikita.sleepcycle.ui.components.ScreenContainer
import com.nikita.sleepcycle.ui.components.SecondaryActionButton
import com.nikita.sleepcycle.ui.components.SettingsCard
import com.nikita.sleepcycle.ui.components.SleepLengthChip
import com.nikita.sleepcycle.ui.components.ToggleRow
import com.nikita.sleepcycle.ui.state.DebugUiState
import com.nikita.sleepcycle.ui.theme.ChipGap
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted
import java.time.LocalTime

/** The Debug/simulation screen. */
@Composable
fun DebugScreen(
    state: DebugUiState,
    onSimulatedBandDataChange: (Boolean) -> Unit,
    onSpeedChange: (Int) -> Unit,
    onSetSimulatedAsleep: (Boolean) -> Unit,
    onClearSimulatedSleep: () -> Unit,
    onApplyClockJump: (LocalTime) -> Unit,
    onResetToRealTime: () -> Unit,
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
        Text(text = stringResource(R.string.debug_subtitle), style = MaterialTheme.typography.bodyMedium)

        SettingsCard {
            ToggleRow(
                title = stringResource(R.string.debug_simulated_band_data_title),
                description = stringResource(R.string.debug_simulated_band_data_description),
                checked = state.simulatedBandData,
                onCheckedChange = onSimulatedBandDataChange,
                contentDescription = stringResource(R.string.debug_simulated_band_data_title),
                enabled = state.simulatedBandDataControlEnabled,
            )
            if (!state.simulatedBandDataControlEnabled) {
                Text(text = stringResource(R.string.debug_jump_night_active_reason), style = MaterialTheme.typography.bodySmall, color = NightOnSurfaceMuted)
            }
            Text(text = stringResource(R.string.debug_speed_title), style = MaterialTheme.typography.titleMedium)
            Text(text = stringResource(R.string.debug_speed_description), style = MaterialTheme.typography.bodySmall, color = NightOnSurfaceMuted)
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
            if (!state.simulatedTimeControlEnabled) {
                Text(text = stringResource(R.string.debug_requires_simulated_band_data_reason), style = MaterialTheme.typography.bodySmall, color = NightOnSurfaceMuted)
            }
        }

        SettingsCard {
            Text(text = stringResource(R.string.debug_jump_section_title), style = MaterialTheme.typography.titleMedium)
            MediumTimeText(time = state.currentVirtualTime, enabled = state.jumpAllowedNow, onTimeChange = onApplyClockJump)
            // W2: the reason sits directly under the picker it explains. It used to sit below the reset
            // button too, which read as if resetting were blocked as well - when resetting is precisely what
            // stays available.
            if (!state.jumpAllowedNow) {
                val reason = if (!state.simulatedTimeControlEnabled) {
                    stringResource(R.string.debug_requires_simulated_band_data_reason)
                } else {
                    stringResource(R.string.debug_jump_night_active_reason)
                }
                Text(text = reason, style = MaterialTheme.typography.bodySmall, color = NightOnSurfaceMuted)
            }
            SecondaryActionButton(
                text = stringResource(R.string.debug_reset_to_real_time_button),
                onClick = onResetToRealTime,
                enabled = state.resetToRealTimeAllowedNow,
            )
            if (!state.resetToRealTimeAllowedNow) {
                Text(text = stringResource(R.string.debug_jump_night_active_reason), style = MaterialTheme.typography.bodySmall, color = NightOnSurfaceMuted)
            }
        }

        SettingsCard {
            Text(text = stringResource(R.string.debug_simulator_controls_title), style = MaterialTheme.typography.titleMedium)
            ToggleRow(
                title = stringResource(R.string.debug_asleep_toggle_title),
                checked = state.simulatedAsleep,
                onCheckedChange = onSetSimulatedAsleep,
                contentDescription = stringResource(R.string.debug_asleep_toggle_title),
                enabled = state.sleepControlEnabled,
            )
            // W2: same placement rule as the jump card - the reason explains the toggle above it, not the
            // clear button below, which stays usable so a leftover timeline can always be emptied.
            if (!state.sleepControlEnabled) {
                Text(text = stringResource(R.string.debug_requires_simulated_band_data_reason), style = MaterialTheme.typography.bodySmall, color = NightOnSurfaceMuted)
            }
            SecondaryActionButton(text = stringResource(R.string.debug_clear_simulated_sleep_button), onClick = onClearSimulatedSleep, enabled = state.canClearSimulatedSleep)

            Text(text = stringResource(R.string.debug_timeline_title), style = MaterialTheme.typography.titleMedium)
            if (state.simulatedTimeline.isEmpty()) {
                Text(text = stringResource(R.string.debug_timeline_empty), style = MaterialTheme.typography.bodySmall, color = NightOnSurfaceMuted)
            }
            state.simulatedTimeline.forEach { line ->
                Text(
                    text = stringResource(R.string.debug_timeline_entry, line.kindLabel, line.fromTimeLabel, line.toTimeLabel),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SettingsCard {
            Text(text = stringResource(R.string.debug_test_alarm_title), style = MaterialTheme.typography.titleMedium)
            Text(text = stringResource(R.string.debug_ring_test_alarm_description), style = MaterialTheme.typography.bodySmall, color = NightOnSurfaceMuted)
            SecondaryActionButton(text = stringResource(R.string.debug_ring_test_alarm_button), onClick = onRingTestAlarm, enabled = state.canRingTestAlarm)
            if (!state.canRingTestAlarm) {
                Text(text = stringResource(R.string.debug_ring_test_alarm_disabled_reason), style = MaterialTheme.typography.bodySmall, color = NightOnSurfaceMuted)
            }
        }
    }
}
