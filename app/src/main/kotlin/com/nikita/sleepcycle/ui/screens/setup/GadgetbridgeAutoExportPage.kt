package com.nikita.sleepcycle.ui.screens.setup

// File purpose: wizard page 4 - Gadgetbridge's auto-export toggle and location, plus the warning not to use
// Data management > Export Data instead.

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.AmberWarningLine
import com.nikita.sleepcycle.ui.components.SettingsCard

/** Gadgetbridge's auto-export switch and export location, plus the one warning that saves a re-setup later. */
@Composable
fun GadgetbridgeAutoExportPage() {
    Text(text = stringResource(R.string.setup_wizard_gadgetbridge_auto_export_body), style = MaterialTheme.typography.bodyMedium)
    SettingsCard {
        Text(text = "• " + stringResource(R.string.setup_wizard_gadgetbridge_auto_export_enable_line), style = MaterialTheme.typography.bodyMedium)
        Text(text = "• " + stringResource(R.string.setup_wizard_gadgetbridge_auto_export_location_line), style = MaterialTheme.typography.bodyMedium)
    }
    AmberWarningLine(text = stringResource(R.string.setup_wizard_gadgetbridge_auto_export_warning))
}
