package com.nikita.sleepcycle.ui.screens.night

// File purpose: the night screen's chrome - status line, the confirm-end dialog, and dispatch to the content
// variant for the current engine mode (states A-D plus the FINISHED amendment).

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
import com.nikita.sleepcycle.BuildConfig
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.ConfirmDialog
import com.nikita.sleepcycle.ui.components.DebugBanner
import com.nikita.sleepcycle.ui.components.IconGlyphButton
import com.nikita.sleepcycle.ui.components.ScreenContainer
import com.nikita.sleepcycle.ui.components.SecondaryActionButton
import com.nikita.sleepcycle.ui.components.StatusLine
import com.nikita.sleepcycle.ui.state.EndNightAction
import com.nikita.sleepcycle.ui.state.NightScreenContent
import com.nikita.sleepcycle.ui.state.NightUiState
import com.nikita.sleepcycle.ui.theme.ScreenBottomPadding

/** The night screen: always-visible sync status, the state-specific content, and the end-night action. */
@Composable
fun NightScreen(
    state: NightUiState,
    onRequestEndNight: () -> Unit,
    onConfirmEndNight: () -> Unit,
    onCancelEndNight: () -> Unit,
    onDone: () -> Unit,
    onOpenDebug: () -> Unit = {},
) {
    ScreenContainer(scrollable = false, bottomPadding = ScreenBottomPadding) {
        DebugBanner(state.activeDebugSwitches)
        // BuildConfig.DEBUG-gated, same as the Debug row on Setup (SetupScreen.kt): the simulator's buttons
        // need to be reachable while a simulated night is actually running, not just before it starts.
        if (BuildConfig.DEBUG) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                IconGlyphButton(
                    glyph = stringResource(R.string.glyph_setup),
                    contentDescription = stringResource(R.string.content_description_open_debug),
                    onClick = onOpenDebug,
                )
            }
        }
        StatusLine(text = statusLineText(state), ok = state.lastSyncOk != false)

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            when (val content = state.content) {
                is NightScreenContent.Loading -> Text(stringResource(R.string.night_loading), style = MaterialTheme.typography.bodyLarge)
                is NightScreenContent.GoingToBedOrAsleep -> GoingToBedContent(content)
                is NightScreenContent.NapAsleep -> NapAsleepContent(content)
                is NightScreenContent.WokeUp -> WokeUpContent(content)
                is NightScreenContent.NightFinished -> FinishedContent(content)
                is NightScreenContent.MorningReport -> MorningReportContent(content)
            }
        }

        when (state.content) {
            is NightScreenContent.MorningReport ->
                com.nikita.sleepcycle.ui.components.PrimaryActionButton(text = stringResource(R.string.action_done), onClick = onDone)
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

    // Item 1: never shown while ending, even if a stray onRequestEndNight slipped through - the confirmation
    // dialog must not be re-openable while ending.
    if (state.confirmingEndNight && !state.endingNight) {
        val (titleRes, messageRes) = confirmDialogTextResources(state.endAction)
        ConfirmDialog(
            title = stringResource(titleRes),
            message = stringResource(messageRes),
            confirmText = endActionLabel(state.endAction),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = onConfirmEndNight,
            onDismiss = onCancelEndNight,
        )
    }
}

@Composable
private fun statusLineText(state: NightUiState): String = when {
    state.content is NightScreenContent.MorningReport -> stringResource(R.string.night_morning_ended_at, state.content.endedAtTimeLabel)
    state.lastSyncOk == null && state.lastSyncTimeLabel == null -> stringResource(R.string.night_status_never_synced)
    state.lastSyncOk == false && state.syncFailureCause != null ->
        stringResource(R.string.night_status_sync_failed, state.lastSyncTimeLabel.orEmpty(), state.syncFailureCause)
    state.lastSyncOk == false -> stringResource(R.string.night_status_data_stale, state.lastSyncTimeLabel.orEmpty())
    else -> stringResource(R.string.night_status_synced_ok, state.lastSyncTimeLabel.orEmpty())
}

@Composable
private fun endActionLabel(action: EndNightAction): String = when (action) {
    EndNightAction.STOP -> stringResource(R.string.night_stop_night)
    EndNightAction.IM_UP -> stringResource(R.string.night_im_up_end_night)
    EndNightAction.END -> stringResource(R.string.night_end_night)
}

private fun confirmDialogTextResources(action: EndNightAction): Pair<Int, Int> = when (action) {
    EndNightAction.STOP -> R.string.night_confirm_stop_title to R.string.night_confirm_stop_message
    EndNightAction.IM_UP, EndNightAction.END -> R.string.night_confirm_im_up_title to R.string.night_confirm_im_up_message
}
