package com.nikita.sleepcycle.ui.screens.night

// File purpose: the night screen's chrome - status line, the confirm-end dialog, and dispatch to the content
// variant for the current engine mode (N1: one variant for every live night, plus FINISHED and the morning
// report). P3: the "Nap for 20 min" button above the end-night button while the out-of-bed nudge is pending,
// and the alarm block centred against the whole screen so that button never moves it.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.nikita.sleepcycle.ui.theme.CardCornerRadius
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.nikita.sleepcycle.BuildConfig
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.ConfirmDialog
import com.nikita.sleepcycle.ui.components.DebugBanner
import com.nikita.sleepcycle.ui.components.DebugQuickControls
import com.nikita.sleepcycle.ui.components.PrimaryActionButton
import com.nikita.sleepcycle.ui.components.SlidersButton
import com.nikita.sleepcycle.ui.components.ScreenContainer
import com.nikita.sleepcycle.ui.components.SecondaryActionButton
import com.nikita.sleepcycle.ui.components.StatusLine
import com.nikita.sleepcycle.ui.state.DebugUiState
import com.nikita.sleepcycle.ui.state.EndNightAction
import com.nikita.sleepcycle.ui.state.NightScreenContent
import com.nikita.sleepcycle.ui.state.NightUiState
import com.nikita.sleepcycle.ui.theme.ScreenBottomPadding
import com.nikita.sleepcycle.ui.theme.ScreenHorizontalPadding

/** The night screen: always-visible sync status, the state-specific content, and the end-night action. */
@Composable
fun NightScreen(
    state: NightUiState,
    debug: DebugUiState,
    onRequestEndNight: () -> Unit,
    onConfirmEndNight: () -> Unit,
    onCancelEndNight: () -> Unit,
    onStartNap: () -> Unit,
    onRequestImUp: () -> Unit,
    onConfirmImUp: () -> Unit,
    onCancelImUp: () -> Unit,
    onDone: () -> Unit,
    onSpeedChange: (Int) -> Unit,
    onSetSimulatedAsleep: (Boolean) -> Unit,
    onOpenDebug: () -> Unit = {},
) {
    // P3 (owner request, 2026-09-30): the alarm block (label, big time, countdown) is centred against the WHOLE
    // screen, drawn over the chrome rather than inside the space the chrome leaves, so its position never
    // depends on how many buttons sit at the bottom - adding "Nap for 20 min" must not push it up. The morning
    // report is a tall card, not a three-line block, so it keeps filling the space between header and button.
    val centredOverScreen = state.content !is NightScreenContent.MorningReport
    Box(modifier = Modifier.fillMaxSize()) {
        ScreenContainer(scrollable = false, bottomPadding = ScreenBottomPadding) {
            // W15 (owner request): the sync line shares one row with the toolbar icon beside it, the same header
            // shape Before bed uses for "Band connected" - not a lone icon with the status stacked underneath.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                // Owner request, 2026-09-30: "Not synced yet" is not good news, so no green dot - grey (null) until
                // the first sync answers, then green or red.
                StatusLine(text = statusLineText(state), ok = state.lastSyncOk)
                // BuildConfig.DEBUG-gated, same as the Debug row Settings carries (SettingsScreen.kt): the
                // simulator's buttons must be reachable while a simulated night runs, not just before it starts.
                if (BuildConfig.DEBUG) {
                    SlidersButton(
                        contentDescription = stringResource(R.string.content_description_open_debug),
                        onClick = onOpenDebug,
                    )
                }
            }
            // W11 (owner request): the simulated-clock banner sits UNDER the sync line and the debug button, not
            // above them - the sync line is the one thing worth reading first, and the banner only ever renders on
            // a simulated night anyway.
            DebugBanner(state.activeDebugSwitches, simulatedTimeValue = state.simulatedTimeValue)
            // P1: while band data is not reaching the app, say so where it cannot be missed - see
            // NightUiState.bandNotSyncingWarning.
            if (state.bandNotSyncingWarning) BandNotSyncingWarning()

            val content = state.content
            if (content is NightScreenContent.MorningReport) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    MorningReportContent(content)
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            // W7: the speed row and the Asleep toggle, right where a simulated night is actually watched - this
            // screen is where the owner spends the whole simulation, and driving it meant a round trip to Debug
            // for every change. Renders nothing at all on a real night.
            DebugQuickControls(state = debug, onSpeedChange = onSpeedChange, onSetSimulatedAsleep = onSetSimulatedAsleep)

            // P3 (owner spec, 2026-09-30): "Nap for 20 min", a filled amber button stacked ABOVE the outlined
            // end-night button, offered only while the out-of-bed nudge is pending (NightUiState.napButtonMinutes).
            // Hidden while ending the night, so the two actions can never race.
            val napMinutes = state.napButtonMinutes
            if (napMinutes != null && !state.endingNight && content !is NightScreenContent.MorningReport) {
                PrimaryActionButton(text = stringResource(R.string.night_nap_for_minutes, napMinutes), onClick = onStartNap)
            }
            // Owner spec, 2026-10-02: "I'm up", split out of "I'm up, end night" - offered until the morning
            // alarm rings, it starts the out-of-bed cycle early so Nap is available without waiting for the alarm.
            if (state.showImUpButton && !state.endingNight && content !is NightScreenContent.MorningReport) {
                PrimaryActionButton(text = stringResource(R.string.night_im_up), onClick = onRequestImUp)
            }

            when (content) {
                is NightScreenContent.MorningReport ->
                    PrimaryActionButton(text = stringResource(R.string.action_done), onClick = onDone)
                is NightScreenContent.Loading -> Unit
                // Item 1: disabled and relabelled the instant "confirm" is tapped, for the whole ~3 s endNight
                // takes - the owner tapped this twice on both real nights because it stayed enabled with no
                // indication anything was happening.
                else -> SecondaryActionButton(
                    text = if (state.endingNight) stringResource(R.string.night_ending) else endActionLabel(state.endAction),
                    onClick = onRequestEndNight,
                    enabled = !state.endingNight,
                )
            }
        }
        if (centredOverScreen) {
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = ScreenHorizontalPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (val centred = state.content) {
                        is NightScreenContent.Loading -> Text(stringResource(R.string.night_loading), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                        is NightScreenContent.NextAlarm -> NextAlarmContent(centred)
                        is NightScreenContent.NightFinished -> FinishedContent(centred)
                        is NightScreenContent.MorningReport -> Unit
                    }
                }
            }
        }
    }

    // Item 1: never shown while ending, even if a stray onRequestEndNight slipped through - the confirmation
    // dialog must not be re-openable while ending.
    if (state.confirmingImUp && !state.endingNight) {
        ConfirmDialog(
            title = stringResource(R.string.night_confirm_im_up_early_title),
            message = null,
            confirmText = stringResource(R.string.night_im_up),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = onConfirmImUp,
            onDismiss = onCancelImUp,
        )
    }
    if (state.confirmingEndNight && !state.endingNight) {
        // Owner request, 2026-10-02: the title alone, centred, no explanation underneath.
        ConfirmDialog(
            title = stringResource(confirmDialogTitleResource(state.endAction)),
            message = null,
            confirmText = endActionLabel(state.endAction),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = onConfirmEndNight,
            onDismiss = onCancelEndNight,
        )
    }
}

/** P1: the "band not syncing" warning - the alarm is still armed, but it is only the engine's estimate. */
@Composable
private fun BandNotSyncingWarning() {
    Text(
        text = stringResource(R.string.night_band_not_syncing_warning),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onErrorContainer,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(CardCornerRadius))
            .padding(12.dp),
    )
}

@Composable
private fun statusLineText(state: NightUiState): String = when {
    state.content is NightScreenContent.MorningReport -> stringResource(R.string.night_morning_ended_at, state.content.endedAtTimeLabel)
    state.lastSyncOk == null && state.lastSyncTimeLabel == null -> stringResource(R.string.night_status_never_synced)
    // Owner request, 2026-09-30: the technical cause ("timed out after PT20S") is not shown - the night log keeps it.
    state.lastSyncOk == false && state.syncFailureCause != null ->
        stringResource(R.string.night_status_sync_failed, state.lastSyncTimeLabel.orEmpty())
    state.lastSyncOk == false -> stringResource(R.string.night_status_data_stale, state.lastSyncTimeLabel.orEmpty())
    else -> stringResource(R.string.night_status_synced_ok, state.lastSyncTimeLabel.orEmpty())
}

@Composable
private fun endActionLabel(action: EndNightAction): String = when (action) {
    EndNightAction.STOP -> stringResource(R.string.night_stop_night)
    EndNightAction.IM_UP -> stringResource(R.string.night_im_up_end_night)
    EndNightAction.END -> stringResource(R.string.night_end_night)
}

private fun confirmDialogTitleResource(action: EndNightAction): Int = when (action) {
    EndNightAction.STOP -> R.string.night_confirm_stop_title
    EndNightAction.IM_UP, EndNightAction.END -> R.string.night_confirm_im_up_title
}
