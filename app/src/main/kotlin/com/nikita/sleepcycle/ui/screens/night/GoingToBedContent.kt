package com.nikita.sleepcycle.ui.screens.night

// File purpose: state A - going to bed, or still/again asleep with no completed awakening yet this stretch.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.AlarmModeHeader
import com.nikita.sleepcycle.ui.components.HeroNumeral
import com.nikita.sleepcycle.ui.state.NightScreenContent
import com.nikita.sleepcycle.ui.state.OnsetPhase
import com.nikita.sleepcycle.ui.theme.ScreenContentGap

@Composable
fun GoingToBedContent(content: NightScreenContent.GoingToBedOrAsleep) {
    val caption = when (content.onsetPhase) {
        OnsetPhase.PROJECTED -> stringResource(R.string.night_a_caption_if_asleep_by, content.onsetTimeLabel)
        OnsetPhase.ACTUAL -> stringResource(R.string.night_a_caption_asleep_since, content.onsetTimeLabel)
    }
    // Owner request: which alarm is coming and why, glanceable above the hero number - see AlarmModeHeader.kt.
    val subtitle = when {
        content.alarmAlreadyRang -> stringResource(R.string.night_a_alarm_already_rang)
        content.isDeadlineOnly -> stringResource(R.string.night_a_deadline_only_note)
        else -> stringResource(R.string.night_a_sleep_length, content.sleepLengthHoursLabel)
    }
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(ScreenContentGap)) {
        AlarmModeHeader(modeLabel = content.modeLabel, reasonText = content.reasonText)
        HeroNumeral(
            value = content.alarmTimeLabel,
            caption = caption,
            subtitle = subtitle,
        )
    }
}
