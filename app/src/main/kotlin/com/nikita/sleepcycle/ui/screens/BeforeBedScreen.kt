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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.AlarmSettingsCards
import com.nikita.sleepcycle.ui.components.ConfirmDialog
import com.nikita.sleepcycle.ui.components.LiveVolumeLine
import com.nikita.sleepcycle.ui.components.SimulatedBlock
import com.nikita.sleepcycle.ui.components.MenuLinesButton
import com.nikita.sleepcycle.ui.components.SlidersButton
import com.nikita.sleepcycle.ui.components.PrimaryActionButton
import com.nikita.sleepcycle.ui.components.ScreenContainer
import com.nikita.sleepcycle.ui.components.SecondaryActionButton
import com.nikita.sleepcycle.ui.components.StatusLine
import com.nikita.sleepcycle.ui.state.BandCheckStatus
import com.nikita.sleepcycle.ui.state.BandStatus
import com.nikita.sleepcycle.ui.state.BeforeBedUiState
import com.nikita.sleepcycle.ui.theme.AmberAccent
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted
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
    onNoAlarmPicked: () -> Unit,
    onStartNight: () -> Unit,
    onConfirmDebugNightStart: () -> Unit = {},
    onCancelDebugNightStart: () -> Unit = {},
    onTestAgain: () -> Unit = {},
    onOpenGadgetbridge: () -> Unit = {},
) {
    ScreenContainer(scrollable = false) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            StatusLine(
                text = when (state.bandStatus) {
                    BandStatus.CONNECTED -> stringResource(R.string.before_bed_band_connected_at, state.bandCheckedAtLabel.orEmpty())
                    BandStatus.NOT_CHECKED_RECENTLY -> stringResource(R.string.night_status_never_synced)
                    BandStatus.CHECKING -> stringResource(R.string.before_bed_band_checking)
                    BandStatus.NOT_RESPONDING -> stringResource(R.string.before_bed_band_not_responding)
                    BandStatus.NOT_MEASURING -> stringResource(R.string.before_bed_band_not_measuring)
                    BandStatus.SETUP_INCOMPLETE -> stringResource(R.string.before_bed_band_not_ready)
                },
                ok = when (state.bandStatus) {
                    BandStatus.CONNECTED -> true
                    BandStatus.CHECKING, BandStatus.NOT_CHECKED_RECENTLY -> null
                    BandStatus.NOT_RESPONDING, BandStatus.NOT_MEASURING, BandStatus.SETUP_INCOMPLETE -> false
                },
            )
            Row {
                MenuLinesButton(stringResource(R.string.content_description_open_logs), onOpenLogs)
                SlidersButton(stringResource(R.string.content_description_open_settings), onOpenSettings)
            }
        }
        Text(text = stringResource(R.string.before_bed_title), style = MaterialTheme.typography.headlineLarge)

        if (state.activeDebugSwitches.isNotEmpty()) SimulatedBlock(state.simulatedTimeValue)

        AlarmSettingsCards(
            deadlineEnabled = state.deadlineEnabled,
            deadlineTime = state.deadlineTime,
            sleepLengthOptions = state.sleepLengthOptions,
            noAlarmSelected = state.noAlarmSelected,
            onDeadlineEnabledChange = onDeadlineEnabledChange,
            onDeadlineTimeChange = onDeadlineTimeChange,
            onSleepLengthPicked = onSleepLengthPicked,
            onNoAlarmPicked = onNoAlarmPicked,
        )

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))

        BandCheckLine(state.bandCheck, onTestAgain)
        // P5: the app cannot reconnect the band itself until this Gadgetbridge toggle is on. Quiet, never a blocker.
        if (state.showBluetoothIntentApiHint) {
            Text(
                text = stringResource(R.string.before_bed_bluetooth_intent_api_hint),
                style = MaterialTheme.typography.bodySmall,
                color = NightOnSurfaceMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Owner request, 2026-10-05: the band did not answer, so offer Gadgetbridge itself, to reconnect it there.
        if (state.offerOpenGadgetbridge) {
            SecondaryActionButton(text = stringResource(R.string.open_gadgetbridge), onClick = onOpenGadgetbridge)
        }
        if (state.startNightBlocker != null) {
            Text(
                text = stringResource(setupBlockerReasonRes(state.startNightBlocker)),
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (state.startNightBlockedBySetupCheck && state.bandCheck == BandCheckStatus.None) {
            Text(
                text = stringResource(R.string.start_blocked_setup_check_stale),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Owner spec, 2026-10-22 (PER-133): the live output and media volume, directly above Start night, as a
        // reference for the fade's starting volume. Hidden while the simulation block shows the volume instead.
        if (state.showLiveVolumeLine) LiveVolumeLine()
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

/**
 * P2: the latest connection test, quietly, right above "Start night": a muted "checking" line while one runs, or
 * the failure reason in amber with a "Test again" button. Never a dialog, and never itself disables Start night.
 */
@Composable
private fun BandCheckLine(bandCheck: BandCheckStatus, onTestAgain: () -> Unit) {
    when (bandCheck) {
        BandCheckStatus.None -> Unit
        BandCheckStatus.Checking -> Text(
            text = stringResource(R.string.before_bed_band_check_running),
            style = MaterialTheme.typography.bodySmall,
            color = NightOnSurfaceMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        is BandCheckStatus.Failed -> {
            Text(
                text = stringResource(R.string.before_bed_band_check_failed, bandCheck.reason),
                style = MaterialTheme.typography.bodySmall,
                color = AmberAccent,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryActionButton(text = stringResource(R.string.before_bed_band_check_retry), onClick = onTestAgain)
        }
    }
}
