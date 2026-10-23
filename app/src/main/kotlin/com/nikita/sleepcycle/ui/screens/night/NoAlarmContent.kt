package com.nikita.sleepcycle.ui.screens.night

// File purpose: PER-150 - a No-alarm night's centred block: "No alarm" where the alarm name and time would be,
// and "Tracking your sleep." where the countdown would be. Same two-line shape as NextAlarmContent, no time.

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
import com.nikita.sleepcycle.ui.theme.AmberAccent
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted

@Composable
fun NoAlarmContent() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.night_no_alarm_title),
            style = MaterialTheme.typography.headlineMedium,
            color = AmberAccent,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.night_no_alarm_detail),
            style = MaterialTheme.typography.bodyLarge,
            color = NightOnSurfaceMuted,
            textAlign = TextAlign.Center,
        )
    }
}
