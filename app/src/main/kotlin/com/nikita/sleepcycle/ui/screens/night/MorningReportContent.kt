package com.nikita.sleepcycle.ui.screens.night

// File purpose: state D - the morning report, shown after the night is ended.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.CardDivider
import com.nikita.sleepcycle.ui.components.HeroNumeral
import com.nikita.sleepcycle.ui.components.SettingsCard
import com.nikita.sleepcycle.ui.state.NightScreenContent
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted
import com.nikita.sleepcycle.ui.theme.ScreenContentGap
import com.nikita.sleepcycle.ui.theme.SmallNumeralStyle

/** Item 6: a night with no recorded sleep stretch at all (e.g. ended seconds after it started) says so plainly instead of "you slept 0 minutes", and skips the now-empty per-stretch card. */
@Composable
fun MorningReportContent(content: NightScreenContent.MorningReport) {
    val noSleepRecorded = content.stretches.isEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(ScreenContentGap)) {
        if (noSleepRecorded) {
            Text(text = stringResource(R.string.night_morning_no_sleep), style = MaterialTheme.typography.headlineSmall)
        } else {
            val detail = content.wokeAtTimeLabel?.let { stringResource(R.string.night_morning_woke_at, it) }
            HeroNumeral(
                caption = stringResource(R.string.night_morning_greeting),
                value = content.totalSleepDurationLabel,
                detail = detail,
            )
            SettingsCard {
                content.stretches.forEachIndexed { index, stretch ->
                    if (index > 0) CardDivider()
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "${stretch.startTimeLabel} – ${stretch.endTimeLabel}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = NightOnSurfaceMuted,
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(text = stretch.durationLabel, style = SmallNumeralStyle)
                            Text(text = " (${stretch.cyclesLabel})", style = MaterialTheme.typography.bodyMedium, color = NightOnSurfaceMuted)
                        }
                    }
                }
            }
        }
        Text(text = stringResource(R.string.night_morning_log_saved), style = MaterialTheme.typography.bodySmall, color = NightOnSurfaceMuted)
    }
}
