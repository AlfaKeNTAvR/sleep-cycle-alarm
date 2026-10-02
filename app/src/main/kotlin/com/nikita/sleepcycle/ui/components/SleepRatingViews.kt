package com.nikita.sleepcycle.ui.components

// File purpose: the sleep rating's three looks (owner spec, 2026-10-02): the morning report's Good / Okay / Bad
// faces, the Past night screen's pill buttons, and the Logs list's small chips. Each rating keeps one color
// everywhere: green, amber, red.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikita.sleepcycle.night.SleepRating
import com.nikita.sleepcycle.night.sleepRatingLabelRes
import com.nikita.sleepcycle.ui.theme.NightOnBackground
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceDisabled
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted
import com.nikita.sleepcycle.ui.theme.NightOutline
import com.nikita.sleepcycle.ui.theme.RatingBad
import com.nikita.sleepcycle.ui.theme.RatingGood
import com.nikita.sleepcycle.ui.theme.RatingOkay

private val RatingGap = 10.dp
private val FaceButtonCornerRadius = 16.dp
private val FaceButtonPadding = 12.dp
private val FaceSize = 44.dp
private val FaceStrokeWidth = 2.5.dp
private val PillHeight = 44.dp
private val PillCornerRadius = 22.dp
private val ChipCornerRadius = 10.dp
private val ChipHorizontalPadding = 8.dp
private val ChipVerticalPadding = 2.dp
private val ChipTextSize = 12.sp
private val UnselectedBorderWidth = 1.dp
private val SelectedBorderWidth = 2.dp
/** The selected button's tint: its rating color at about 13% opacity, as in the design. */
private const val SELECTED_FILL_ALPHA = 0.13f

/** The one color each rating has everywhere. */
fun sleepRatingColor(rating: SleepRating): Color = when (rating) {
    SleepRating.GOOD -> RatingGood
    SleepRating.OKAY -> RatingOkay
    SleepRating.BAD -> RatingBad
}

/** The morning report's three faces. Once one is picked the other two dim; tapping another changes it. */
@Composable
fun SleepRatingFaces(selected: SleepRating?, onPick: (SleepRating) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RatingGap)) {
        SleepRating.entries.forEach { rating ->
            val isSelected = rating == selected
            val dimmed = selected != null && !isSelected
            val color = if (dimmed) NightOnSurfaceDisabled else sleepRatingColor(rating)
            val label = stringResource(sleepRatingLabelRes(rating))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(FaceButtonCornerRadius))
                    .background(if (isSelected) color.copy(alpha = SELECTED_FILL_ALPHA) else Color.Transparent)
                    .border(BorderStroke(UnselectedBorderWidth, if (isSelected) color else NightOutline), RoundedCornerShape(FaceButtonCornerRadius))
                    .clickable(role = Role.Button) { onPick(rating) }
                    .semantics { this.selected = isSelected; contentDescription = label }
                    .padding(FaceButtonPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Canvas(modifier = Modifier.size(FaceSize)) { drawFace(rating, color) }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        dimmed -> NightOnSurfaceDisabled
                        isSelected -> color
                        else -> NightOnBackground
                    },
                )
            }
        }
    }
}

/** A round face on a 48-unit grid: outline, two eyes, and a smile, a straight line or a frown. */
private fun DrawScope.drawFace(rating: SleepRating, color: Color) {
    val unit = size.width / 48f
    val stroke = Stroke(width = FaceStrokeWidth.toPx(), cap = StrokeCap.Round)
    drawCircle(color = color, radius = 20f * unit, style = stroke)
    drawCircle(color = color, radius = 1.6f * unit, center = Offset(17f * unit, 20f * unit))
    drawCircle(color = color, radius = 1.6f * unit, center = Offset(31f * unit, 20f * unit))
    val mouth = Path().apply {
        when (rating) {
            SleepRating.GOOD -> { moveTo(15f * unit, 29f * unit); quadraticTo(24f * unit, 37f * unit, 33f * unit, 29f * unit) }
            SleepRating.OKAY -> { moveTo(16f * unit, 31f * unit); lineTo(32f * unit, 31f * unit) }
            SleepRating.BAD -> { moveTo(15f * unit, 34f * unit); quadraticTo(24f * unit, 26f * unit, 33f * unit, 34f * unit) }
        }
    }
    drawPath(path = mouth, color = color, style = stroke)
}

/** Past night's row of three pill buttons for one rating: the picked one outlined and tinted in its color. */
@Composable
fun SleepRatingPills(selected: SleepRating?, onPick: (SleepRating) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SleepRating.entries.forEach { rating ->
            val isSelected = rating == selected
            val color = sleepRatingColor(rating)
            val shape = RoundedCornerShape(PillCornerRadius)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(PillHeight)
                    .clip(shape)
                    .background(if (isSelected) color.copy(alpha = SELECTED_FILL_ALPHA) else Color.Transparent)
                    .border(
                        BorderStroke(if (isSelected) SelectedBorderWidth else UnselectedBorderWidth, if (isSelected) color else NightOutline),
                        shape,
                    )
                    .clickable(role = Role.Button) { onPick(rating) }
                    .semantics { this.selected = isSelected },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(sleepRatingLabelRes(rating)),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) color else NightOnSurfaceMuted,
                )
            }
        }
    }
}

/** A Logs row's small chip for one rating. */
@Composable
fun SleepRatingChip(rating: SleepRating) {
    val color = sleepRatingColor(rating)
    Text(
        text = stringResource(sleepRatingLabelRes(rating)),
        fontSize = ChipTextSize,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(ChipCornerRadius))
            .background(color.copy(alpha = SELECTED_FILL_ALPHA))
            .padding(horizontal = ChipHorizontalPadding, vertical = ChipVerticalPadding),
    )
}
