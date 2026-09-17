package com.nikita.sleepcycle.ui.screens.night

// File purpose: states B (more sleep fits) and C (only a nap fits).

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.CardDivider
import com.nikita.sleepcycle.ui.components.HeroNumeral
import com.nikita.sleepcycle.ui.components.LabeledValueRow
import com.nikita.sleepcycle.ui.components.SettingsCard
import com.nikita.sleepcycle.ui.components.WakeTimeline
import com.nikita.sleepcycle.ui.state.NightScreenContent
import com.nikita.sleepcycle.ui.theme.ScreenContentGap

@Composable
fun WokeUpContent(content: NightScreenContent.WokeUp) {
    Column(verticalArrangement = Arrangement.spacedBy(ScreenContentGap)) {
        HeroNumeral(
            caption = stringResource(R.string.night_woke_caption),
            value = content.sleptDurationLabel,
            detail = if (content.napOnly && content.wakeBoundaryTimeLabel != null) {
                stringResource(R.string.night_c_timeline_header, content.wakeBoundaryTimeLabel)
            } else {
                null
            },
        )
        if (content.napOnly) {
            SettingsCard {
                Text(
                    text = stringResource(R.string.night_c_nap_title, content.napLengthLabel.orEmpty()),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(text = stringResource(R.string.night_c_nap_description), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            SettingsCard {
                if (content.wakeBoundaryTimeLabel != null) {
                    Text(text = stringResource(R.string.night_b_timeline_header, content.wakeBoundaryTimeLabel), style = MaterialTheme.typography.bodySmall)
                }
                WakeTimeline(entries = content.timeline)
            }
        }
        SettingsCard {
            if (content.tonightSoFarLabel != null) {
                LabeledValueRow(label = stringResource(R.string.night_tonight_so_far), value = content.tonightSoFarLabel)
                CardDivider()
            }
            if (content.bandAlarmTimeLabel != null) {
                val bandAlarmValue = if (content.bandAlarmIsEstimate) {
                    stringResource(R.string.night_band_alarm_estimate, content.bandAlarmTimeLabel)
                } else {
                    content.bandAlarmTimeLabel
                }
                LabeledValueRow(label = stringResource(R.string.night_band_alarm), value = bandAlarmValue)
                CardDivider()
            }
            if (content.phoneSafetyAlarmTimeLabel != null) {
                LabeledValueRow(label = stringResource(R.string.night_phone_safety_alarm), value = content.phoneSafetyAlarmTimeLabel)
            }
        }
    }
}
