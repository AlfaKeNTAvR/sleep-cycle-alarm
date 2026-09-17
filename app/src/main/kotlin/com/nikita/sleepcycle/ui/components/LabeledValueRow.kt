package com.nikita.sleepcycle.ui.components

// File purpose: a "label ... value" row (e.g. "Band alarm    07:15"), used inside SettingsCard for alarm-time
// readouts, with an optional thin divider below for stacking several inside one card.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.nikita.sleepcycle.ui.theme.DividerThickness
import com.nikita.sleepcycle.ui.theme.NightOnBackground
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted
import com.nikita.sleepcycle.ui.theme.NightSurfaceBorder
import com.nikita.sleepcycle.ui.theme.SmallNumeralStyle

/** One "label ... value" row, the value in the small serif numeral style. */
@Composable
fun LabeledValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = NightOnSurfaceMuted)
        Text(text = value, style = SmallNumeralStyle, color = NightOnBackground)
    }
}

/** A thin horizontal divider, for separating stacked [LabeledValueRow]s inside one card. */
@Composable
fun CardDivider(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxWidth()
            .height(DividerThickness)
            .background(NightSurfaceBorder),
    )
}
