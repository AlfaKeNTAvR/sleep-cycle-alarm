package com.nikita.sleepcycle.ui.screens.night

// File purpose: the OVERDUE amendment - still asleep past the planned alarm; the band keeps buzzing.

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
fun OverdueContent(content: NightScreenContent.Overdue) {
    Column(verticalArrangement = Arrangement.spacedBy(ScreenContentGap)) {
        HeroNumeral(
            caption = stringResource(R.string.night_overdue_title),
            value = content.nextBuzzTimeLabel,
        )
        if (content.phoneSafetyAlarmTimeLabel != null) {
            SettingsCard {
                LabeledValueRow(label = stringResource(R.string.night_phone_safety_alarm), value = content.phoneSafetyAlarmTimeLabel)
            }
        }
    }
}
