package com.nikita.sleepcycle.ui.components

// File purpose: the Settings screen's look (owner spec, 2026-10-02, option A) - a small upper-case title over
// one card of rows with no descriptions - shared by Settings, Debug and the Night screen's Simulation card,
// which the owner asked to look the same way.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikita.sleepcycle.ui.theme.AmberAccent
import com.nikita.sleepcycle.ui.theme.CardNarrowPadding
import com.nikita.sleepcycle.ui.theme.MinTouchTarget
import com.nikita.sleepcycle.ui.theme.NightOnBackground
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceDisabled
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted
import com.nikita.sleepcycle.ui.theme.NightOutline

private val SectionGap = 8.dp
private val SectionTitleLetterSpacing = 1.sp
private val RowButtonCornerRadius = 12.dp
private val RowButtonPadding = 10.dp

/** A small upper-case section title over one card holding that section's rows. */
@Composable
fun SettingsSection(title: String, modifier: Modifier = Modifier, rows: @Composable () -> Unit) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(SectionGap)) {
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

/** A label and a switch, one touch target tall. */
@Composable
fun SettingsSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    ToggleRow(
        title = label,
        checked = checked,
        onCheckedChange = onCheckedChange,
        contentDescription = label,
        enabled = enabled,
        modifier = Modifier.heightIn(min = MinTouchTarget),
    )
}

/** A label, then a small bordered amber button on the right - the same button the Settings screen's time row uses. */
@Composable
fun SettingsButtonRow(label: String, buttonText: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.titleMedium, color = if (enabled) NightOnBackground else NightOnSurfaceMuted)
        SettingsRowButton(text = buttonText, onClick = onClick, enabled = enabled)
    }
}

/** The small bordered amber button at the right end of a settings row. */
@Composable
private fun SettingsRowButton(text: String, onClick: () -> Unit, enabled: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = if (enabled) AmberAccent else NightOnSurfaceDisabled,
        modifier = Modifier
            .clip(RoundedCornerShape(RowButtonCornerRadius))
            .border(BorderStroke(1.dp, NightOutline), RoundedCornerShape(RowButtonCornerRadius))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(RowButtonPadding),
    )
}
