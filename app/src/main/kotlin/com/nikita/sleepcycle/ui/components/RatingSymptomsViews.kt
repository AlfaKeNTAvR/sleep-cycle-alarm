package com.nikita.sleepcycle.ui.components

// File purpose: the "why Okay or Bad" look (owner spec, 2026-10-04): the dialog of tappable symptom tiles -
// icon over name, at most three to a row, any number ticked - and the small line of ticked symptoms under a rating on
// the morning report and Past night, which reopens the dialog when tapped. All state lives in
// ui/state/RatingSymptomsDialog.kt; these only draw it and report taps.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.night.RatingMoment
import com.nikita.sleepcycle.night.RatingSymptom
import com.nikita.sleepcycle.night.sleepRatingLabelRes
import com.nikita.sleepcycle.ui.state.SymptomsDialogState
import com.nikita.sleepcycle.ui.theme.NightOnBackground
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted
import com.nikita.sleepcycle.ui.theme.NightOutline
import com.nikita.sleepcycle.ui.theme.NightSurface
import com.nikita.sleepcycle.ui.theme.SymptomIconIdle

private val DialogCornerRadius = 28.dp
private val DialogPadding = 24.dp
private val DialogContentGap = 16.dp
private val DialogTitleSize = 22.sp
private val TileGap = 10.dp
private val TileMinHeight = 104.dp
private val TileCornerRadius = 16.dp
private val TileBorderWidth = 2.dp
private val TilePadding = 8.dp
private val TileIconSize = 28.dp
private val TileLabelSize = 13.sp
private const val TILE_LABEL_MAX_LINES = 2
private val LineIconSize = 16.dp
private val LineItemGap = 12.dp
private val LineIconLabelGap = 4.dp
private val LineLabelSize = 12.sp
/** A ticked tile's tint: its rating color at about 13% opacity, the same as a picked rating face. */
private const val SELECTED_FILL_ALPHA = 0.13f

/**
 * The dialog itself: title per moment, "You picked Okay." under it, the tiles, then Save (with the count once
 * anything is ticked) and Skip. A tap outside or Back is the same as Skip.
 */
@Composable
fun RatingSymptomsDialog(state: SymptomsDialogState, onToggle: (RatingSymptom) -> Unit, onSave: () -> Unit, onSkip: () -> Unit) {
    val ratingColor = sleepRatingColor(state.rating)
    Dialog(onDismissRequest = onSkip) {
        Surface(shape = RoundedCornerShape(DialogCornerRadius), color = NightSurface) {
            Column(modifier = Modifier.padding(DialogPadding), verticalArrangement = Arrangement.spacedBy(DialogContentGap)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(
                            when (state.moment) {
                                RatingMoment.AFTER_END_NIGHT -> R.string.symptoms_title_morning
                                RatingMoment.LATER -> R.string.symptoms_title_later
                            }
                        ),
                        fontSize = DialogTitleSize,
                        fontWeight = FontWeight.Normal,
                        color = NightOnBackground,
                    )
                    Text(
                        text = stringResource(R.string.symptoms_subtitle, stringResource(sleepRatingLabelRes(state.rating))),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NightOnSurfaceMuted,
                    )
                }
                state.options.chunked(state.tilesPerRow).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                        row.forEach { symptom ->
                            SymptomTile(
                                symptom = symptom,
                                selected = symptom in state.selected,
                                selectedColor = ratingColor,
                                onToggle = { onToggle(symptom) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // A short last row keeps its tiles the same width as the full rows above it.
                        repeat(state.tilesPerRow - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PrimaryActionButton(
                        text = if (state.selected.isEmpty()) stringResource(R.string.symptoms_save)
                        else stringResource(R.string.symptoms_save_count, state.selected.size),
                        onClick = onSave,
                    )
                    TextButton(onClick = onSkip, colors = ButtonDefaults.textButtonColors(contentColor = NightOnSurfaceMuted)) {
                        Text(text = stringResource(R.string.symptoms_skip), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/** One tile: the icon over its name. Ticked, the border, icon and name take the rating's color over a faint tint of it. */
@Composable
private fun SymptomTile(symptom: RatingSymptom, selected: Boolean, selectedColor: Color, onToggle: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(TileCornerRadius)
    Column(
        modifier = modifier
            .heightIn(min = TileMinHeight)
            .clip(shape)
            .background(if (selected) selectedColor.copy(alpha = SELECTED_FILL_ALPHA) else Color.Transparent)
            .border(BorderStroke(TileBorderWidth, if (selected) selectedColor else NightOutline), shape)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(TilePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = symptomIcon(symptom),
            contentDescription = null,
            tint = if (selected) selectedColor else SymptomIconIdle,
            modifier = Modifier.size(TileIconSize),
        )
        Text(
            text = stringResource(symptomLabelRes(symptom)),
            fontSize = TileLabelSize,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) selectedColor else NightOnBackground,
            textAlign = TextAlign.Center,
            maxLines = TILE_LABEL_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The line under an Okay or Bad rating: each ticked symptom as a small icon and its name, wrapping onto more
 * lines as needed. With none ticked it says "Tick what felt off" instead. Tapping either opens the dialog.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RatingSymptomsLine(symptoms: List<RatingSymptom>, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val tappable = modifier.fillMaxWidth().clip(RoundedCornerShape(TileCornerRadius / 2)).clickable(role = Role.Button, onClick = onEdit)
    if (symptoms.isEmpty()) {
        Text(
            text = stringResource(R.string.symptoms_add),
            style = MaterialTheme.typography.bodySmall,
            color = NightOnSurfaceMuted,
            modifier = tappable.padding(vertical = 4.dp),
        )
        return
    }
    FlowRow(
        modifier = tappable.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(LineItemGap),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        symptoms.forEach { symptom ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LineIconLabelGap)) {
                Icon(imageVector = symptomIcon(symptom), contentDescription = null, tint = SymptomIconIdle, modifier = Modifier.size(LineIconSize))
                Text(text = stringResource(symptomLabelRes(symptom)), fontSize = LineLabelSize, color = NightOnSurfaceMuted)
            }
        }
    }
}
