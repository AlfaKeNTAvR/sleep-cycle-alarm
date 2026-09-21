package com.nikita.sleepcycle.ui.screens.night

// File purpose: states B (more sleep fits) and C (only a nap fits).

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.AlarmModeHeader
import com.nikita.sleepcycle.ui.components.CardDivider
import com.nikita.sleepcycle.ui.components.HeroNumeral
import com.nikita.sleepcycle.ui.components.LabeledValueRow
import com.nikita.sleepcycle.ui.components.SettingsCard
import com.nikita.sleepcycle.ui.state.NightScreenContent
import com.nikita.sleepcycle.ui.theme.ScreenContentGap

@Composable
fun WokeUpContent(content: NightScreenContent.WokeUp) {
    Column(verticalArrangement = Arrangement.spacedBy(ScreenContentGap)) {
        // Owner request: which alarm is coming and why, glanceable above the hero number - see AlarmModeHeader.kt.
        AlarmModeHeader(modeLabel = content.modeLabel, reasonText = content.reasonText)
        HeroNumeral(
            caption = stringResource(R.string.night_woke_caption),
            value = content.sleptDurationLabel,
            detail = if (content.napOnly && content.headerTimeLabel != null) {
                stringResource(R.string.night_c_timeline_header, content.headerTimeLabel)
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
        } else if (content.headerTimeLabel != null) {
            // The "possible wake-ups" bar timeline that used to sit under this header was removed (owner
            // request: not worth reading at 3am) - only the header sentence itself remains, since it answers a
            // different question ("when do I need to be back asleep by") than a list of candidate wake times.
            SettingsCard {
                Text(text = stringResource(R.string.night_b_timeline_header, content.headerTimeLabel), style = MaterialTheme.typography.bodySmall)
            }
        }
        SettingsCard {
            if (content.tonightSoFarLabel != null) {
                LabeledValueRow(label = stringResource(R.string.night_tonight_so_far), value = content.tonightSoFarLabel)
                CardDivider()
            }
            if (content.alarmTimeLabel != null) {
                val alarmValue = if (content.alarmIsEstimate) {
                    stringResource(R.string.night_alarm_estimate, content.alarmTimeLabel)
                } else {
                    content.alarmTimeLabel
                }
                LabeledValueRow(label = stringResource(R.string.night_alarm), value = alarmValue)
            }
        }
    }
}
