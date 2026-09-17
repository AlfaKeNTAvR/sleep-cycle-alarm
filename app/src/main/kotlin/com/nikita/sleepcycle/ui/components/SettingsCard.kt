package com.nikita.sleepcycle.ui.components

// File purpose: the rounded, bordered card used by every screen for one group of related content.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.nikita.sleepcycle.ui.theme.CardBorderWidth
import com.nikita.sleepcycle.ui.theme.CardContentGap
import com.nikita.sleepcycle.ui.theme.CardCornerRadius
import com.nikita.sleepcycle.ui.theme.CardPadding
import com.nikita.sleepcycle.ui.theme.NightSurface
import com.nikita.sleepcycle.ui.theme.NightSurfaceBorder

/** One rounded, bordered card grouping related content, styled to match the night-screen mockups. */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = CardPadding,
    content: @Composable () -> Unit,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCornerRadius),
        colors = CardDefaults.outlinedCardColors(containerColor = NightSurface),
        border = BorderStroke(CardBorderWidth, NightSurfaceBorder),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(CardContentGap),
        ) {
            content()
        }
    }
}
