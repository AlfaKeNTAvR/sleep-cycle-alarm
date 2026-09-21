package com.nikita.sleepcycle.ui.components

// File purpose: one plain navigation row for the Settings menu (X1) - a label and a chevron, nothing else. The
// owner's explicit instruction was that these rows carry no status text at all, so this composable is
// deliberately this thin; every status/checklist/warning that used to sit on Setup's one long scroll now lives
// inside the screen each row opens instead. Shared by all three Settings rows (Setup, Test connection, Debug)
// so a fourth row someday costs one call, not a new row implementation.

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import com.nikita.sleepcycle.ui.theme.ChevronArtSize
import com.nikita.sleepcycle.ui.theme.ChevronStrokeWidth
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted

/** One Settings row: [text] on the left, a disclosure chevron on the right, the whole card tappable. */
@Composable
fun SettingsMenuRow(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SettingsCard(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = text, style = MaterialTheme.typography.titleMedium)
            ChevronRightMark()
        }
    }
}

/** A plain ">" mark, drawn on a 24-unit grid like the toolbar icons (IconButtons.kt) rather than a Unicode glyph - this row has no click target of its own, so it skips that file's touch-target/content-description chrome. */
@Composable
private fun ChevronRightMark() {
    Canvas(modifier = Modifier.size(ChevronArtSize)) {
        val unit = size.width / 24f
        val stroke = Stroke(width = ChevronStrokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(8f * unit, 4f * unit)
            lineTo(16f * unit, 12f * unit)
            lineTo(8f * unit, 20f * unit)
        }
        drawPath(path = path, color = NightOnSurfaceMuted, style = stroke)
    }
}
