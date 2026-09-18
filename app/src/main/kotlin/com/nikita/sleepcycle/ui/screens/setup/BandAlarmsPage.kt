package com.nikita.sleepcycle.ui.screens.setup

// File purpose: wizard page 7 - the band-side alarm setting Gadgetbridge needs, plus the smart-slot and
// freeing-a-slot instructions "Test connection" (page 8) will otherwise surface only after the fact.

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.SettingsCard

/** The band's own alarm setting, plus the smart-wakeup-slot and free-slot notes ahead of Test connection. */
@Composable
fun BandAlarmsPage() {
    SettingsCard {
        Text(text = "• " + stringResource(R.string.setup_wizard_band_alarms_allow_third_party_line), style = MaterialTheme.typography.bodyMedium)
    }
    Text(text = stringResource(R.string.setup_wizard_band_alarms_smart_slot_note), style = MaterialTheme.typography.bodyMedium)
    Text(text = stringResource(R.string.setup_wizard_band_alarms_free_slot_note), style = MaterialTheme.typography.bodyMedium)
}
