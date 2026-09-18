package com.nikita.sleepcycle.ui.screens.setup

// File purpose: wizard page 2 - the band's Bluetooth address, prefilled.

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.SettingsCard
import com.nikita.sleepcycle.ui.state.SetupUiState

/** The band's Bluetooth MAC address field, prefilled with the owner's band. */
@Composable
fun BandAddressPage(state: SetupUiState, onDeviceMacChange: (String) -> Unit) {
    Text(text = stringResource(R.string.setup_wizard_band_address_body), style = MaterialTheme.typography.bodyMedium)
    SettingsCard {
        Text(text = stringResource(R.string.setup_device_mac_label), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.deviceMacText,
            onValueChange = onDeviceMacChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
}
