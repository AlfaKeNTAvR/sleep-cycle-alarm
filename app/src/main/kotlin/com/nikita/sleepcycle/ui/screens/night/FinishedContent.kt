package com.nikita.sleepcycle.ui.screens.night

// File purpose: the FINISHED amendment - the night is over (deadline passed, or woken at the alarm) but not
// ended yet; offers "End night" (wired up by the caller, see NightScreen.kt).

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.LabeledValueRow
import com.nikita.sleepcycle.ui.components.SettingsCard
import com.nikita.sleepcycle.ui.state.NightScreenContent
import com.nikita.sleepcycle.ui.theme.AmberAccent
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted
import com.nikita.sleepcycle.ui.theme.ScreenContentGap

@Composable
fun FinishedContent(content: NightScreenContent.NightFinished) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.night_finished_title),
            style = MaterialTheme.typography.headlineMedium,
            color = AmberAccent,
            textAlign = TextAlign.Center,
        )
        Text(
            text = content.reasonText,
            style = MaterialTheme.typography.bodyLarge,
            color = NightOnSurfaceMuted,
            textAlign = TextAlign.Center,
        )
        // The phone alarm stays armed even after the night is FINISHED but not yet ended (D4).
        if (content.phoneSafetyAlarmTimeLabel != null) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = ScreenContentGap)) {
                SettingsCard {
                    LabeledValueRow(label = stringResource(R.string.night_phone_safety_alarm), value = content.phoneSafetyAlarmTimeLabel)
                }
            }
        }
    }
}
