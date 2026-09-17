package com.nikita.sleepcycle.ui.screens.night

// File purpose: NAP mode while asleep again for the nap (D4) - asleep-style wording, not the "you slept"
// wording WokeUpContent uses; there is no completed stretch to report yet, just the short nap alarm ahead.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.HeroNumeral
import com.nikita.sleepcycle.ui.components.LabeledValueRow
import com.nikita.sleepcycle.ui.components.SettingsCard
import com.nikita.sleepcycle.ui.state.NightScreenContent
import com.nikita.sleepcycle.ui.theme.ScreenContentGap

@Composable
fun NapAsleepContent(content: NightScreenContent.NapAsleep) {
    Column(verticalArrangement = Arrangement.spacedBy(ScreenContentGap)) {
        HeroNumeral(
            value = content.alarmTimeLabel,
            caption = stringResource(R.string.night_nap_asleep_caption, content.onsetTimeLabel),
        )
        if (content.phoneSafetyAlarmTimeLabel != null) {
            SettingsCard {
                LabeledValueRow(label = stringResource(R.string.night_phone_safety_alarm), value = content.phoneSafetyAlarmTimeLabel)
            }
        }
    }
}
