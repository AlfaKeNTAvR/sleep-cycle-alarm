package com.nikita.sleepcycle.ui.screens.night

// File purpose: N1 - the one layout every live night uses, whichever mode the engine is in and whether the
// owner is asleep, awake or napping. Three lines, top to bottom: which alarm is coming, when it rings, how
// long until then. See NightScreenContent.NextAlarm's own doc for what this replaced and why.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.AlarmModeLabel
import com.nikita.sleepcycle.ui.components.HeroNumeral
import com.nikita.sleepcycle.ui.state.NightScreenContent

@Composable
fun NextAlarmContent(content: NightScreenContent.NextAlarm) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AlarmModeLabel(content.modeLabel)
        HeroNumeral(value = content.alarmTimeLabel, detail = detailLine(content))
    }
}

/**
 * The one line under the hero time. A live countdown whenever there is an armed alarm to count down to;
 * otherwise H8's already-rang time, which is the only other thing that state has to say. Both null - nothing
 * armed and nothing latched to explain it - leaves the line out rather than showing it blank: that is the
 * defensive fallback branch (a partially restored state file), not a state a normal night reaches.
 */
@Composable
private fun detailLine(content: NightScreenContent.NextAlarm): String? = when {
    content.countdownLabel != null -> stringResource(R.string.night_countdown, content.countdownLabel)
    content.rangAtTimeLabel != null -> stringResource(R.string.night_morning_alarm_rang_at, content.rangAtTimeLabel)
    else -> null
}
