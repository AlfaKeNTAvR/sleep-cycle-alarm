package com.nikita.sleepcycle.ui.screens.setup

// File purpose: the chrome every wizard page shares - the back arrow plus title row, the progress indicator,
// the page's own content, and a Back/Next footer. Individual pages (WhatYouNeedPage.kt etc.) only supply
// their title and body content.

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.IconGlyphButton
import com.nikita.sleepcycle.ui.components.PrimaryActionButton
import com.nikita.sleepcycle.ui.components.ScreenContainer

/**
 * Wraps one wizard page's [content] with the shared back-arrow-plus-title row, the progress indicator, and a
 * "Next" footer button. [onBack] fires from the arrow when [page] is the first page (there is nowhere to step
 * back to, so the arrow exits the wizard instead); every other page uses [onPreviousPage]. [showNext] hides
 * the footer's Next button on the last page, which shows its own "Test connection" and "Done" actions in its
 * own content instead.
 */
@Composable
fun SetupWizardScaffold(
    page: SetupWizardPage,
    titleRes: Int,
    isFirstPage: Boolean,
    showNext: Boolean,
    onBack: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    content: @Composable () -> Unit,
) {
    ScreenContainer(scrollable = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconGlyphButton(
                glyph = stringResource(R.string.glyph_back),
                contentDescription = stringResource(R.string.content_description_back),
                onClick = if (isFirstPage) onBack else onPreviousPage,
            )
            Text(text = stringResource(titleRes), style = MaterialTheme.typography.headlineMedium)
        }
        SetupWizardProgress(currentPage = page)
        content()
        if (showNext) {
            PrimaryActionButton(text = stringResource(R.string.setup_wizard_next_button), onClick = onNextPage)
        }
    }
}
