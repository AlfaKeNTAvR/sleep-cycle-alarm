package com.nikita.sleepcycle.ui.components

// File purpose: the big centered serif numeral used by every night-screen variant (a clock time or a duration),
// with an optional caption above and a subtitle below.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nikita.sleepcycle.ui.theme.AmberAccent
import com.nikita.sleepcycle.ui.theme.HeroNumeralStyle
import com.nikita.sleepcycle.ui.theme.NightOnBackground
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted

/** The night screen's centered hero: an optional small caption, the big serif numeral, and an optional subtitle. */
@Composable
fun HeroNumeral(
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    subtitle: String? = null,
    detail: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (caption != null) {
            Text(text = caption, style = MaterialTheme.typography.bodyLarge, color = NightOnSurfaceMuted, textAlign = TextAlign.Center)
        }
        Text(text = value, style = HeroNumeralStyle, color = AmberAccent, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Text(text = subtitle, style = MaterialTheme.typography.titleLarge, color = NightOnBackground, textAlign = TextAlign.Center)
        }
        if (detail != null) {
            Text(text = detail, style = MaterialTheme.typography.bodyMedium, color = NightOnSurfaceMuted, textAlign = TextAlign.Center)
        }
    }
}
