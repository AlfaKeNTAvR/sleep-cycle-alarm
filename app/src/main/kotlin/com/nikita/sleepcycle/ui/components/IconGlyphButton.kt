package com.nikita.sleepcycle.ui.components

// File purpose: minimal icon buttons at the accessible touch-target size, without pulling in a Material icon
// library the app does not otherwise need. Two kinds share one piece of button chrome: a single typographic
// glyph (the gear, the list mark, the three-dot menu) and the back arrow, which is drawn rather than typed.
//
// Why the back arrow is drawn: it used to be the Unicode character "←" set in the body font at 28sp. A
// typographic arrow is thin, sits low against the title it labels, and changes shape with whatever font the
// phone falls back to - it reads as a character that wandered into the toolbar rather than as a control.
// Drawing it means the stroke weight is chosen rather than inherited, and it matches the Medium weight the
// rest of the app's text uses. The other three glyphs are still typed; they should follow eventually.

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nikita.sleepcycle.ui.theme.IconGlyphStyle
import com.nikita.sleepcycle.ui.theme.MinTouchTarget
import com.nikita.sleepcycle.ui.theme.NightOnBackground

/** The drawn icon's own box, the size Material icons use; the touch target around it is [MinTouchTarget]. */
private val IconArtSize = 24.dp

/** Matches the Medium weight of the app's text, so the arrow sits at the same visual weight as the title beside it. */
private val IconStrokeWidth = 2.dp

/**
 * The shared chrome: the minimum accessible touch target, an unbounded ripple, and one content description
 * for the whole control. [content] draws the mark itself, centred.
 */
@Composable
private fun IconButtonBox(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
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
        content()
    }
}

/** A single-glyph icon button (e.g. "⚙" for settings) at the minimum accessible touch target size. */
@Composable
fun IconGlyphButton(glyph: String, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButtonBox(contentDescription, onClick, modifier) {
        Text(text = glyph, style = IconGlyphStyle, color = NightOnBackground)
    }
}

/**
 * The back button: a left-pointing arrow drawn as a shaft plus a two-stroke head, on the same 24dp grid
 * Material icons use, with round caps so it reads as drawn rather than clipped. Coordinates are in that
 * grid's units and scaled to the box, so the shape is identical at any density.
 */
@Composable
fun BackArrowButton(contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButtonBox(contentDescription, onClick, modifier) {
        Canvas(modifier = Modifier.size(IconArtSize)) {
            val unit = size.width / 24f
            fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
            val stroke = Stroke(
                width = IconStrokeWidth.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
            drawLine(
                color = NightOnBackground,
                start = point(19f, 12f),
                end = point(5f, 12f),
                strokeWidth = stroke.width,
                cap = stroke.cap,
            )
            val head = Path().apply {
                moveTo(point(11f, 6f).x, point(11f, 6f).y)
                lineTo(point(5f, 12f).x, point(5f, 12f).y)
                lineTo(point(11f, 18f).x, point(11f, 18f).y)
            }
            drawPath(path = head, color = NightOnBackground, style = stroke)
        }
    }
}
