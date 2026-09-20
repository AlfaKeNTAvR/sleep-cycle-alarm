package com.nikita.sleepcycle.ui.screens.night

// File purpose: the FINISHED amendment - the night is over (deadline passed, or D5's nap cap spent with none
// left) but not ended yet; offers "End night" (wired up by the caller, see NightScreen.kt). D3: band-detected
// wake alone no longer reaches this state - only the deadline, the nap cap, or the owner's own "I'm awake"
// (which ends the night outright, never landing here at all) do. F14: FINISHED never carries an alarm (D1's
// wakeAt is null by construction the moment mode becomes FINISHED, and F7 makes the night's real ending
// - cancelling the phone alarm and the tick alarm - happen on that same tick), so there is nothing to show
// beyond the engine's own reason.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.state.NightScreenContent
import com.nikita.sleepcycle.ui.theme.AmberAccent
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted

@Composable
fun FinishedContent(content: NightScreenContent.NightFinished) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.night_finished_title),
            style = MaterialTheme.typography.headlineMedium,
            color = AmberAccent,
            textAlign = TextAlign.Center,
        )
        Text(
            text = content.reasonText,
            style = MaterialTheme.typography.bodyLarge,
            color = NightOnSurfaceMuted,
            textAlign = TextAlign.Center,
        )
    }
}
