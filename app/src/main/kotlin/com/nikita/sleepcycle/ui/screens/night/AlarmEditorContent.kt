package com.nikita.sleepcycle.ui.screens.night

// File purpose: Q2 - the Night screen's alarm editor, drawn in place of the Night screen while open: Before
// bed's own "Wake me by" and "Sleep up to" pickers, the alarm they would set, and Save / Cancel. Back cancels.

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.AlarmSettingsCards
import com.nikita.sleepcycle.ui.components.PrimaryActionButton
import com.nikita.sleepcycle.ui.components.ScreenContainer
import com.nikita.sleepcycle.ui.components.SecondaryActionButton
import com.nikita.sleepcycle.ui.state.AlarmEditorProblem
import com.nikita.sleepcycle.ui.state.AlarmEditorUiState
import com.nikita.sleepcycle.ui.theme.AmberAccent
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted
import java.time.LocalTime

/** The alarm editor's actions, grouped so the Night screen takes one parameter for them. */
data class AlarmEditorActions(
    val onCancel: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onDeadlineEnabledChange: (Boolean) -> Unit = {},
    val onDeadlineTimeChange: (LocalTime) -> Unit = {},
    val onSleepLengthPicked: (Int) -> Unit = {},
    val onNoAlarmPicked: () -> Unit = {},
)

@Composable
fun AlarmEditorContent(state: AlarmEditorUiState, actions: AlarmEditorActions) {
    BackHandler(onBack = actions.onCancel)
    ScreenContainer(scrollable = false) {
        Text(text = stringResource(R.string.alarm_editor_title), style = MaterialTheme.typography.headlineLarge)
        AlarmSettingsCards(
            deadlineEnabled = state.deadlineEnabled,
            deadlineTime = state.deadlineTime,
            sleepLengthOptions = state.sleepLengthOptions,
            noAlarmSelected = state.noAlarmSelected,
            onDeadlineEnabledChange = actions.onDeadlineEnabledChange,
            onDeadlineTimeChange = actions.onDeadlineTimeChange,
            onSleepLengthPicked = actions.onSleepLengthPicked,
            onNoAlarmPicked = actions.onNoAlarmPicked,
        )
        Spacer(modifier = Modifier.weight(1f))
        val problem = state.problem
        Text(
            text = if (problem != null) stringResource(problemText(problem)) else previewText(state),
            style = MaterialTheme.typography.bodyLarge,
            color = if (problem != null) AmberAccent else NightOnSurfaceMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryActionButton(text = stringResource(R.string.alarm_editor_save), onClick = actions.onSave, enabled = state.saveEnabled)
        SecondaryActionButton(text = stringResource(R.string.action_cancel), onClick = actions.onCancel)
    }
}

/** The alarm the choice would set, in the words the Night screen itself uses. */
@Composable
private fun previewText(state: AlarmEditorUiState): String {
    val time = state.newAlarmTimeLabel
    return when {
        state.noAlarmSelected -> stringResource(R.string.alarm_editor_preview_no_alarm)
        time == null -> stringResource(R.string.night_no_alarm_armed)
        state.newAlarmAtWakeBy -> stringResource(R.string.alarm_editor_preview_at_wake_by, time)
        else -> stringResource(R.string.alarm_editor_preview, time)
    }
}

private fun problemText(problem: AlarmEditorProblem): Int = when (problem) {
    AlarmEditorProblem.DEADLINE_PASSED -> R.string.alarm_editor_deadline_passed
    AlarmEditorProblem.MORNING_ALARM_RANG -> R.string.alarm_editor_morning_alarm_rang
    AlarmEditorProblem.NOT_SAVED -> R.string.alarm_editor_not_saved
}
