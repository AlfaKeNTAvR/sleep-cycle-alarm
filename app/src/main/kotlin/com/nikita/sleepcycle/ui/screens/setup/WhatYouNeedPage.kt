package com.nikita.sleepcycle.ui.screens.setup

// File purpose: wizard page 1 - what the owner needs before anything else, and whether Gadgetbridge was found.

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.SettingsCard
import com.nikita.sleepcycle.ui.components.StatusLine
import com.nikita.sleepcycle.ui.state.SetupItemKind
import com.nikita.sleepcycle.ui.state.SetupUiState

/** What the night needs (a band, Gadgetbridge) and whether Gadgetbridge was actually found on this phone. */
@Composable
fun WhatYouNeedPage(state: SetupUiState) {
    val gadgetbridgeFound = state.items.first { it.kind == SetupItemKind.GADGETBRIDGE_INSTALLED }.complete
    Text(text = stringResource(R.string.setup_wizard_what_you_need_body), style = MaterialTheme.typography.bodyMedium)
    SettingsCard {
        StatusLine(
            text = stringResource(
                if (gadgetbridgeFound) R.string.setup_wizard_gadgetbridge_found else R.string.setup_wizard_gadgetbridge_not_found
            ),
            ok = gadgetbridgeFound,
        )
    }
}
