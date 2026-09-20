package com.nikita.sleepcycle.ui.components

// File purpose: the app's four icon buttons - back, logs, settings and the three-dot menu - each drawn on a
// 24dp grid at the accessible touch-target size, sharing one piece of button chrome.
//
// Why they are drawn rather than typed: all four used to be Unicode characters set in the body font at 28sp
// ("←", "≡", "⚙", "⋮"). A typographic mark is thin, sits low against the title it labels, and changes shape
// with whatever font the phone falls back to - it reads as a character that wandered into the toolbar rather
// than as a control. The gear was the worst of them: U+2699 is emoji-adjacent, so some fonts render it in
// colour and some substitute a box. And "≡" is the identical-to sign, not a menu mark, with shorter and
// tighter lines than one. Drawing them means the stroke weight is chosen rather than inherited, and every
// mark matches the Medium weight the rest of the app's text uses.
//
// Settings is a pair of sliders rather than a gear, chosen by the owner: eight gear spokes crowd each other
// at 24dp and can read as a sun, while sliders stay legible at any size and share the logs mark's horizontal
// rhythm.

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nikita.sleepcycle.ui.theme.MinTouchTarget
import com.nikita.sleepcycle.ui.theme.NightOnBackground

/** The drawn mark's own box, the size Material icons use; the touch target around it is [MinTouchTarget]. */
private val IconArtSize = 24.dp

/** Matches the Medium weight of the app's text, so a mark sits at the same visual weight as the title beside it. */
private val IconStrokeWidth = 2.dp

/**
 * The shared chrome: the minimum accessible touch target, an unbounded ripple, and one content description
 * for the whole control. [draw] paints the mark on a 24-unit grid, centred; `unit` is one grid step in pixels.
 */
@Composable
private fun IconButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier,
    draw: DrawScope.(unit: Float, stroke: Stroke) -> Unit,
) {
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
        Canvas(modifier = Modifier.size(IconArtSize)) {
            val stroke = Stroke(width = IconStrokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            draw(size.width / 24f, stroke)
        }
    }
}

/** One straight run on the 24-unit grid, in the app's foreground colour. */
private fun DrawScope.drawGridLine(unit: Float, stroke: Stroke, x1: Float, y1: Float, x2: Float, y2: Float) {
    drawLine(
        color = NightOnBackground,
        start = Offset(x1 * unit, y1 * unit),
        end = Offset(x2 * unit, y2 * unit),
        strokeWidth = stroke.width,
        cap = stroke.cap,
    )
}

/** Back: a shaft plus a two-stroke head, pointing left. */
@Composable
fun BackArrowButton(contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(contentDescription, onClick, modifier) { unit, stroke ->
        drawGridLine(unit, stroke, 19f, 12f, 5f, 12f)
        val head = Path().apply {
            moveTo(11f * unit, 6f * unit)
            lineTo(5f * unit, 12f * unit)
            lineTo(11f * unit, 18f * unit)
        }
        drawPath(path = head, color = NightOnBackground, style = stroke)
    }
}

/** Logs: three evenly spaced full-width lines, which is what the "≡" was standing in for. */
@Composable
fun MenuLinesButton(contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(contentDescription, onClick, modifier) { unit, stroke ->
        drawGridLine(unit, stroke, 4f, 7f, 20f, 7f)
        drawGridLine(unit, stroke, 4f, 12f, 20f, 12f)
        drawGridLine(unit, stroke, 4f, 17f, 20f, 17f)
    }
}

/**
 * Settings: two slider tracks, each broken around its own knob. The break is a real gap rather than a knob
 * filled with the background colour, so the mark stays correct on whatever surface it is placed on.
 */
@Composable
fun SlidersButton(contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(contentDescription, onClick, modifier) { unit, stroke ->
        drawGridLine(unit, stroke, 4f, 8.5f, 5.6f, 8.5f)
        drawGridLine(unit, stroke, 12.4f, 8.5f, 20f, 8.5f)
        drawCircle(color = NightOnBackground, radius = 2.4f * unit, center = Offset(9f * unit, 8.5f * unit), style = stroke)
        drawGridLine(unit, stroke, 4f, 15.5f, 11.6f, 15.5f)
        drawGridLine(unit, stroke, 18.4f, 15.5f, 20f, 15.5f)
        drawCircle(color = NightOnBackground, radius = 2.4f * unit, center = Offset(15f * unit, 15.5f * unit), style = stroke)
    }
}

/** The row overflow menu: three filled dots at a chosen size and spacing. */
@Composable
fun DotsButton(contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(contentDescription, onClick, modifier) { unit, _ ->
        listOf(5f, 12f, 19f).forEach { y ->
            drawCircle(color = NightOnBackground, radius = 1.7f * unit, center = Offset(12f * unit, y * unit))
        }
    }
}
