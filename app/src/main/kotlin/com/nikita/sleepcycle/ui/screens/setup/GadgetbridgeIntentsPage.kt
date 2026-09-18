package com.nikita.sleepcycle.ui.screens.setup

// File purpose: wizard page 3 - the four Gadgetbridge Intent API switches, one line each, nothing else.

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.SettingsCard

/** The four Gadgetbridge Developer-options Intent API switches this app relies on to talk to the band. */
@Composable
fun GadgetbridgeIntentsPage() {
    Text(text = stringResource(R.string.setup_wizard_gadgetbridge_intents_body), style = MaterialTheme.typography.bodyMedium)
    SettingsCard {
        stringArrayResource(R.array.setup_wizard_gadgetbridge_intents_lines).forEach { line ->
            Text(text = "• $line", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
