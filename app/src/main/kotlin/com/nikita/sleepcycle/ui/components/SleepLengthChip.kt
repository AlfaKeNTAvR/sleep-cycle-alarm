package com.nikita.sleepcycle.ui.components

// File purpose: one "Sleep up to" picker chip. An unavailable length is disabled and covered by a thin 45-degree
// hatch (no warning text), drawn with a Canvas clipped to the chip's rounded shape.

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
// matchParentSize() is a member extension of BoxScope itself (see the Box{} call below), not a top-level
// function, so it needs no import of its own.
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.nikita.sleepcycle.ui.theme.AmberAccent
import com.nikita.sleepcycle.ui.theme.ChipCornerRadius
import com.nikita.sleepcycle.ui.theme.ChipHeight
import com.nikita.sleepcycle.ui.theme.HatchLineSpacing
import com.nikita.sleepcycle.ui.theme.HatchLineWidth
import com.nikita.sleepcycle.ui.theme.CardBorderWidth
import com.nikita.sleepcycle.ui.theme.NightBorderDisabled
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceDisabled
import com.nikita.sleepcycle.ui.theme.NightOutline
import com.nikita.sleepcycle.ui.theme.OnAmberAccent
import kotlin.math.hypot

@Composable
fun SleepLengthChip(
    label: String,
    selected: Boolean,
    available: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(ChipCornerRadius)
    val backgroundColor = if (selected) AmberAccent else Color.Transparent
    val borderColor = when {
        selected -> AmberAccent
        available -> NightOutline
        else -> NightBorderDisabled
    }
    val textColor = when {
        selected -> OnAmberAccent
        available -> MaterialTheme.colorScheme.onSurface
        else -> NightOnSurfaceDisabled
    }
    Box(
        modifier = modifier
            .heightIn(min = ChipHeight)
            .clip(shape)
            .border(CardBorderWidth, borderColor, shape)
            .background(backgroundColor, shape)
            .clickable(enabled = available, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (!available) {
            // matchParentSize (not fillMaxSize): fillMaxSize expands to the loose max height the Row/Box chain
            // passes down from the screen above, which made the whole chip - not just the hatch - grow to fill
            // the card's remaining vertical space. matchParentSize sizes strictly to the Box's own size, which
            // is otherwise driven only by the label Text and the ChipHeight minimum, so the hatch can never
            // influence the chip's measured size - purely a decoration, as intended.
            HatchOverlay(color = NightOnSurfaceDisabled, modifier = Modifier.matchParentSize())
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

/** Thin "/" hatch lines at 45 degrees, [HatchLineSpacing] apart, [HatchLineWidth] thick - covers whatever it is sized to. */
@Composable
private fun HatchOverlay(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val spacingPx = HatchLineSpacing.toPx()
        val strokePx = HatchLineWidth.toPx()
        val diagonal = hypot(size.width, size.height)
        rotate(degrees = 45f) {
            var x = -diagonal
            while (x < diagonal) {
                drawLine(
                    color = color,
                    start = Offset(x, -diagonal),
                    end = Offset(x, diagonal),
                    strokeWidth = strokePx,
                )
                x += spacingPx
            }
        }
    }
}
