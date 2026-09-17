package com.nikita.sleepcycle.ui.components

// File purpose: a labelled row with a trailing switch, used for the deadline and phone-backup toggles.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.nikita.sleepcycle.ui.theme.AmberAccent
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted
import com.nikita.sleepcycle.ui.theme.NightThumbOff
import com.nikita.sleepcycle.ui.theme.NightTrackOff
import com.nikita.sleepcycle.ui.theme.OnAmberAccent

/** A title, an optional description line, and a trailing switch, styled to match the amber-accent theme. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else NightOnSurfaceMuted,
            )
            if (description != null) {
                Text(text = description, style = MaterialTheme.typography.bodyMedium, color = NightOnSurfaceMuted)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.semantics { this.contentDescription = contentDescription },
            colors = SwitchDefaults.colors(
                checkedThumbColor = OnAmberAccent,
                checkedTrackColor = AmberAccent,
                checkedBorderColor = AmberAccent,
                uncheckedThumbColor = NightThumbOff,
                uncheckedTrackColor = NightTrackOff,
                uncheckedBorderColor = NightTrackOff,
            ),
        )
    }
}
