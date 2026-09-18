package com.nikita.sleepcycle.ui.components

// File purpose: a single-line amber warning, e.g. "nothing on the phone will ring tonight" (item 4). Unlike
// DebugBanner (a full-width filled block for simulated-night switches), this is plain amber text: worth
// noticing but not shouting, and it never blocks the action below it.

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nikita.sleepcycle.ui.theme.AmberAccent

/** One line of amber warning text. */
@Composable
fun AmberWarningLine(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = AmberAccent, modifier = modifier)
}
