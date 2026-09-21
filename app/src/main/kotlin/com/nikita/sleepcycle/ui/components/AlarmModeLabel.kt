package com.nikita.sleepcycle.ui.components

// File purpose: owner request - "information on the screens is misleading, especially when it should indicate
// whether it's a nap mode or whether it's a full mode... it's confusing sometimes to understand why is the
// alarm beeping". One line above the night screen's hero time naming which alarm that time belongs to, in the
// same wording the ring screen and notification already use (see AlarmLabel.kt).
//
// N1 (owner decision, 2026-09-21) cut this down from the block it used to be. It carried the engine's own
// one-sentence reason underneath, and, for the out-of-bed nudge alone, that nudge's time beside the name -
// because the nudge was the one alarm whose time appeared nowhere else on screen. Neither is needed now: the
// hero number underneath is always this alarm's own time, the nudge included, and the reason sentence was one
// of the lines the owner found unreadable at 3am. It is also no longer amber: amber is the hero number's
// colour, and having both compete was the other half of the same complaint.

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.alarm.AlarmLabel
import com.nikita.sleepcycle.alarm.alarmLabelNameRes
import com.nikita.sleepcycle.ui.theme.NightOnBackground

/**
 * Which alarm the night screen's hero time belongs to. [modeLabel] is null when nothing is armed (must-fix 1
 * of the 09/21 review) - shown as the plain "no alarm armed" wording instead of naming an alarm that does not
 * exist, with the hero time underneath reading as a dash for the same reason.
 */
@Composable
fun AlarmModeLabel(modeLabel: AlarmLabel?, modifier: Modifier = Modifier) {
    Text(
        text = if (modeLabel == null) stringResource(R.string.night_no_alarm_armed) else stringResource(alarmLabelNameRes(modeLabel)),
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.titleLarge,
        color = NightOnBackground,
        textAlign = TextAlign.Center,
    )
}
