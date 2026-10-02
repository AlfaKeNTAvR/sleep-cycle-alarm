package com.nikita.sleepcycle.ui.screens

// File purpose: the Settings screen. X1 made it a short navigation menu (Setup, Debug); owner spec 2026-10-02
// (option A) adds the owner's own choices above that menu, in titled sections with no descriptions: After the
// alarm, Bedtime audio, Sleep rating, then More (the old Setup and Debug rows). Every change is saved at once;
// there is no Done button (W17). Limits and defaults live in night/UserSettings.kt.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikita.sleepcycle.BuildConfig
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.night.AfterAlarmSettings
import com.nikita.sleepcycle.night.BedtimeAudioSettings
import com.nikita.sleepcycle.night.SettingStepper
import com.nikita.sleepcycle.night.SleepRatingSettings
import com.nikita.sleepcycle.night.canStepSetting
import com.nikita.sleepcycle.night.stepSetting
import com.nikita.sleepcycle.ui.components.AppTimePickerDialog
import com.nikita.sleepcycle.ui.components.BackArrowButton
import com.nikita.sleepcycle.ui.components.CardDivider
import com.nikita.sleepcycle.ui.components.ScreenContainer
import com.nikita.sleepcycle.ui.components.SettingsCard
import com.nikita.sleepcycle.ui.components.SettingsMenuRow
import com.nikita.sleepcycle.ui.components.ToggleRow
import com.nikita.sleepcycle.ui.state.SettingsMenuEntry
import com.nikita.sleepcycle.ui.state.settingsMenuEntries
import com.nikita.sleepcycle.ui.theme.AmberAccent
import com.nikita.sleepcycle.ui.theme.CardNarrowPadding
import com.nikita.sleepcycle.ui.theme.MinTouchTarget
import com.nikita.sleepcycle.ui.theme.NightOnBackground
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceDisabled
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted
import com.nikita.sleepcycle.ui.theme.NightOutline
import java.time.LocalTime

private val SectionGap = 8.dp
private val SectionTitleLetterSpacing = 1.sp
private val StepperButtonSize = 40.dp
private val StepperValueMinWidth = 64.dp
private val TimeButtonCornerRadius = 12.dp
private val TimeButtonPadding = 10.dp

/**
 * The Settings screen. [isDebugBuild] defaults to the real [BuildConfig.DEBUG] flag and is a parameter only so a
 * preview/test can force either list; [settingsMenuEntries] (X4) is what actually decides whether Debug shows.
 * A row whose switch above it is off stays visible but dimmed and inert (Starting volume under a fade that is
 * off; Ask again later and Ask at under a rating that is off).
 *
 * W17 (owner request): there is no "Done" button. X1 gave this screen one, but it went exactly where the back
 * arrow already goes, so it was a second control for a job one already did.
 *
 * W12/W14 (owner request): the connection test is not here. It was briefly a row opening a screen of its own,
 * then a button on this menu, and now sits back inside Setup with the readiness checklist it reports against.
 */
@Composable
fun SettingsScreen(
    afterAlarm: AfterAlarmSettings,
    bedtimeAudio: BedtimeAudioSettings,
    sleepRating: SleepRatingSettings,
    onAfterAlarmChange: (AfterAlarmSettings) -> Unit,
    onBedtimeAudioChange: (BedtimeAudioSettings) -> Unit,
    onSleepRatingChange: (SleepRatingSettings) -> Unit,
    onOpenSetup: () -> Unit,
    onOpenDebug: () -> Unit,
    onBack: () -> Unit,
    isDebugBuild: Boolean = BuildConfig.DEBUG,
) {
    ScreenContainer(scrollable = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackArrowButton(
                contentDescription = stringResource(R.string.content_description_back),
                onClick = onBack,
            )
            Text(text = stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
        }

        SettingsSection(stringResource(R.string.settings_section_after_alarm)) {
            StepperRow(
                label = stringResource(R.string.settings_nudge),
                stepper = SettingStepper.NUDGE_MINUTES,
                value = afterAlarm.nudgeMinutes,
                valueText = stringResource(R.string.settings_minutes_value, afterAlarm.nudgeMinutes),
                onValueChange = { onAfterAlarmChange(afterAlarm.copy(nudgeMinutes = it)) },
            )
            CardDivider()
            StepperRow(
                label = stringResource(R.string.settings_nap_length),
                stepper = SettingStepper.NAP_MINUTES,
                value = afterAlarm.napMinutes,
                valueText = stringResource(R.string.settings_minutes_value, afterAlarm.napMinutes),
                onValueChange = { onAfterAlarmChange(afterAlarm.copy(napMinutes = it)) },
            )
        }

        SettingsSection(stringResource(R.string.settings_section_bedtime_audio)) {
            SwitchRow(
                label = stringResource(R.string.settings_fade),
                checked = bedtimeAudio.fadeEnabled,
                onCheckedChange = { onBedtimeAudioChange(bedtimeAudio.copy(fadeEnabled = it)) },
            )
            CardDivider()
            StepperRow(
                label = stringResource(R.string.settings_fade_start),
                stepper = SettingStepper.FADE_START_PERCENT,
                value = bedtimeAudio.fadeStartPercent,
                valueText = stringResource(R.string.settings_percent_value, bedtimeAudio.fadeStartPercent),
                onValueChange = { onBedtimeAudioChange(bedtimeAudio.copy(fadeStartPercent = it)) },
                enabled = bedtimeAudio.fadeEnabled,
            )
            CardDivider()
            SwitchRow(
                label = stringResource(R.string.settings_pause_when_asleep),
                checked = bedtimeAudio.pauseWhenAsleep,
                onCheckedChange = { onBedtimeAudioChange(bedtimeAudio.copy(pauseWhenAsleep = it)) },
            )
        }

        SettingsSection(stringResource(R.string.settings_section_rating)) {
            SwitchRow(
                label = stringResource(R.string.settings_rate_night),
                checked = sleepRating.enabled,
                onCheckedChange = { onSleepRatingChange(sleepRating.copy(enabled = it)) },
            )
            CardDivider()
            SwitchRow(
                label = stringResource(R.string.settings_ask_again_later),
                checked = sleepRating.askAgainLater,
                onCheckedChange = { onSleepRatingChange(sleepRating.copy(askAgainLater = it)) },
                enabled = sleepRating.enabled,
            )
            CardDivider()
            TimeRow(
                label = stringResource(R.string.settings_ask_at),
                time = sleepRating.askAt,
                onTimeChange = { onSleepRatingChange(sleepRating.copy(askAt = it)) },
                enabled = sleepRating.enabled && sleepRating.askAgainLater,
            )
        }

        SettingsSection(stringResource(R.string.settings_section_more)) {
            settingsMenuEntries(isDebugBuild).forEachIndexed { index, entry ->
                if (index > 0) CardDivider()
                SettingsMenuRow(
                    text = stringResource(settingsMenuEntryLabelRes(entry)),
                    onClick = settingsMenuEntryAction(entry, onOpenSetup, onOpenDebug),
                )
            }
        }
    }
}

/** A small upper-case section title over one card holding that section's rows. */
@Composable
private fun SettingsSection(title: String, rows: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SectionGap)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            letterSpacing = SectionTitleLetterSpacing,
            color = NightOnSurfaceMuted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        SettingsCard(contentPadding = CardNarrowPadding) { rows() }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    ToggleRow(
        title = label,
        checked = checked,
        onCheckedChange = onCheckedChange,
        contentDescription = label,
        enabled = enabled,
        modifier = Modifier.heightIn(min = MinTouchTarget),
    )
}

/** A label, then minus / value / plus. A button that would not change the value (at the limit) is disabled. */
@Composable
private fun StepperRow(
    label: String,
    stepper: SettingStepper,
    value: Int,
    valueText: String,
    onValueChange: (Int) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.titleMedium, color = if (enabled) NightOnBackground else NightOnSurfaceMuted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StepperButton(
                text = "−",
                contentDescription = stringResource(R.string.content_description_less),
                enabled = enabled && canStepSetting(stepper, value, -1),
                onClick = { onValueChange(stepSetting(stepper, value, -1)) },
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (enabled) AmberAccent else NightOnSurfaceDisabled,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = StepperValueMinWidth),
            )
            StepperButton(
                text = "+",
                contentDescription = stringResource(R.string.content_description_more),
                enabled = enabled && canStepSetting(stepper, value, +1),
                onClick = { onValueChange(stepSetting(stepper, value, +1)) },
            )
        }
    }
}

@Composable
private fun StepperButton(text: String, contentDescription: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .size(StepperButtonSize)
            .clip(CircleShape)
            .border(BorderStroke(1.dp, NightOutline), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = MaterialTheme.typography.titleLarge, color = if (enabled) NightOnBackground else NightOnSurfaceDisabled)
    }
}

/** A label, then the time in a small bordered button that opens the app's time picker. */
@Composable
private fun TimeRow(label: String, time: LocalTime, onTimeChange: (LocalTime) -> Unit, enabled: Boolean) {
    var showPicker by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.titleMedium, color = if (enabled) NightOnBackground else NightOnSurfaceMuted)
        Text(
            text = "%02d:%02d".format(time.hour, time.minute),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (enabled) AmberAccent else NightOnSurfaceDisabled,
            modifier = Modifier
                .clip(RoundedCornerShape(TimeButtonCornerRadius))
                .border(BorderStroke(1.dp, NightOutline), RoundedCornerShape(TimeButtonCornerRadius))
                .clickable(enabled = enabled, role = Role.Button) { showPicker = true }
                .padding(TimeButtonPadding),
        )
    }
    if (showPicker) AppTimePickerDialog(time = time, onTimeChange = onTimeChange, onDismiss = { showPicker = false })
}

/** Row label per entry - reuses the same strings Setup and Debug already show as their own screen titles (X6), rather than adding near-duplicates. */
private fun settingsMenuEntryLabelRes(entry: SettingsMenuEntry): Int = when (entry) {
    SettingsMenuEntry.SETUP -> R.string.setup_title
    SettingsMenuEntry.DEBUG -> R.string.setup_debug_row_label
}

private fun settingsMenuEntryAction(
    entry: SettingsMenuEntry,
    onOpenSetup: () -> Unit,
    onOpenDebug: () -> Unit,
): () -> Unit = when (entry) {
    SettingsMenuEntry.SETUP -> onOpenSetup
    SettingsMenuEntry.DEBUG -> onOpenDebug
}
