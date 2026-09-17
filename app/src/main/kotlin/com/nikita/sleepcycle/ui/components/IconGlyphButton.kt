package com.nikita.sleepcycle.ui.components

// File purpose: a minimal icon button (a single glyph, e.g. a gear or a list mark) at the accessible touch-target
// size, without pulling in a Material icon library the app does not otherwise need.

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.nikita.sleepcycle.ui.theme.MinTouchTarget
import com.nikita.sleepcycle.ui.theme.NightOnBackground

/** A single-glyph icon button (e.g. "⚙" for settings) at the minimum accessible touch target size. */
@Composable
fun IconGlyphButton(glyph: String, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(MinTouchTarget)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = false, radius = MinTouchTarget / 2),
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, style = MaterialTheme.typography.titleLarge, color = NightOnBackground)
    }
}
