package com.nikita.sleepcycle.ui.screens

// File purpose: one saved night reopened from the Logs list - the same morning-report body state D shows,
// under this night's own date, plus the deadline and picked length that night ran under.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.CardDivider
import com.nikita.sleepcycle.ui.components.BackArrowButton
import com.nikita.sleepcycle.ui.components.LabeledValueRow
import com.nikita.sleepcycle.ui.components.ScreenContainer
import com.nikita.sleepcycle.ui.components.SettingsCard
import com.nikita.sleepcycle.ui.screens.night.MorningReportBody
import com.nikita.sleepcycle.ui.state.PastNightUiState
import com.nikita.sleepcycle.ui.theme.AmberAccent
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted

private val TitleRowGap = 6.dp

/** One past night: its report if the log recorded one, and an honest line about what is missing if it did not. */
@Composable
fun PastNightScreen(state: PastNightUiState, onBack: () -> Unit) {
    ScreenContainer(scrollable = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TitleRowGap)) {
            BackArrowButton(
                contentDescription = stringResource(R.string.content_description_back),
                onClick = onBack,
            )
            Text(text = state.title, style = MaterialTheme.typography.headlineSmall)
            if (state.isSimulated) {
                Text(text = stringResource(R.string.logs_simulated_tag), style = MaterialTheme.typography.labelSmall, color = AmberAccent)
            }
        }
        if (state.report == null) {
            Text(text = stringResource(R.string.past_night_no_summary), style = MaterialTheme.typography.bodyMedium, color = NightOnSurfaceMuted)
        } else {
            MorningReportBody(content = state.report, caption = stringResource(R.string.past_night_slept))
            if (state.recordedStretchCount != null) {
                Text(
                    text = pluralStringResource(
                        R.plurals.past_night_stretches_not_recorded,
                        state.recordedStretchCount,
                        state.recordedStretchCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = NightOnSurfaceMuted,
                )
            }
        }
        if (state.deadlineTimeLabel != null || state.pickedLengthLabel != null) {
            SettingsCard {
                if (state.deadlineTimeLabel != null) {
                    LabeledValueRow(label = stringResource(R.string.past_night_deadline), value = state.deadlineTimeLabel)
                    if (state.pickedLengthLabel != null) CardDivider()
                }
                if (state.pickedLengthLabel != null) {
                    LabeledValueRow(label = stringResource(R.string.past_night_picked_length), value = state.pickedLengthLabel)
                }
            }
        }
    }
}
