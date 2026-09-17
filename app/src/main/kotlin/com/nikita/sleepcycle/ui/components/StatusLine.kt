package com.nikita.sleepcycle.ui.components

// File purpose: the small dot-plus-text status line shown at the top of the Before-bed and Night screens.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nikita.sleepcycle.ui.theme.ConnectedDot
import com.nikita.sleepcycle.ui.theme.ErrorRed
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted
import com.nikita.sleepcycle.ui.theme.StatusDotSize

/** A coloured dot plus a short status sentence, e.g. "Band connected" or "Sync failed at 03:15". */
@Composable
fun StatusLine(text: String, ok: Boolean, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(StatusDotSize)
                .background(if (ok) ConnectedDot else ErrorRed, CircleShape),
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(8.dp))
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = NightOnSurfaceMuted)
    }
}
