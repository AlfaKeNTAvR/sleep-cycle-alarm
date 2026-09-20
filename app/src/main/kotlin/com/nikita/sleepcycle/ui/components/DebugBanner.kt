package com.nikita.sleepcycle.ui.components

// File purpose: the amber warning banner shown on Before bed, the Night screen and (as plain text) the night
// service notification whenever any debug switch is live (A1) - keyed to isAnyEnabled, never to fastNight
// alone, and naming every switch that is on in plain words so a simulated-data-only night warns just as
// loudly as a fast one.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.night.ActiveDebugSwitch
import com.nikita.sleepcycle.ui.theme.AmberAccent
import com.nikita.sleepcycle.ui.theme.CardCornerRadius
import com.nikita.sleepcycle.ui.theme.OnAmberAccent

private const val DEBUG_BANNER_SEPARATOR = "  ·  "

/** The plain-word label for one active debug switch, from `strings.xml` (app-spec: user text lives there). */
@Composable
private fun labelFor(switch: ActiveDebugSwitch): String = when (switch) {
    ActiveDebugSwitch.SIMULATED_SLEEP_DATA -> stringResource(R.string.debug_switch_simulated_sleep_data)
    ActiveDebugSwitch.FAST_NIGHT -> stringResource(R.string.debug_switch_fast_night)
}

/** A full-width amber banner naming every switch in [switches], e.g. "SIMULATED SLEEP DATA  ·  FAST NIGHT". Renders nothing when [switches] is empty - callers should skip it entirely in that case. */
@Composable
fun DebugBanner(switches: List<ActiveDebugSwitch>, modifier: Modifier = Modifier) {
    if (switches.isEmpty()) return
    val labels = switches.map { labelFor(it) }
    Text(
        text = labels.joinToString(DEBUG_BANNER_SEPARATOR),
        style = MaterialTheme.typography.labelLarge,
        color = OnAmberAccent,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .background(AmberAccent, RoundedCornerShape(CardCornerRadius))
            .padding(vertical = 8.dp),
    )
}
