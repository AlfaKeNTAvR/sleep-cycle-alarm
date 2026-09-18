package com.nikita.sleepcycle.ui.screens.setup

// File purpose: the wizard's progress indicator - a "Step X of N" line plus one dot per page, so where the
// owner is in the flow reads at a glance without counting pages.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.theme.AmberAccent
import com.nikita.sleepcycle.ui.theme.CardContentGap
import com.nikita.sleepcycle.ui.theme.ChipGap
import com.nikita.sleepcycle.ui.theme.ConnectedDot
import com.nikita.sleepcycle.ui.theme.NightOutline
import com.nikita.sleepcycle.ui.theme.StatusDotSize

/** "Step X of N" plus one dot per page: amber for the current page, green for pages already behind it, muted for pages still ahead. */
@Composable
fun SetupWizardProgress(currentPage: SetupWizardPage) {
    val order = SETUP_WIZARD_PAGE_ORDER
    val currentIndex = order.indexOf(currentPage)
    Column(verticalArrangement = Arrangement.spacedBy(CardContentGap)) {
        Text(
            text = stringResource(R.string.setup_wizard_step_label, currentIndex + 1, order.size),
            style = MaterialTheme.typography.labelLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ChipGap)) {
            order.forEachIndexed { index, _ ->
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(StatusDotSize)
                        .background(wizardProgressDotColor(index, currentIndex), CircleShape),
                )
            }
        }
    }
}

private fun wizardProgressDotColor(pageIndex: Int, currentIndex: Int): Color = when {
    pageIndex == currentIndex -> AmberAccent
    pageIndex < currentIndex -> ConnectedDot
    else -> NightOutline
}
