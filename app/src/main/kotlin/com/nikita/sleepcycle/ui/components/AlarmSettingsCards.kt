package com.nikita.sleepcycle.ui.components

// File purpose: the "Wake me by" card and the "Sleep up to" chips with None, shared by Before bed and the Night
// screen's alarm editor (Q2), so both pick the night's alarm the same way.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.state.SleepLengthOption
import com.nikita.sleepcycle.ui.theme.ChipGap
import java.time.LocalTime

/** The deadline switch with its time, then the sleep length chips and None. Unavailable lengths are hatched and cannot be picked. */
@Composable
fun AlarmSettingsCards(
    deadlineEnabled: Boolean,
    deadlineTime: LocalTime,
    sleepLengthOptions: List<SleepLengthOption>,
    noAlarmSelected: Boolean,
    onDeadlineEnabledChange: (Boolean) -> Unit,
    onDeadlineTimeChange: (LocalTime) -> Unit,
    onSleepLengthPicked: (Int) -> Unit,
    onNoAlarmPicked: () -> Unit,
) {
    SettingsCard {
        ToggleRow(
            title = stringResource(R.string.before_bed_wake_me_by),
            checked = deadlineEnabled,
            onCheckedChange = onDeadlineEnabledChange,
            contentDescription = stringResource(R.string.content_description_deadline_switch),
        )
        MediumTimeText(time = deadlineTime, enabled = deadlineEnabled, onTimeChange = onDeadlineTimeChange)
    }

    SettingsCard {
        Text(text = stringResource(R.string.before_bed_sleep_up_to), style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ChipGap)) {
            sleepLengthOptions.forEach { option ->
                SleepLengthChip(
                    label = option.hoursLabel,
                    selected = option.selected,
                    available = option.available,
                    onClick = { onSleepLengthPicked(option.cycles) },
                    contentDescription = sleepLengthContentDescription(option),
                    modifier = Modifier.weight(1f),
                )
            }
            // PER-150: a No-alarm night, after the cycle chips. Always available: it needs no deadline to fit.
            SleepLengthChip(
                label = stringResource(R.string.sleep_length_no_alarm),
                selected = noAlarmSelected,
                available = true,
                onClick = onNoAlarmPicked,
                contentDescription = stringResource(R.string.content_description_sleep_length_no_alarm),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun sleepLengthContentDescription(option: SleepLengthOption): String =
    if (option.available) {
        stringResource(R.string.content_description_sleep_length_option, option.hoursLabel)
    } else {
        stringResource(R.string.content_description_sleep_length_option_unavailable, option.hoursLabel)
    }
