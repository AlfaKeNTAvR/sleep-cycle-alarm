package com.nikita.sleepcycle.ui.components

// File purpose: owner request - "information on the screens is misleading, especially when it should indicate
// whether it's a nap mode or whether it's a full mode... it's confusing sometimes to understand why is the
// alarm beeping". A small, glanceable block shown above each active night-screen state's HeroNumeral: which
// alarm is coming next (the same "Morning alarm"/"Nap alarm" wording the ring screen and notification already
// use - see AlarmLabel.kt), and underneath it, in the engine's own words, why.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.alarm.AlarmLabel
import com.nikita.sleepcycle.alarm.alarmLabelNameRes
import com.nikita.sleepcycle.ui.theme.AmberAccent
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted

/**
 * Which alarm is coming next, and the engine's own one-sentence reason, centered above the screen's hero
 * number. [modeLabel] is null when nothing is armed (must-fix 1 of the 09/21 review) - shown as the plain
 * "no alarm armed" wording instead of naming an alarm that does not exist. [reasonText] is null when the
 * caller has nothing to say underneath (state A's already-rang case, should-fix 5 of the same review): the
 * second line is left out entirely rather than shown blank.
 */
@Composable
fun AlarmModeHeader(modeLabel: AlarmLabel?, reasonText: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = modeLabel?.let { stringResource(alarmLabelNameRes(it)) } ?: stringResource(R.string.night_no_alarm_armed),
            style = MaterialTheme.typography.titleMedium,
            color = AmberAccent,
            textAlign = TextAlign.Center,
        )
        if (reasonText != null) {
            Text(
                text = reasonText,
                style = MaterialTheme.typography.bodySmall,
                color = NightOnSurfaceMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}
