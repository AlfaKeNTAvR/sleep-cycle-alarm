package com.nikita.sleepcycle.ui.components

// File purpose: the amber warning banner shown on Before bed, the Night screen and (as plain text) the night
// service notification whenever any debug switch is live (A1) - keyed to isAnyEnabled, never to fastNight
// alone, and naming every switch that is on in plain words so a simulated-data-only night warns just as
// loudly as a fast one.
//
// T12 (amended): SIMULATED_TIME's own label is no longer a static word - it carries the live "HH:mm[, Nx]"
// reading ([simulatedTimeValue], pre-formatted by com.nikita.sleepcycle.night.formatSimulatedTimeValue in the
// pure ui/state builder, since that layer has [now]/[zone]/the warp but no Android Context to resolve a string
// resource with). Null only when SIMULATED_TIME does not appear in [switches] at all (not warped).

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

/** The plain-word label for one active debug switch, from `strings.xml` (app-spec: user text lives there). [simulatedTimeValue] fills SIMULATED_TIME's own "SIMULATED %s" template; an unexpected null (should not happen - see this file's own header) falls back to an empty value rather than crashing. */
@Composable
private fun labelFor(switch: ActiveDebugSwitch, simulatedTimeValue: String?): String = when (switch) {
    ActiveDebugSwitch.SIMULATED_SLEEP_DATA -> stringResource(R.string.debug_switch_simulated_sleep_data)
    ActiveDebugSwitch.SIMULATED_TIME -> stringResource(R.string.debug_switch_simulated_time, simulatedTimeValue ?: "")
}

/** A full-width amber banner naming every switch in [switches], e.g. "SIMULATED SLEEP DATA  ·  SIMULATED 03:15, 60x". Renders nothing when [switches] is empty - callers should skip it entirely in that case. */
@Composable
fun DebugBanner(switches: List<ActiveDebugSwitch>, modifier: Modifier = Modifier, simulatedTimeValue: String? = null) {
    if (switches.isEmpty()) return
    val labels = switches.map { labelFor(it, simulatedTimeValue) }
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
