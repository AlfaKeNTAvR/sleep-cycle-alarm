package com.nikita.sleepcycle.ui.screens.night

// File purpose: state A - going to bed, or still/again asleep with no completed awakening yet this stretch.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.AlarmModeHeader
import com.nikita.sleepcycle.ui.components.HeroNumeral
import com.nikita.sleepcycle.ui.state.NightScreenContent
import com.nikita.sleepcycle.ui.state.NightSubtitle
import com.nikita.sleepcycle.ui.state.OnsetPhase
import com.nikita.sleepcycle.ui.theme.ScreenContentGap

@Composable
fun GoingToBedContent(content: NightScreenContent.GoingToBedOrAsleep) {
    val caption = when (content.onsetPhase) {
        OnsetPhase.PROJECTED -> stringResource(R.string.night_a_caption_if_asleep_by, content.onsetTimeLabel)
        OnsetPhase.ACTUAL -> stringResource(R.string.night_a_caption_asleep_since, content.onsetTimeLabel)
    }
    // The precedence between these three is decided once in buildGoingToBedContent, not re-derived here - see
    // NightSubtitle's own doc (09/21 review's should-fix 7: the old three-way `when` over two raw booleans
    // lived only here, untestable, and hid that the booleans could both be true at once).
    // UNKNOWN (round 2 of the 09/21 review, should-fix 9): no subtitle line at all - see NightSubtitle's own
    // doc. Nothing latched to back up SLEEP_LENGTH's "X h of sleep" promise in this defensive-only branch.
    val subtitle = when (content.subtitle) {
        NightSubtitle.ALREADY_RANG -> stringResource(R.string.night_a_alarm_already_rang)
        NightSubtitle.DEADLINE_ONLY -> stringResource(R.string.night_a_deadline_only_note)
        NightSubtitle.SLEEP_LENGTH -> stringResource(R.string.night_a_sleep_length, content.sleepLengthHoursLabel)
        NightSubtitle.UNKNOWN -> null
    }
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(ScreenContentGap)) {
        // Owner request: which alarm is coming and why, glanceable above the hero number - see AlarmModeHeader.kt.
        // reasonText is already null in the already-rang case (see buildGoingToBedContent), so this needs no
        // extra check here.
        AlarmModeHeader(modeLabel = content.modeLabel, modeLabelTimeLabel = content.modeLabelTimeLabel, reasonText = content.reasonText)
        HeroNumeral(
            value = content.alarmTimeLabel,
            caption = caption,
            subtitle = subtitle,
            // 09/21 review's must-fix 3 decision: the deadline, restored as a plain caption line under the
            // hero time - see NightScreenContent.GoingToBedOrAsleep's own doc for why and when this is shown.
            detail = content.deadlineTimeLabel?.let { stringResource(R.string.night_a_deadline_caption, it) },
        )
    }
}
