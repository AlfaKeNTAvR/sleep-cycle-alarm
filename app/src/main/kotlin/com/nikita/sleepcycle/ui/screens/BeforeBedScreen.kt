package com.nikita.sleepcycle.ui.screens

// File purpose: the "Before bed" screen - deadline switch and time, the "sleep up to" picker, the phone-backup
// switch, and "Start night".

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
import com.nikita.sleepcycle.ui.components.AmberWarningLine
import com.nikita.sleepcycle.ui.components.ConfirmDialog
import com.nikita.sleepcycle.ui.components.DebugBanner
import com.nikita.sleepcycle.ui.components.IconGlyphButton
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
 */
@Composable
fun BeforeBedScreen(
    state: BeforeBedUiState,
    onOpenSetup: () -> Unit,
    onOpenLogs: () -> Unit,
    onDeadlineEnabledChange: (Boolean) -> Unit,
    onDeadlineTimeChange: (LocalTime) -> Unit,
    onSleepLengthPicked: (Int) -> Unit,
    onPhoneBackupChange: (Boolean) -> Unit,
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
                IconGlyphButton(stringResource(R.string.glyph_logs), stringResource(R.string.content_description_open_logs), onOpenLogs)
                IconGlyphButton(stringResource(R.string.glyph_setup), stringResource(R.string.content_description_open_setup), onOpenSetup)
            }
        }
        Text(text = stringResource(R.string.before_bed_title), style = MaterialTheme.typography.headlineLarge)

        DebugBanner(state.activeDebugSwitches)

        SettingsCard {
            ToggleRow(
                title = stringResource(R.string.before_bed_wake_me_by),
                checked = state.deadlineEnabled,
                onCheckedChange = onDeadlineEnabledChange,
                contentDescription = stringResource(R.string.content_description_deadline_switch),
            )
            MediumTimeText(time = state.deadlineTime, enabled = state.deadlineEnabled, onTimeChange = onDeadlineTimeChange)
            Text(
                text = stringResource(R.string.before_bed_wake_me_by_description),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!state.deadlineEnabled) {
                Text(
                    text = stringResource(R.string.before_bed_no_deadline_note),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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

        if (state.phoneBackupRowVisible) {
            SettingsCard {
                ToggleRow(
                    title = stringResource(R.string.before_bed_phone_backup_title),
                    description = stringResource(R.string.before_bed_phone_backup_description),
                    checked = state.phoneBackupEnabled,
                    onCheckedChange = onPhoneBackupChange,
                    contentDescription = stringResource(R.string.content_description_phone_backup_switch),
                )
            }
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))

        if (state.noPhoneAlarmWarning) {
            AmberWarningLine(text = stringResource(R.string.warning_no_phone_alarm))
        }
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
        } else if (state.startNightBlockedByNoPhoneAlarm) {
            Text(
                text = stringResource(R.string.start_blocked_single_slot_needs_phone_alarm),
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
