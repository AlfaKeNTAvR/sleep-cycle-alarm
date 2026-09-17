package com.nikita.sleepcycle.ui.screens

// File purpose: the Debug/simulation screen - reached from a "Debug" row at the bottom of Setup, present
// only in a debug build (see DebugOptions.kt). Three independent switches, the simulator's event buttons and
// timeline, and the phone-alarm daylight test button.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.IconGlyphButton
import com.nikita.sleepcycle.ui.components.SecondaryActionButton
import com.nikita.sleepcycle.ui.components.SettingsCard
import com.nikita.sleepcycle.ui.components.ToggleRow
import com.nikita.sleepcycle.ui.state.DebugUiState
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted
import com.nikita.sleepcycle.ui.theme.ScreenContentGap
import com.nikita.sleepcycle.ui.theme.ScreenHorizontalPadding
import com.nikita.sleepcycle.ui.theme.ScreenTopPadding

/** The Debug/simulation screen. */
@Composable
fun DebugScreen(
    state: DebugUiState,
    onSimulatedBandDataChange: (Boolean) -> Unit,
    onFastNightChange: (Boolean) -> Unit,
    onDryRunBandCommandsChange: (Boolean) -> Unit,
    onFellAsleep: () -> Unit,
    onWokeUp: () -> Unit,
    onFellBackAsleep: () -> Unit,
    onClearSimulatedSleep: () -> Unit,
    onRingTestAlarm: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenHorizontalPadding)
            .padding(top = ScreenTopPadding, bottom = ScreenContentGap),
        verticalArrangement = Arrangement.spacedBy(ScreenContentGap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconGlyphButton(
                glyph = stringResource(R.string.glyph_back),
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
            )
            ToggleRow(
                title = stringResource(R.string.debug_fast_night_title),
                description = stringResource(R.string.debug_fast_night_description),
                checked = state.fastNight,
                onCheckedChange = onFastNightChange,
                contentDescription = stringResource(R.string.debug_fast_night_title),
            )
            ToggleRow(
                title = stringResource(R.string.debug_dry_run_title),
                description = stringResource(R.string.debug_dry_run_description),
                checked = state.dryRunBandCommands,
                onCheckedChange = onDryRunBandCommandsChange,
                contentDescription = stringResource(R.string.debug_dry_run_title),
            )
        }

        SettingsCard {
            Text(text = stringResource(R.string.debug_simulator_controls_title), style = MaterialTheme.typography.titleMedium)
            SecondaryActionButton(text = stringResource(R.string.debug_fell_asleep_button), onClick = onFellAsleep, enabled = state.canFallAsleep)
            SecondaryActionButton(text = stringResource(R.string.debug_woke_up_button), onClick = onWokeUp, enabled = state.canWakeUp)
            SecondaryActionButton(text = stringResource(R.string.debug_fell_back_asleep_button), onClick = onFellBackAsleep, enabled = state.canFallBackAsleep)
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
