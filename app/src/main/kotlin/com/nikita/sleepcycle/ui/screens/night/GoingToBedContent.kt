package com.nikita.sleepcycle.ui.screens.night

// File purpose: state A - going to bed, or still/again asleep with no completed awakening yet this stretch.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.HeroNumeral
import com.nikita.sleepcycle.ui.components.LabeledValueRow
import com.nikita.sleepcycle.ui.components.SettingsCard
import com.nikita.sleepcycle.ui.components.WakeTimeline
import com.nikita.sleepcycle.ui.state.NightScreenContent
import com.nikita.sleepcycle.ui.state.OnsetPhase
import com.nikita.sleepcycle.ui.theme.ScreenContentGap

@Composable
fun GoingToBedContent(content: NightScreenContent.GoingToBedOrAsleep) {
    val caption = when (content.onsetPhase) {
        OnsetPhase.PROJECTED -> stringResource(R.string.night_a_caption_if_asleep_by, content.onsetTimeLabel)
        OnsetPhase.ACTUAL -> stringResource(R.string.night_a_caption_asleep_since, content.onsetTimeLabel)
    }
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(ScreenContentGap)) {
        HeroNumeral(
            value = content.alarmTimeLabel,
            caption = caption,
            subtitle = if (content.isDeadlineOnly) {
                stringResource(R.string.night_a_deadline_only_note)
            } else {
                stringResource(R.string.night_a_sleep_length, content.sleepLengthHoursLabel)
            },
        )
        SettingsCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = stringResource(R.string.night_timeline_title), style = MaterialTheme.typography.bodySmall)
                if (content.deadlineTimeLabel != null) {
                    Text(text = stringResource(R.string.night_timeline_deadline_by, content.deadlineTimeLabel), style = MaterialTheme.typography.bodySmall)
                }
            }
            WakeTimeline(entries = content.timeline)
        }
        if (content.phoneSafetyAlarmTimeLabel != null) {
            SettingsCard {
                LabeledValueRow(label = stringResource(R.string.night_phone_safety_alarm), value = content.phoneSafetyAlarmTimeLabel)
            }
        }
    }
}
