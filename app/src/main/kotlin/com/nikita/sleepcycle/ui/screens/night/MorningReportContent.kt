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
import androidx.compose.ui.text.style.TextAlign
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.night.SleepRating
import com.nikita.sleepcycle.ui.components.SleepRatingFaces
import com.nikita.sleepcycle.ui.state.MorningRatingCard
import com.nikita.sleepcycle.ui.components.CardDivider
import com.nikita.sleepcycle.ui.components.HeroNumeral
import com.nikita.sleepcycle.ui.components.SettingsCard
import com.nikita.sleepcycle.ui.state.NightScreenContent
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted
import com.nikita.sleepcycle.ui.theme.ScreenContentGap
import com.nikita.sleepcycle.ui.theme.SmallNumeralStyle

/**
 * State D itself: the shared report body under this morning's greeting, then either the "How did you sleep?"
 * card ([rating] non-null - owner spec, 2026-10-02, it takes the place of the note) or the note that the log
 * was saved (rating switched off, or a night started before the rating existed).
 */
@Composable
fun MorningReportContent(content: NightScreenContent.MorningReport, rating: MorningRatingCard?, onRate: (SleepRating) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ScreenContentGap)) {
        MorningReportBody(content = content, caption = stringResource(R.string.night_morning_greeting))
        if (rating != null) {
            SettingsCard {
                Text(
                    text = stringResource(R.string.rating_morning_title),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                SleepRatingFaces(selected = rating.selected, onPick = onRate)
                Text(
                    text = stringResource(if (rating.selected == null) R.string.rating_morning_hint else R.string.rating_morning_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = NightOnSurfaceMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Text(
                text = stringResource(R.string.night_morning_log_saved),
                style = MaterialTheme.typography.bodySmall,
                color = NightOnSurfaceMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
            // P1: "no band data reached the app" is a different fact from "the band reported no sleep" - the
            // night of 2026-09-30 said the latter when every sync had failed.
            val noSleepText = if (content.noBandData) R.string.night_morning_no_band_data else R.string.night_morning_no_sleep
            Text(
                text = stringResource(noSleepText),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
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
                                text = "${stretch.startTimeLabel} - ${stretch.endTimeLabel}",
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
