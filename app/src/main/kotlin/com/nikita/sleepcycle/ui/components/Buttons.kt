package com.nikita.sleepcycle.ui.components

// File purpose: the two button styles used throughout: a filled amber primary action, and an outlined secondary
// (or destructive-ish "stop") action.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nikita.sleepcycle.ui.theme.AmberAccent
import com.nikita.sleepcycle.ui.theme.CardBorderWidth
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceDisabled
import com.nikita.sleepcycle.ui.theme.NightOutline
import com.nikita.sleepcycle.ui.theme.NightSurface
import com.nikita.sleepcycle.ui.theme.OnAmberAccent
import com.nikita.sleepcycle.ui.theme.PrimaryButtonCornerRadius
import com.nikita.sleepcycle.ui.theme.PrimaryButtonHeight
import com.nikita.sleepcycle.ui.theme.SecondaryButtonCornerRadius
import com.nikita.sleepcycle.ui.theme.SecondaryButtonHeight

/** The main filled action of a screen ("Start night", "I'm up, end night", "Done"). */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(PrimaryButtonHeight),
        shape = RoundedCornerShape(PrimaryButtonCornerRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = AmberAccent,
            contentColor = OnAmberAccent,
            disabledContainerColor = NightSurface,
            disabledContentColor = NightOnSurfaceDisabled,
        ),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** An outlined secondary action ("Stop night", "Cancel"). */
@Composable
fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(SecondaryButtonHeight),
        shape = RoundedCornerShape(SecondaryButtonCornerRadius),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        border = BorderStroke(CardBorderWidth, NightOutline),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}
