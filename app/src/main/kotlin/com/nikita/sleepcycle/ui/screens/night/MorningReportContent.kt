package com.nikita.sleepcycle.ui.screens.night

// File purpose: state D - the morning report, shown after the night is ended.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** State D itself: the shared report body under this morning's greeting, plus the note that the log was saved. */
@Composable
fun MorningReportContent(content: NightScreenContent.MorningReport) {
    Column(verticalArrangement = Arrangement.spacedBy(ScreenContentGap)) {
        MorningReportBody(content = content, caption = stringResource(R.string.night_morning_greeting))
        Text(text = stringResource(R.string.night_morning_log_saved), style = MaterialTheme.typography.bodySmall, color = NightOnSurfaceMuted)
    }
}

/**
 * The report itself - the total slept and the per-stretch card - shared by state D and by the Logs screen's
 * past-night view, which shows the same night again later under its own [caption].
 *
 * Item 6: a night with no recorded sleep stretch at all (e.g. ended seconds after it started) says so plainly
 * instead of "you slept 0 minutes", and skips the now-empty per-stretch card. A night whose log kept the total
 * but not the stretch times ([NightScreenContent.MorningReport.stretchDetailRecorded] false) is a different
 * case: the total is real and is shown, only the card is left out - the caller says why.
 */
@Composable
fun MorningReportBody(content: NightScreenContent.MorningReport, caption: String) {
    val noSleepRecorded = content.stretchDetailRecorded && content.stretches.isEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(ScreenContentGap)) {
        if (noSleepRecorded) {
            Text(text = stringResource(R.string.night_morning_no_sleep), style = MaterialTheme.typography.headlineSmall)
        } else {
            val detail = content.wokeAtTimeLabel?.let { stringResource(R.string.night_morning_woke_at, it) }
            HeroNumeral(
                caption = caption,
                value = content.totalSleepDurationLabel,
                detail = detail,
            )
            if (content.stretchDetailRecorded) {
                SettingsCard {
                    content.stretches.forEachIndexed { index, stretch ->
                        if (index > 0) CardDivider()
                        // The three texts here are three different sizes, so bottom-aligning their boxes made the
                        // small ones sit visibly low - owner-reported. One shared baseline instead.
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                modifier = Modifier.alignByBaseline(),
                                text = "${stretch.startTimeLabel} – ${stretch.endTimeLabel}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = NightOnSurfaceMuted,
                            )
                            Row(modifier = Modifier.alignByBaseline()) {
                                Text(modifier = Modifier.alignByBaseline(), text = stretch.durationLabel, style = SmallNumeralStyle)
                                Text(
                                    modifier = Modifier.alignByBaseline(),
                                    text = " (${stretch.cyclesLabel})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NightOnSurfaceMuted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
