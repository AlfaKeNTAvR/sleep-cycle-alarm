package com.nikita.sleepcycle.ui.screens

// File purpose: the "Before bed" screen - deadline switch and time, the "sleep up to" picker, and "Start night".

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.ConfirmDialog
import com.nikita.sleepcycle.ui.components.DebugBanner
import com.nikita.sleepcycle.ui.components.MenuLinesButton
import com.nikita.sleepcycle.ui.components.SlidersButton
import com.nikita.sleepcycle.ui.components.MediumTimeText
import com.nikita.sleepcycle.ui.components.PrimaryActionButton
import com.nikita.sleepcycle.ui.components.ScreenContainer
import com.nikita.sleepcycle.ui.components.SettingsCard
import com.nikita.sleepcycle.ui.components.SleepLengthChip
import com.nikita.sleepcycle.ui.components.StatusLine
import com.nikita.sleepcycle.ui.components.ToggleRow
import com.nikita.sleepcycle.ui.state.BeforeBedUiState
import com.nikita.sleepcycle.ui.state.SleepLengthOption
import com.nikita.sleepcycle.ui.theme.ChipGap
import java.time.LocalTime

/**
 * The "Before bed" screen. [onStartNight] is called when the owner taps "Start night" - the caller decides
 * whether that needs the "this is a simulated night" confirmation first (see [BeforeBedUiState.confirmingDebugNightStart]);
 * [onConfirmDebugNightStart] / [onCancelDebugNightStart] answer that confirmation when it is showing.
 *
 * W7: the simulator's live controls are NOT here. They were, briefly, and it was useless: they are needed
 * from the moment a night starts, which is exactly when this screen goes away. They live on the night screen
 * instead, and this screen's banner says only that the night will be simulated.
 */
@Composable
fun BeforeBedScreen(
    state: BeforeBedUiState,
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    onDeadlineEnabledChange: (Boolean) -> Unit,
    onDeadlineTimeChange: (LocalTime) -> Unit,
    onSleepLengthPicked: (Int) -> Unit,
    onStartNight: () -> Unit,
    onConfirmDebugNightStart: () -> Unit = {},
    onCancelDebugNightStart: () -> Unit = {},
) {
    ScreenContainer(scrollable = false) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            StatusLine(
                text = if (state.bandReady) stringResource(R.string.before_bed_band_connected) else stringResource(R.string.before_bed_band_not_ready),
                ok = state.bandReady,
            )
            Row {
                MenuLinesButton(stringResource(R.string.content_description_open_logs), onOpenLogs)
                SlidersButton(stringResource(R.string.content_description_open_settings), onOpenSettings)
            }
        }
        Text(text = stringResource(R.string.before_bed_title), style = MaterialTheme.typography.headlineLarge)

        DebugBanner(state.activeDebugSwitches, simulatedTimeValue = state.simulatedTimeValue)

        SettingsCard {
            ToggleRow(
                title = stringResource(R.string.before_bed_wake_me_by),
                checked = state.deadlineEnabled,
                onCheckedChange = onDeadlineEnabledChange,
                contentDescription = stringResource(R.string.content_description_deadline_switch),
            )
            MediumTimeText(time = state.deadlineTime, enabled = state.deadlineEnabled, onTimeChange = onDeadlineTimeChange)
        }

        SettingsCard {
            Text(text = stringResource(R.string.before_bed_sleep_up_to), style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ChipGap)) {
                state.sleepLengthOptions.forEach { option ->
                    SleepLengthChip(
                        label = option.hoursLabel,
                        selected = option.selected,
                        available = option.available,
                        onClick = { onSleepLengthPicked(option.cycles) },
                        contentDescription = sleepLengthContentDescription(option),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))

        if (state.startNightBlocker != null) {
            Text(
                text = stringResource(setupBlockerReasonRes(state.startNightBlocker)),
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (state.startNightBlockedBySetupCheck) {
            Text(
                text = stringResource(R.string.start_blocked_setup_check_stale),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        PrimaryActionButton(
            text = stringResource(R.string.before_bed_start_night),
            onClick = onStartNight,
            enabled = state.startNightEnabled,
        )
    }

    if (state.confirmingDebugNightStart) {
        ConfirmDialog(
            title = stringResource(R.string.debug_confirm_start_title),
            message = stringResource(R.string.debug_confirm_start_message),
            confirmText = stringResource(R.string.debug_confirm_start_confirm),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = onConfirmDebugNightStart,
            onDismiss = onCancelDebugNightStart,
        )
    }
}

@Composable
private fun sleepLengthContentDescription(option: SleepLengthOption): String =
    if (option.available) {
        stringResource(R.string.content_description_sleep_length_option, option.hoursLabel)
    } else {
        stringResource(R.string.content_description_sleep_length_option_unavailable, option.hoursLabel)
    }
