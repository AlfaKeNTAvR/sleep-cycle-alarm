package com.nikita.sleepcycle.ui.screens

// File purpose: the Logs screen - lists saved night logs, newest first, each shareable via the system share sheet.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.IconGlyphButton
import com.nikita.sleepcycle.ui.components.ScreenContainer
import com.nikita.sleepcycle.ui.components.SettingsCard
import com.nikita.sleepcycle.ui.state.LogsUiState
import com.nikita.sleepcycle.ui.state.NightLogSummary
import com.nikita.sleepcycle.ui.theme.AmberAccent
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted

/** The Logs screen: every saved night log, newest first, each shareable through the system share sheet. */
@Composable
fun LogsScreen(
    state: LogsUiState,
    onShareLog: (NightLogSummary) -> Unit,
    onBack: () -> Unit,
) {
    ScreenContainer(scrollable = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconGlyphButton(
                glyph = stringResource(R.string.glyph_back),
                contentDescription = stringResource(R.string.content_description_back),
                onClick = onBack,
            )
            Text(text = stringResource(R.string.logs_title), style = MaterialTheme.typography.headlineMedium)
        }
        if (state.logs.isEmpty()) {
            Text(text = stringResource(R.string.logs_empty), style = MaterialTheme.typography.bodyMedium, color = NightOnSurfaceMuted)
        }
        state.logs.forEach { log ->
            SettingsCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text = log.displayName, style = MaterialTheme.typography.titleMedium)
                            if (log.isSimulated) {
                                Text(
                                    text = stringResource(R.string.logs_simulated_tag),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AmberAccent,
                                )
                            }
                        }
                        Text(text = log.sizeLabel, style = MaterialTheme.typography.bodySmall, color = NightOnSurfaceMuted)
                    }
                    IconGlyphButton(
                        glyph = stringResource(R.string.glyph_share),
                        contentDescription = stringResource(R.string.content_description_share_log),
                        onClick = { onShareLog(log) },
                    )
                }
            }
        }
    }
}
